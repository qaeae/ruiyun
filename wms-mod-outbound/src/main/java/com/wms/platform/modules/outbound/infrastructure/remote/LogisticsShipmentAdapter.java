package com.wms.platform.modules.outbound.infrastructure.remote;

import com.wms.platform.modules.logistics.app.ShipmentService;
import com.wms.platform.modules.outbound.app.remote.ShipmentPort;

/**
 * 装运单端口的本地适配实现（出库 → logistics，同进程直调）。
 *
 * <p>与 {@code AcctFeeFreezeAdapter} 同理：物流独立成服务后只换本类实现
 * （{@code 01} §2.1 状态机第 1 行的"生成 lg_shipment"是跨域副作用，走端口才能随时搬走）。
 */
public class LogisticsShipmentAdapter implements ShipmentPort {

    /** 物流域装运单服务。 */
    private final ShipmentService shipmentService;

    public LogisticsShipmentAdapter(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @Override
    public ShipmentRef create(CreateShipmentRequest request) {
        ShipmentService.ShipmentView view = shipmentService.createForOutbound(new ShipmentService
                .CreateShipmentCommand(request.doId(), request.doNo(), request.warehouseId(), request.merchantId(),
                request.carrierCode(), request.carrierName(), request.estimatedFee(), request.operatorId()));
        return new ShipmentRef(view.id(), view.shpNo(), view.status(), view.waybill());
    }

    @Override
    public void cancelVoid(String doNo, String reason, Long operatorId) {
        shipmentService.cancelVoid(doNo, reason, operatorId);
    }

    @Override
    public ShipmentRef findByDo(String doNo) {
        ShipmentService.ShipmentView view = shipmentService.findByDoNo(doNo);
        return view == null ? null : new ShipmentRef(view.id(), view.shpNo(), view.status(), view.waybill());
    }

    @Override
    public void bindWaybill(String doNo, String waybillNo, Long operatorId) {
        shipmentService.bindWaybill(doNo, waybillNo, operatorId);
    }
}
