package com.wms.platform.modules.logistics.infrastructure.po;

import lombok.Getter;
import lombok.Setter;

/**
 * 面单打印所需的运单字段投影（{@code lg_shipment}）。
 *
 * <p>为什么用"投影"而不是完整的 {@code ShipmentPo}：打印登记只需要 6 个字段，
 * 建一个 30 列的完整 Po 会带来"以后谁都能顺手在这里改别的字段"的扩散风险。
 * 命名用 {@code Row} 而非 {@code Po}/{@code Dto}：它不是表映射，也不是传输模型，
 * 只是"查询结果行"，避免与 B3 的后缀语义混淆。
 */
@Getter
@Setter
public class ShipmentPrintRow {

    /** 装运单号。 */
    private String shpNo;

    /** 承运单号（未取号时为空）。 */
    private String waybillNo;

    /** 面单地址。 */
    private String labelUrl;

    /** 已打印张数（累加口径）。 */
    private Integer printCount;

    /** 面单是否已作废。 */
    private Boolean labelVoid;

    /** 运单状态（待取号/已取号/已交接/…）。 */
    private String status;
}
