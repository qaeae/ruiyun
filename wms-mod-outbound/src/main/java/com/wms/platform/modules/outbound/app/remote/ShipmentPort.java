package com.wms.platform.modules.outbound.app.remote;

import java.math.BigDecimal;

/**
 * 装运单端口（越界依赖的**唯一出口之二**：出库 → 物流）。
 *
 * <p><b>为什么出库需要物流</b>：{@code 01} §2.1 状态机第 1 行的副作用包含"生成 {@code lg_shipment}
 * （物流费）"，而打包环节的"面单与 DO 绑定校验"（{@code 20004}）也以装运单为凭据——
 * {@code out_order} 表上没有运单列，绑定关系的载体就是装运单。
 *
 * <p>取号、面单打印、轨迹回调、作废后重派等属 M8；本端口只暴露出库真正需要的两件事：
 * 建单联动生成装运单、取消时作废装运单。
 */
public interface ShipmentPort {

    /**
     * 生成装运单（待取号）。
     *
     * @param request 建单请求
     * @return 装运单引用
     */
    ShipmentRef create(CreateShipmentRequest request);

    /**
     * 取消/作废装运单（面单已打印的必须作废，{@code 01} §2.6）。
     *
     * @param doNo       出库单号
     * @param reason     作废原因
     * @param operatorId 操作人
     */
    void cancelVoid(String doNo, String reason, Long operatorId);

    /**
     * 查某出库单的装运单（打包时做面单匹配校验）。
     *
     * @param doNo 出库单号
     * @return 装运单引用；不存在返回 {@code null}
     */
    ShipmentRef findByDo(String doNo);

    /**
     * 绑定/校验运单号（已有则必须一致，没有则绑定；不一致抛 {@code 20004}）。
     *
     * @param doNo       出库单号
     * @param waybillNo  运单号
     * @param operatorId 操作人
     */
    void bindWaybill(String doNo, String waybillNo, Long operatorId);

    /**
     * 建单请求。
     *
     * @param doId        出库单 ID
     * @param doNo        出库单号
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param carrierCode 承运商编码
     * @param carrierName 承运商名称（可空）
     * @param estimatedFee 预估价（可空）
     * @param operatorId  操作人（可空）
     */
    record CreateShipmentRequest(long doId, String doNo, long warehouseId, long merchantId, String carrierCode,
                                 String carrierName, BigDecimal estimatedFee, Long operatorId) {
    }

    /**
     * 装运单引用。
     *
     * @param id     装运单 ID
     * @param shpNo  装运单号
     * @param status 状态
     * @param waybill 承运单号（取号前为 {@code null}）
     */
    record ShipmentRef(Long id, String shpNo, String status, String waybill) {
    }
}
