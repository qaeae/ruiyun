package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 出库单分页查询条件（{@code 03} API-OUT-13 / 03 §1「分页」：pageNum ≥ 1、pageSize 1~200）。
 *
 * <p>条件全部可选：仓库/商家/状态/单号模糊。数据权限（租户 + 仓库范围）由服务端强制注入，
 * **不接受调用方传租户**（传了也会被忽略），避免越权查询（03 §1.1）。
 *
 * @param pageNum     页码（≥1）
 * @param pageSize    每页条数（1~200，默认 20）
 * @param warehouseId 仓库（可空）
 * @param merchantId  商家（可空；商家端只应查自己，由数据权限保证）
 * @param status      状态（可空，中文状态名）
 * @param doNo        出库单号（模糊，可空）
 */
public record PageOutOrderRo(@Min(value = 1, message = "页码最小为 1") Integer pageNum,
                             @Min(value = 1, message = "每页条数最小为 1")
                             @Max(value = 200, message = "每页条数最大为 200") Integer pageSize,
                             Long warehouseId,
                             Long merchantId,
                             String status,
                             String doNo) {

    /** 默认页码。 */
    private static final int DEFAULT_PAGE_NUM = 1;

    /** 默认每页条数（与 03 §1 一致）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 生效页码。
     *
     * @return 页码（默认 1）
     */
    public int effectivePageNum() {
        return pageNum == null ? DEFAULT_PAGE_NUM : pageNum;
    }

    /**
     * 生效每页条数。
     *
     * @return 每页条数（默认 20）
     */
    public int effectivePageSize() {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }
}
