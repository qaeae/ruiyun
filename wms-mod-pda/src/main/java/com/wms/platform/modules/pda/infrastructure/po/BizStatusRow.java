package com.wms.platform.modules.pda.infrastructure.po;

import lombok.Getter;
import lombok.Setter;

/**
 * 业务单据状态投影（只读）。
 *
 * <p>命名用 {@code Row}：它不是表映射，只是"查询结果行"（与 {@code ShipmentPrintRow} 同一约定）。
 */
@Getter
@Setter
public class BizStatusRow {

    /** 单据主键。 */
    private Long id;

    /** 当前状态。 */
    private String status;

    /** 商家 ID。 */
    private Long merchantId;

    /** 仓库 ID。 */
    private Long warehouseId;
}
