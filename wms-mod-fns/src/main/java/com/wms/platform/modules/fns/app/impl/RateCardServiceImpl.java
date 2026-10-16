package com.wms.platform.modules.fns.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.datasource.ReadOnly;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.cache.CacheKeys;
import com.wms.platform.common.cache.CacheService;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.fns.app.RateCardService;
import com.wms.platform.modules.fns.domain.rate.FreightQuote;
import com.wms.platform.modules.fns.domain.rate.FreightRuleEngine;
import com.wms.platform.modules.fns.domain.rate.ParcelFacts;
import com.wms.platform.modules.fns.domain.rate.RateCard;
import com.wms.platform.modules.fns.domain.rate.RateItem;
import com.wms.platform.modules.fns.infrastructure.mapper.RateCardMapper;
import com.wms.platform.modules.fns.infrastructure.mapper.RateItemMapper;
import com.wms.platform.modules.fns.infrastructure.po.RateCardPo;
import com.wms.platform.modules.fns.infrastructure.po.RateItemPo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * 价目表与运费试算实现。
 *
 * <p><b>缓存</b>：启用的价目表是典型的"读多写少 + 变更可精确失效"数据，
 * 每次试算都查两张表没有必要 → 用 P3.2 的 {@link CacheService} 缓存**整张卡（含明细）**，
 * 并在版本启用/停用后失效（**提交后**失效，避免并发请求在提交前读到旧版本并回填）。
 *
 * <p>缓存的是"卡 + 明细"整体：计费必然同时需要两者，分开缓存只会多一次往返与一个不一致窗口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateCardServiceImpl implements RateCardService {

    /** 默认价目表编码（试算未指定时使用）。 */
    private static final String DEFAULT_CARD_CODE = "FREIGHT-DEFAULT";

    /** 价目表缓存 TTL（分钟）：价目表很少变，但调价必须尽快生效 → 短 TTL + 变更显式失效。 */
    private static final int CARD_CACHE_TTL_MINUTES = 10;

    /** 缓存键前缀（失效时按前缀清）。 */
    private static final String CACHE_PREFIX = "fns:rate-card";

    /** 价目表 Mapper。 */
    private final RateCardMapper rateCardMapper;

    /** 明细 Mapper。 */
    private final RateItemMapper rateItemMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** 缓存。 */
    private final CacheService cacheService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createVersion(String cardCode, String name, String carrierCode, BigDecimal bufferRatio) {
        long tenant = TenantContext.tenantId();
        // 1. 版本号 = 当前最大版本 + 1（调价新增版本，历史版本保留以便复算）
        int nextVersion = rateCardMapper.maxVersion(tenant, cardCode) + 1;
        // 2. 落库（状态=草稿：必须显式启用才参与计费，避免"建了就开始计费"）
        RateCardPo po = RateCardPo.of(idGenerator.nextId(), tenant, cardCode, name, carrierCode, nextVersion,
                bufferRatio, TenantContext.userId());
        rateCardMapper.insert(po);
        log.info("[价目表] 新建版本 cardCode={} version={} carrier={} bufferRatio={}",
                cardCode, nextVersion, carrierCode, bufferRatio);
        return nextVersion;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addItem(String cardCode, int version, String regionCode, BigDecimal weightFrom, BigDecimal weightTo,
                        BigDecimal firstWeight, BigDecimal firstPrice, BigDecimal addUnit, BigDecimal addPrice,
                        BigDecimal volumeDivisor, BigDecimal minCharge, int priority) {
        long tenant = TenantContext.tenantId();
        // 1. 只允许改草稿版本：已启用的版本改价会让历史运费失去解释力
        RateCardPo card = requireVersion(tenant, cardCode, version);
        if (!RateCardPo.STATUS_DRAFT.equals(card.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "价目表 " + cardCode + " v" + version + " 已是「" + card.getStatus()
                            + "」，不允许修改；请新建版本（保证历史运费可复算）");
        }
        // 2. 区间语义校验（DB 有 CHECK 兜底，这里先给业务可读的报错）
        if (weightTo != null && weightTo.compareTo(weightFrom) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "重量区间上界必须大于下界：" + weightFrom + " → " + weightTo);
        }
        // 3. 落库
        RateItemPo po = RateItemPo.of(idGenerator.nextId(), tenant, card.getId());
        po.setRegionCode(regionCode);
        po.setWeightFrom(weightFrom);
        po.setWeightTo(weightTo);
        po.setFirstWeight(firstWeight);
        po.setFirstPrice(firstPrice);
        po.setAddUnit(addUnit);
        po.setAddPrice(addPrice);
        po.setVolumeDivisor(volumeDivisor);
        po.setMinCharge(minCharge);
        po.setPriority(priority);
        rateItemMapper.insert(po);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableVersion(String cardCode, int version) {
        long tenant = TenantContext.tenantId();
        RateCardPo target = requireVersion(tenant, cardCode, version);
        // 1. 明细不能为空：空价目表启用后试算会全部 40007（现场直接不可用，不如现在就拒绝）
        if (rateItemMapper.selectByCard(tenant, target.getId()).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "价目表 " + cardCode + " v" + version + " 没有明细，启用后无法计费");
        }
        // 2. 先停用同编码的其它启用版本（同一时刻只有一个生效版本）
        for (RateCardPo other : rateCardMapper.selectVersions(tenant, cardCode)) {
            if (RateCardPo.STATUS_ENABLED.equals(other.getStatus()) && !other.getId().equals(target.getId())) {
                other.disable();
                rateCardMapper.updateById(other);
                log.info("[价目表] 停用旧版本 cardCode={} version={}", cardCode, other.getVersion());
            }
        }
        // 3. 启用目标版本
        target.enable();
        rateCardMapper.updateById(target);
        log.info("[价目表] 已启用 cardCode={} version={}", cardCode, version);
        // 4. 提交后失效缓存
        evictAfterCommit();
    }

    @Override
    @ReadOnly("价目表明细查看：仅用于人工核对，计费本身走 FreightRuleEngine（主库加载的卡）")
    public List<RateItem> itemsOf(String cardCode, int version) {
        long tenant = TenantContext.tenantId();
        RateCardPo card = requireVersion(tenant, cardCode, version);
        return loadItems(tenant, card.getId());
    }

    @Override
    public FreightQuote estimate(String cardCode, ParcelFacts facts, BigDecimal bufferRatioOverride) {
        String code = cardCode == null || cardCode.isBlank() ? DEFAULT_CARD_CODE : cardCode;
        RateCard card = loadEnabledCard(code, bufferRatioOverride);
        FreightQuote quote = FreightRuleEngine.quote(card, facts);
        log.info("[运费试算] cardCode={} v{} 区域={} 实重={} 体积重={} 计费重={} 运费={} 预估价={}",
                quote.cardCode(), quote.cardVersion(), facts.regionCode(), quote.actualWeightKg(),
                quote.volumeWeightKg(), quote.chargeableWeight(), quote.freightFee(), quote.estimatedFee());
        return quote;
    }

    /**
     * 载入启用的价目表（带缓存）。
     *
     * @param cardCode            编码
     * @param bufferRatioOverride 缓冲系数覆盖（为空用卡自带）
     * @return 价目表
     */
    private RateCard loadEnabledCard(String cardCode, BigDecimal bufferRatioOverride) {
        long tenant = TenantContext.tenantId();
        String key = CacheKeys.of(CACHE_PREFIX, tenant, cardCode);
        RateCard cached = cacheService.get(key, RateCard.class);
        if (cached != null) {
            return bufferRatioOverride == null ? cached
                    : new RateCard(cached.cardCode(), cached.carrierCode(), cached.version(),
                            bufferRatioOverride, cached.items());
        }
        RateCardPo po = rateCardMapper.selectEnabled(tenant, cardCode);
        if (po == null) {
            // 没有启用版本 → 明确报错，绝不静默按 0 计费
            throw new BizException(ErrorCode.RATE_CARD_UNAVAILABLE,
                    "价目表 " + cardCode + " 没有启用版本（请先配置并启用，或指定 cardCode）");
        }
        BigDecimal buffer = bufferRatioOverride == null ? po.getBufferRatio() : bufferRatioOverride;
        RateCard card = new RateCard(po.getCardCode(), po.getCarrierCode(), po.getVersion(), buffer,
                loadItems(tenant, po.getId()));
        cacheService.put(key, card, Duration.ofMinutes(CARD_CACHE_TTL_MINUTES));
        return card;
    }

    /**
     * 载入明细并转成领域对象。
     *
     * @param tenant 租户
     * @param cardId 价目表 ID
     * @return 明细列表
     */
    private List<RateItem> loadItems(long tenant, long cardId) {
        return rateItemMapper.selectByCard(tenant, cardId).stream()
                .map(po -> new RateItem(po.getRegionCode(), po.getWeightFrom(), po.getWeightTo(),
                        po.getFirstWeight(), po.getFirstPrice(), po.getAddUnit(), po.getAddPrice(),
                        po.getVolumeDivisor(), po.getMinCharge(), po.getPriority()))
                .toList();
    }

    /**
     * 按编码+版本取价目表（不存在则报错）。
     *
     * @param tenant   租户
     * @param cardCode 编码
     * @param version  版本
     * @return 价目表
     */
    private RateCardPo requireVersion(long tenant, String cardCode, int version) {
        return rateCardMapper.selectVersions(tenant, cardCode).stream()
                .filter(po -> po.getVersion() == version)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.RATE_CARD_UNAVAILABLE,
                        "价目表不存在：" + cardCode + " v" + version));
    }

    /**
     * 事务提交后失效价目表缓存（避免并发请求在提交前读到旧版本并回填）。
     */
    private void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cacheService.evictByPrefix(CACHE_PREFIX);
                }
            });
        } else {
            cacheService.evictByPrefix(CACHE_PREFIX);
        }
    }
}
