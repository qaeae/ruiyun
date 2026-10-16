package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 异常拦截（{@code 03} API-OUT-11，状态机第 12 行）。
 *
 * <p>副作用最重：**释放冻结、回滚预占、开异常单**。因此原因必填——一个没有原因的拦截
 * 会让商家在申诉时完全无法核对，现场也只能靠回忆判断。
 *
 * <p>说明：{@code 01} §2.1 第 12 行提到"开异常单"，但 28 张基线表里没有出库异常单表。
 * 本轮以 {@code pf_biz_status_log}（事件 {@code HOLD} + 原因）+ 出库单 {@code remark} 承载，
 * 并登记为待确认项（见 {@code docs/standards/STANDARDS-MAPPING.md}）。
 *
 * @param reason 拦截原因（必填，写入状态流水与出库单备注）
 */
public record HoldOutOrderRo(@NotBlank(message = "拦截原因不能为空")
                             @Size(max = 255, message = "拦截原因不得超过 255 字符") String reason) {
}
