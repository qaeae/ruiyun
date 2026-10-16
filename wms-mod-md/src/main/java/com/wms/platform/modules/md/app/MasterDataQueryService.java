package com.wms.platform.modules.md.app;

import java.math.BigDecimal;

/**
 * 主数据查询服务（W3 基础能力）。
 *
 * <p><b>它存在的意义是把"业务编码 → 内部 ID"的解析收敛到一处</b>：
 * 库存接口（API-IV-*）、出库下单、PDA 补传都要做这件事。若各自实现，
 * 会出现"某处按条码查、某处按 ID 查"的不一致，而这类不一致的后果是
 * 同一件货在两处被当成两个 SKU（库存永远对不平）。
 *
 * <p>解析失败一律返回 {@code null}（**不抛异常**）：调用方最清楚该把它当成
 * 参数错误（4xx）还是"错扫"（回推 PDA 提示重扫），契约层不该替它决定。
 */
public interface MasterDataQueryService {

    /**
     * 解析商品内部 ID。
     *
     * @param merchantId 商家 ID
     * @param skuCode    商品条码
     * @return 商品 ID；不存在或已停用返回 {@code null}
     */
    Long findSkuId(long merchantId, String skuCode);

    /**
     * 解析库位内部 ID。
     *
     * @param warehouseId  仓库 ID
     * @param locationCode 库位编码
     * @return 库位 ID；不存在或已停用返回 {@code null}
     */
    Long findLocationId(long warehouseId, String locationCode);

    /**
     * 校验库位可用于拣货（存在 + 启用 + `is_pickable=1`）。
     *
     * @param warehouseId  仓库 ID
     * @param locationCode 库位编码
     * @throws com.wms.platform.common.api.BizException 库位不存在/停用/不可拣时抛 {@code 50003}
     */
    void assertLocationPickable(long warehouseId, String locationCode);

    /**
     * 商品简要信息（计费与禁入校验用）。
     *
     * @param merchantId 商家 ID
     * @param skuCode    商品条码
     * @return 商品简要信息；不存在返回 {@code null}
     */
    SkuBrief findSku(long merchantId, String skuCode);

    /**
     * 商品简要信息。
     *
     * @param id               商品 ID
     * @param skuCode          条码
     * @param name             名称
     * @param unit             单位
     * @param weightKg         单件重量（可空）
     * @param volumeRatio      体积/抛比（可空）
     * @param forbiddenCategory 是否禁入品类
     */
    record SkuBrief(Long id, String skuCode, String name, String unit, BigDecimal weightKg,
                    BigDecimal volumeRatio, boolean forbiddenCategory) {
    }
}
