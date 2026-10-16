package com.wms.platform.modules.md.app.impl;


import com.wms.platform.common.api.BizException;
import com.wms.platform.common.datasource.ReadOnly;
import com.wms.platform.common.cache.CacheKeys;
import com.wms.platform.common.cache.CacheService;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.security.PiiCipher;
import com.wms.platform.modules.md.api.ro.LocationCreateRo;
import com.wms.platform.modules.md.api.ro.MerchantCreateRo;
import com.wms.platform.modules.md.api.ro.SkuCreateRo;
import com.wms.platform.modules.md.api.ro.UnitConvCreateRo;
import com.wms.platform.modules.md.api.ro.WarehouseCreateRo;
import com.wms.platform.modules.md.api.ro.ZoneCreateRo;
import com.wms.platform.modules.md.api.vo.LocationVo;
import com.wms.platform.modules.md.api.vo.MerchantVo;
import com.wms.platform.modules.md.api.vo.SkuVo;
import com.wms.platform.modules.md.api.vo.UnitConvVo;
import com.wms.platform.modules.md.api.vo.WarehouseVo;
import com.wms.platform.modules.md.api.vo.ZoneVo;
import com.wms.platform.modules.md.app.MasterDataAdminService;
import com.wms.platform.modules.md.domain.UnitConversion;
import com.wms.platform.modules.md.infrastructure.mapper.LocationMapper;
import com.wms.platform.modules.md.infrastructure.mapper.MerchantMapper;
import com.wms.platform.modules.md.infrastructure.mapper.SkuMapper;
import com.wms.platform.modules.md.infrastructure.mapper.SkuUnitConvMapper;
import com.wms.platform.modules.md.infrastructure.mapper.WarehouseMapper;
import com.wms.platform.modules.md.infrastructure.mapper.ZoneMapper;
import com.wms.platform.modules.md.infrastructure.po.LocationPo;
import com.wms.platform.modules.md.infrastructure.po.MdMerchantPo;
import com.wms.platform.modules.md.infrastructure.po.MdWarehousePo;
import com.wms.platform.modules.md.infrastructure.po.MdZonePo;
import com.wms.platform.modules.md.infrastructure.po.SkuPo;
import com.wms.platform.modules.md.infrastructure.po.SkuUnitConvPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主数据维护服务实现。
 *
 * <p>规范落地：B1（Wrapper 只在 Mapper）、B4（Po 静态工厂）、B5（异常带码）、
 * B7（编号步骤注释）、B9（构造注入）、PII（手机号加密 + 脱敏，{@code 01} §7）。
 */
@Service
@RequiredArgsConstructor
public class MasterDataAdminServiceImpl implements MasterDataAdminService {

    /**
     * 缓存（P3.2）：主数据变更后必须失效解析缓存，否则现场会继续用旧结论。
     *
     * <p><b>为什么在"事务提交后"失效而不是立即失效</b>（真实并发陷阱）：
     * 若在事务内失效，另一个并发请求可能在**本事务提交前**读到旧数据并重新写入缓存，
     * 于是本次变更"失效了又立刻被填回旧值"——缓存里留下错误结论，且不会自己恢复。
     * 注册到 {@code afterCommit} 后，失效发生在数据真正可见之后，不存在这个窗口。
     */
    private final CacheService cacheService;

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(MasterDataAdminServiceImpl.class);

    /** 商家 Mapper。 */
    private final MerchantMapper merchantMapper;

    /** 仓库 Mapper。 */
    private final WarehouseMapper warehouseMapper;

    /** 库区 Mapper。 */
    private final ZoneMapper zoneMapper;

    /** 库位 Mapper。 */
    private final LocationMapper locationMapper;

    /** 商品 Mapper。 */
    private final SkuMapper skuMapper;

    /** 箱规 Mapper。 */
    private final SkuUnitConvMapper unitConvMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** PII 加解密。 */
    private final PiiCipher piiCipher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createMerchant(MerchantCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 唯一性校验（先给人话错误；唯一键是最后防线）
        if (merchantMapper.selectByNo(tenant, ro.merchantNo()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "商家编号已存在：" + ro.merchantNo());
        }
        // 2. 手机号：加密存密文 + 存脱敏值（列表默认只读脱敏列）
        String enc = ro.contactPhone() == null || ro.contactPhone().isBlank()
                ? null : piiCipher.encrypt(ro.contactPhone().trim());
        String masked = ro.contactPhone() == null || ro.contactPhone().isBlank()
                ? null : PiiCipher.maskPhone(ro.contactPhone().trim());
        // 3. 落库
        MdMerchantPo po = MdMerchantPo.of(idGenerator.nextId(), tenant, ro.merchantNo().trim(), ro.name().trim(),
                ro.contactName(), enc, masked);
        po.setCreatedBy(TenantContext.userId());
        merchantMapper.insert(po);
        log.info("新增商家 merchantNo={} id={}", po.getMerchantNo(), po.getId());
        return po.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createWarehouse(WarehouseCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 唯一性校验
        if (warehouseMapper.selectByCode(tenant, ro.warehouseCode()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仓库编码已存在：" + ro.warehouseCode());
        }
        // 2. 落库（截单时间默认 18:00，由 Po 工厂兜底）
        MdWarehousePo po = MdWarehousePo.of(idGenerator.nextId(), tenant, ro.warehouseCode().trim(), ro.name().trim(),
                ro.province(), ro.city(), ro.address(), ro.cutoffTime());
        po.setCreatedBy(TenantContext.userId());
        warehouseMapper.insert(po);
        return po.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createZone(ZoneCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 仓库必须存在（否则会造出"挂在不存在仓库下"的库区，库位校验也就失去意义）
        MdWarehousePo warehouse = warehouseMapper.selectByIdSafe(tenant, ro.warehouseId());
        if (warehouse == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仓库不存在：" + ro.warehouseId());
        }
        // 2. 库区编码仓库内唯一
        if (zoneMapper.selectByCode(tenant, ro.warehouseId(), ro.zoneCode()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该仓库下库区编码已存在：" + ro.zoneCode());
        }
        // 3. 类型合法性（隔离区标记由类型推导）
        MdZonePo po = MdZonePo.of(idGenerator.nextId(), tenant, ro.warehouseId(), ro.zoneCode().trim(),
                normalizeZoneType(ro.zoneType()), ro.name());
        po.setCreatedBy(TenantContext.userId());
        zoneMapper.insert(po);
        return po.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createLocation(LocationCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 库区必须存在且属于该仓库（跨仓库挂库位会导致跨仓拣货，库存永远对不上）
        MdZonePo zone = zoneMapper.selectByIdSafe(tenant, ro.zoneId());
        if (zone == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "库区不存在：" + ro.zoneId());
        }
        if (!zone.getWarehouseId().equals(ro.warehouseId())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "库区 " + ro.zoneId() + " 属于仓库 " + zone.getWarehouseId()
                            + "，与传入仓库 " + ro.warehouseId() + " 不一致");
        }
        // 2. 库位编码仓库内唯一
        if (locationMapper.selectByCode(tenant, ro.warehouseId(), ro.locationCode()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该仓库下库位编码已存在：" + ro.locationCode());
        }
        // 3. 落库（可拣默认 true、托盘位默认 1；状态由 Po 工厂内置为「启用」，Service 不得直接 setStatus）
        LocationPo po = LocationPo.of(idGenerator.nextId(), tenant, ro.warehouseId(), ro.zoneId(),
                ro.locationCode().trim(), ro.abcClass(), ro.isPickable() == null || ro.isPickable(),
                ro.quotaUnit() == null ? 1 : ro.quotaUnit());
        po.setCreatedBy(TenantContext.userId());
        locationMapper.insert(po);
        // 主数据变更 → 失效解析缓存（提交后执行，避免并发回填旧值；**必须在 return 之前**，
        // 否则 return 之后的语句不可达）
        evictAfterCommit(CacheKeys.MD_LOCATION_ID);
        return po.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSku(SkuCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 单位必须是受支持的量纲（件/箱/托），否则后面的换算与计费都没有基准
        UnitConversion.assertUnitSupported(ro.unit());
        // 2. 条码在商家内唯一
        if (skuMapper.selectByCode(tenant, ro.merchantId(), ro.skuCode()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该商家下商品条码已存在：" + ro.skuCode());
        }
        // 3. 禁入品类必须给原因（否则几个月后没人说得清为什么禁）
        boolean forbidden = ro.isForbidden() != null && ro.isForbidden();
        if (forbidden && (ro.forbiddenReason() == null || ro.forbiddenReason().isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "标记禁入品类时必须填写禁入原因");
        }
        // 4. 落库（状态由 Po 工厂内置为「启用」；Service 不得直接 setStatus —— 01 §1.2）
        SkuPo po = SkuPo.of(idGenerator.nextId(), tenant, ro.merchantId(), ro.skuCode().trim(), ro.name().trim(),
                ro.category(), ro.unit().trim(), ro.weightKg(), ro.volumeRatio(), forbidden,
                ro.forbiddenReason());
        po.setCreatedBy(TenantContext.userId());
        skuMapper.insert(po);
        // 主数据变更 → 失效解析缓存（提交后执行，避免并发回填旧值；**必须在 return 之前**，
        // 否则 return 之后的语句不可达）
        evictAfterCommit(CacheKeys.MD_SKU_ID);
        return po.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addUnitConversion(long skuId, UnitConvCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 领域校验：单位合法、不能自换算、系数为正
        UnitConversion.assertConfigValid(ro.unitFrom(), ro.unitTo(), ro.factor());
        // 2. 商品必须存在（否则会造出指向空气的换算，谁也不知道它属于哪个商品）
        SkuPo sku = skuMapper.selectByIdSafe(tenant, skuId);
        if (sku == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "商品不存在：" + skuId);
        }
        // 3. 唯一性
        if (unitConvMapper.selectOneConv(tenant, skuId, ro.unitFrom(), ro.unitTo()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该商品的换算关系已存在：" + ro.unitFrom() + " → " + ro.unitTo());
        }
        // 4. 落库
        SkuUnitConvPo po = SkuUnitConvPo.of(idGenerator.nextId(), tenant, skuId, ro.unitFrom().trim(),
                ro.unitTo().trim(), ro.factor());
        po.setCreatedBy(TenantContext.userId());
        unitConvMapper.insert(po);
        // 主数据变更 → 失效解析缓存（提交后执行，避免并发回填旧值；**必须在 return 之前**，
        // 否则 return 之后的语句不可达）
        evictAfterCommit(CacheKeys.MD_SKU_BRIEF);
        return po.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal convertQty(long skuId, BigDecimal qty, String fromUnit, String toUnit) {
        // 1. 取该商品的换算表
        Map<String, BigDecimal> factors = factorsOf(skuId);
        // 2. 换算（无路径会抛 10001，不返回原值——静默返回原值等于把量纲错藏起来）
        return UnitConversion.convert(qty, fromUnit, toUnit, factors);
    }

    @Override
    @ReadOnly("后台列表浏览：允许轻微复制延迟，且返回值不参与后续写决策")
    @Transactional(readOnly = true)
    public List<MerchantVo> listMerchants(String status) {
        List<MdMerchantPo> list = merchantMapper.selectListByStatus(TenantContext.tenantId(), status);
        List<MerchantVo> vos = new ArrayList<>(list.size());
        for (MdMerchantPo po : list) {
            vos.add(new MerchantVo(po.getId(), po.getMerchantNo(), po.getName(), po.getContactName(),
                    po.getContactPhoneMasked(), po.getStatus(), po.getCreditScore()));
        }
        return vos;
    }

    @Override
    @ReadOnly("后台列表浏览：允许轻微复制延迟，且返回值不参与后续写决策")
    @Transactional(readOnly = true)
    public List<WarehouseVo> listWarehouses() {
        List<MdWarehousePo> list = warehouseMapper.selectListAll(TenantContext.tenantId());
        List<WarehouseVo> vos = new ArrayList<>(list.size());
        for (MdWarehousePo po : list) {
            vos.add(new WarehouseVo(po.getId(), po.getWarehouseCode(), po.getName(), po.getProvince(), po.getCity(),
                    po.getAddress(), po.getCutoffTime(), po.getStatus()));
        }
        return vos;
    }

    @Override
    @ReadOnly("后台列表浏览：允许轻微复制延迟，且返回值不参与后续写决策")
    @Transactional(readOnly = true)
    public List<ZoneVo> listZones(long warehouseId) {
        List<MdZonePo> list = zoneMapper.selectByWarehouse(TenantContext.tenantId(), warehouseId);
        List<ZoneVo> vos = new ArrayList<>(list.size());
        for (MdZonePo po : list) {
            vos.add(new ZoneVo(po.getId(), po.getWarehouseId(), po.getZoneCode(), po.getZoneType(), po.getName(),
                    po.getIsolationFlag() != null && po.getIsolationFlag() == 1));
        }
        return vos;
    }

    @Override
    @ReadOnly("后台列表浏览：允许轻微复制延迟，且返回值不参与后续写决策")
    @Transactional(readOnly = true)
    public List<LocationVo> listLocations(long warehouseId) {
        List<LocationPo> list = locationMapper.selectByWarehouse(TenantContext.tenantId(), warehouseId);
        List<LocationVo> vos = new ArrayList<>(list.size());
        for (LocationPo po : list) {
            vos.add(new LocationVo(po.getId(), po.getWarehouseId(), po.getZoneId(), po.getLocationCode(),
                    po.getAbcClass(), po.isPickable(), po.getQuotaUnit(), po.getStatus()));
        }
        return vos;
    }

    @Override
    @ReadOnly("后台列表浏览：允许轻微复制延迟，且返回值不参与后续写决策")
    @Transactional(readOnly = true)
    public List<SkuVo> listSkus(long merchantId) {
        List<SkuPo> list = skuMapper.selectByMerchant(TenantContext.tenantId(), merchantId);
        List<SkuVo> vos = new ArrayList<>(list.size());
        for (SkuPo po : list) {
            vos.add(new SkuVo(po.getId(), po.getMerchantId(), po.getSkuCode(), po.getName(), po.getCategory(),
                    po.getUnit(), po.getWeightKg(), po.getVolumeRatio(), po.isForbiddenCategory(),
                    po.getForbiddenReason(), po.getStatus()));
        }
        return vos;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnitConvVo> listUnitConversions(long skuId) {
        List<SkuUnitConvPo> list = unitConvMapper.selectBySku(TenantContext.tenantId(), skuId);
        List<UnitConvVo> vos = new ArrayList<>(list.size());
        for (SkuUnitConvPo po : list) {
            vos.add(new UnitConvVo(po.getSkuId(), po.getUnitFrom(), po.getUnitTo(), po.getFactor()));
        }
        return vos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeMerchantStatus(long merchantId, boolean enabled) {
        // 1. 存在性校验（不存在的 ID 静默成功，会让调用方以为改成功了）
        if (merchantMapper.selectByIdSafe(TenantContext.tenantId(), merchantId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "商家不存在：" + merchantId);
        }
        // 2. 更新（条件构造器收口在 Mapper，B1）
        merchantMapper.updateStatus(TenantContext.tenantId(), merchantId,
                enabled ? MdMerchantPo.STATUS_ACTIVE : MdMerchantPo.STATUS_DISABLED, TenantContext.userId());
        log.info("商家状态变更为 {}", enabled ? MdMerchantPo.STATUS_ACTIVE : MdMerchantPo.STATUS_DISABLED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeWarehouseStatus(long warehouseId, boolean enabled) {
        if (warehouseMapper.selectByIdSafe(TenantContext.tenantId(), warehouseId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仓库不存在：" + warehouseId);
        }
        warehouseMapper.updateStatus(TenantContext.tenantId(), warehouseId,
                enabled ? MdWarehousePo.STATUS_ENABLED : MdWarehousePo.STATUS_DISABLED, TenantContext.userId());
        log.info("仓库状态变更为 {}", enabled ? MdWarehousePo.STATUS_ENABLED : MdWarehousePo.STATUS_DISABLED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeLocationStatus(long locationId, boolean enabled) {
        if (locationMapper.selectByIdSafe(TenantContext.tenantId(), locationId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "库位不存在：" + locationId);
        }
        locationMapper.updateStatus(TenantContext.tenantId(), locationId,
                enabled ? LocationPo.STATUS_ENABLED : LocationPo.STATUS_DISABLED, TenantContext.userId());
        log.info("库位状态变更为 {}", enabled ? LocationPo.STATUS_ENABLED : LocationPo.STATUS_DISABLED);
        // 主数据变更 → 失效解析缓存（提交后执行，避免并发回填旧值）
        evictAfterCommit(CacheKeys.MD_LOCATION_ID);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeSkuStatus(long skuId, boolean enabled) {
        if (skuMapper.selectByIdSafe(TenantContext.tenantId(), skuId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "商品不存在：" + skuId);
        }
        skuMapper.updateStatus(TenantContext.tenantId(), skuId,
                enabled ? SkuPo.STATUS_ENABLED : SkuPo.STATUS_DISABLED, TenantContext.userId());
        log.info("商品状态变更为 {}", enabled ? SkuPo.STATUS_ENABLED : SkuPo.STATUS_DISABLED);
        // 主数据变更 → 失效解析缓存（提交后执行，避免并发回填旧值）
        evictAfterCommit(CacheKeys.MD_SKU_ID);
    }

    /**
     * 取某商品的换算表。
     *
     * @param skuId 商品 ID
     * @return key（from&gt;to）→ 系数
     */
    private Map<String, BigDecimal> factorsOf(long skuId) {
        List<SkuUnitConvPo> list = unitConvMapper.selectBySku(TenantContext.tenantId(), skuId);
        Map<String, BigDecimal> factors = new HashMap<>(list.size());
        for (SkuUnitConvPo po : list) {
            factors.put(UnitConversion.key(po.getUnitFrom(), po.getUnitTo()), po.getFactor());
        }
        return factors;
    }

    /**
     * 库区类型归一化（非法类型当场拒绝，避免库里出现"零散库区"这类自由文本）。
     *
     * @param zoneType 类型
     * @return 归一化后的类型
     */
    private String normalizeZoneType(String zoneType) {
        String type = zoneType == null ? "" : zoneType.trim();
        List<String> allowed = List.of(MdZonePo.TYPE_STORAGE, MdZonePo.TYPE_PICKING, MdZonePo.TYPE_CHECKING,
                MdZonePo.TYPE_ISOLATION, MdZonePo.TYPE_RETURN);
        if (!allowed.contains(type)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "不支持的库区类型：" + zoneType + "（仅支持 " + String.join("/", allowed) + "）");
        }
        return type;
    }
    /**
     * 事务提交后按前缀失效缓存（无事务时立即失效）。
     *
     * @param keyPrefix 缓存键前缀
     */
    private void evictAfterCommit(String keyPrefix) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cacheService.evictByPrefix(keyPrefix);
                        }
                    });
        } else {
            cacheService.evictByPrefix(keyPrefix);
        }
    }
}
