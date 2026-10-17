package com.wms.platform.modules.md.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.cache.CacheKeys;
import com.wms.platform.common.cache.CacheService;
import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.md.app.MasterDataQueryService;
import com.wms.platform.modules.md.infrastructure.mapper.LocationMapper;
import com.wms.platform.modules.md.infrastructure.mapper.SkuMapper;
import com.wms.platform.modules.md.infrastructure.mapper.WarehouseMapper;
import com.wms.platform.modules.md.infrastructure.po.LocationPo;
import com.wms.platform.modules.md.infrastructure.po.MdWarehousePo;
import com.wms.platform.modules.md.infrastructure.po.SkuPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 主数据查询服务实现。
 *
 * <p>规范落地：B5（异常带码）、B7（编号步骤注释）、B9（构造注入）。
 */
@Service
@RequiredArgsConstructor
public class MasterDataQueryServiceImpl implements MasterDataQueryService {

    /** 商品 Mapper。 */
    private final SkuMapper skuMapper;

    /** 库位 Mapper。 */
    private final LocationMapper locationMapper;

    /** 仓库 Mapper（截单时间按仓可配，见 {@code 01} §3.9）。 */
    private final WarehouseMapper warehouseMapper;

    /**
     * 缓存（P3.2）：只缓存**正向解析结果**（编码 → ID / SKU 明细）。
     *
     * <p><b>为什么可以缓存这里</b>：解析结果是读多写少的数据（PDA 每次扫码都要解析），
     * 且变更入口集中（主数据管理接口），可以做到"变更即失效"。
     *
     * <p><b>为什么不缓存 {@link #assertLocationPickable}</b>：它是**作业准入校验**，
     * 一旦缓存，停用库位后仍可能在 TTL 内被放行（现场就会在停用库位上作业）。
     * 校验类查询必须实时——一次库查询远比"在错误库位上产生库存流水"便宜。
     */
    private final CacheService cacheService;

    /** 配置（缓存 TTL）。 */
    private final WmsProperties wmsProperties;

    @Override
    public Long findSkuId(long merchantId, String skuCode) {
        // 1. 空值直接返回 null（不抛异常：调用方决定是参数错误还是错扫）
        if (skuCode == null || skuCode.isBlank()) {
            return null;
        }
        String key = CacheKeys.of(CacheKeys.MD_SKU_ID, TenantContext.tenantId(), merchantId, skuCode.trim());
        // 2. 先读缓存（未命中返回 null → 回源）
        Long cached = cacheService.get(key, Long.class);
        if (cached != null) {
            return cached;
        }
        // 3. 回源查主数据
        SkuPo po = skuMapper.selectByCode(TenantContext.tenantId(), merchantId, skuCode.trim());
        // 4. 停用的商品不可用（视为解析失败，避免停用品继续产生库存流水）
        if (po == null || !po.isEnabled()) {
            // 负结果**不缓存**：刚新建的 SKU 不应该被"不存在"的缓存挡住（见 CacheService 口径）
            return null;
        }
        // 5. 只缓存正结果，并带 TTL（防止脏数据永久驻留）
        cacheService.put(key, po.getId(),
                java.time.Duration.ofMinutes(wmsProperties.getCache().getMasterDataTtlMinutes()));
        return po.getId();
    }

    @Override
    public Long findLocationId(long warehouseId, String locationCode) {
        // 1. 空值直接返回 null
        if (locationCode == null || locationCode.isBlank()) {
            return null;
        }
        String key = CacheKeys.of(CacheKeys.MD_LOCATION_ID, TenantContext.tenantId(), warehouseId,
                locationCode.trim());
        // 2. 先读缓存
        Long cached = cacheService.get(key, Long.class);
        if (cached != null) {
            return cached;
        }
        // 3. 回源查主数据
        LocationPo po = locationMapper.selectByCode(TenantContext.tenantId(), warehouseId, locationCode.trim());
        // 4. 停用库位视为不存在（现场不允许在停用库位上作业）
        if (po == null || !po.isEnabled()) {
            return null;
        }
        // 5. 只缓存正结果
        cacheService.put(key, po.getId(),
                java.time.Duration.ofMinutes(wmsProperties.getCache().getMasterDataTtlMinutes()));
        return po.getId();
    }

    @Override
    public void assertLocationPickable(long warehouseId, String locationCode) {
        // 1. 查主数据：不存在 → 50003（库位不可拣，03 §1.2 已登记该码）
        LocationPo po = locationMapper.selectByCode(TenantContext.tenantId(), warehouseId, locationCode);
        if (po == null) {
            throw new BizException(ErrorCode.LOCATION_NOT_PICKABLE, "库位不存在：" + locationCode);
        }
        // 2. 停用 → 同样拒绝
        if (!po.isEnabled()) {
            throw new BizException(ErrorCode.LOCATION_NOT_PICKABLE, "库位已停用：" + locationCode);
        }
        // 3. 不可拣（如存储区大库位）→ 拒绝
        if (!po.isPickable()) {
            throw new BizException(ErrorCode.LOCATION_NOT_PICKABLE, "库位不可拣：" + locationCode);
        }
    }

    @Override
    public java.time.LocalTime findCutoffTime(long warehouseId) {
        // 1. 按租户 + 仓库 ID 精确查（**不缓存**：17:5x 现场改截单时间是常事，
        //    缓存 5 分钟就可能把当日单误判成次日单）
        MdWarehousePo po = warehouseMapper.selectByIdSafe(TenantContext.tenantId(), warehouseId);
        // 2. 仓库不存在或未配截单 → null，由调用方回退全局配置（不在这里替它决定）
        return po == null || po.getCutoffTime() == null ? null : po.getCutoffTime();
    }

    @Override
    public SkuBrief findSku(long merchantId, String skuCode) {
        // 1. 查主数据
        if (skuCode == null || skuCode.isBlank()) {
            return null;
        }
        SkuPo po = skuMapper.selectByCode(TenantContext.tenantId(), merchantId, skuCode.trim());
        if (po == null) {
            return null;
        }
        // 2. 转简要信息（不含审计字段，避免把主数据细节泄漏给调用方）
        return new SkuBrief(po.getId(), po.getSkuCode(), po.getName(), po.getUnit(), po.getWeightKg(),
                po.getVolumeRatio(), po.isForbiddenCategory());
    }
}
