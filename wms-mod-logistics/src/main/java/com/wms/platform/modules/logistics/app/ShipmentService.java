package com.wms.platform.modules.logistics.app;

import java.math.BigDecimal;

/**
 * 装运单应用服务（本轮的调用方是出库域：建单联动生成装运单，{@code 03} API-LOG-01）。
 *
 * <p><b>本轮范围</b>：只做"生成装运单（待取号）"。取号（API-LOG-02）、面单打印登记
 * （API-LOG-03，已由 {@link LabelPrintService} 实现）、轨迹回传（API-LOG-05）、作废（API-LOG-08）、
 * 交接承运商（API-LOG-04）属 M8 轮次。
 *
 * <p><b>为什么先落"待取号"就够</b>：出库的打包环节需要"面单与 DO 绑定"这一事实存在，
 * 而绑定关系的载体就是 {@code lg_shipment}（{@code out_order} 上没有运单列）。
 * 先把装运单建出来，取号与打印就能在 M8 里各自独立落地，不必回头改出库链路。
 */
public interface ShipmentService {

    /**
     * 出库建单联动生成装运单（幂等：同一 DO + 承运商只生成一张）。
     *
     * @param command 建单命令
     * @return 装运单视图
     */
    ShipmentView createForOutbound(CreateShipmentCommand command);

    /**
     * 作废装运单（出库取消/拦截时联动；幂等：已作废直接返回）。
     *
     * <p>为什么现在就要有：{@code 01} §2.6 取消矩阵里"面单作废"是取消的必做动作之一——
     * 漏了它会出现"单已取消但面单仍可用"，现场可能拿废面单发出货，事后对账凭空多出一票。
     * 本轮虽未取号（{@code lg_shipment} 停在"待取号"），但作废语义必须现在就落地，
     * 否则 M8 取号上线后要回头改出库链路。
     *
     * @param doNo       出库单号
     * @param reason     作废原因
     * @param operatorId 操作人
     * @return 本次是否真的作废（{@code false} = 无装运单或已作废）
     * @throws com.wms.platform.common.api.BizException 已揽收/运输中不可作废时抛 {@code 30003}
     */
    boolean cancelVoid(String doNo, String reason, Long operatorId);

    /**
     * 查某出库单的装运单（出库打包时需要它做"面单与单据匹配"校验）。
     *
     * @param doNo 出库单号
     * @return 装运单视图；不存在返回 {@code null}
     */
    ShipmentView findByDoNo(String doNo);

    /**
     * 绑定/校验运单号（打包环节，{@code 03} API-OUT-07 的"面单绑定"）。
     *
     * <p><b>为什么允许"首次绑定"而不是只做校验</b>：{@code 01} §2.1 第 7 行的动作是
     * "打包完成 + 扫面单"，前提是装运单已取号（{@code API-LOG-02}，属 M8）。
     * 取号未落地前，如果这里只做"校验已有运单"，出库链路在打包处必然断掉（永远 20004）。
     * 因此本方法语义是：**已有运单必须一致（不一致 → {@code 20004}），没有则绑定（并顺带把状态推到"已取号"）**。
     * M8 取号上线后这段逻辑无需修改——它自然退化为"校验为主、绑定兜底"。
     *
     * @param doNo       出库单号
     * @param waybillNo  运单号
     * @param operatorId 操作人
     * @throws com.wms.platform.common.api.BizException 运单号与本单不一致、或被其它单占用时抛 {@code 20004}
     */
    void bindWaybill(String doNo, String waybillNo, Long operatorId);

    /**
     * 建单命令。
     *
     * @param doId         出库单 ID
     * @param doNo         出库单号
     * @param warehouseId  仓库 ID
     * @param merchantId   商家 ID
     * @param carrierCode  承运商编码
     * @param carrierName  承运商名称（可空）
     * @param estimatedFee 预估价（可空）
     * @param operatorId   操作人（可空）
     */
    record CreateShipmentCommand(long doId, String doNo, long warehouseId, long merchantId, String carrierCode,
                                 String carrierName, BigDecimal estimatedFee, Long operatorId) {
    }

    /**
     * 装运单视图（跨域只交换必要字段，不把 Po 暴露给调用方）。
     *
     * @param id      装运单 ID
     * @param shpNo   装运单号
     * @param doNo    出库单号
     * @param status  状态（待取号 / 已取号 …）
     * @param waybill 承运单号（取号前为 {@code null}）
     */
    record ShipmentView(Long id, String shpNo, String doNo, String status, String waybill) {
    }
}
