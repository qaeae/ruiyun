package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消出库单（{@code 03} API-OUT-10，{@code 01} §2.6 取消/回滚矩阵）。
 *
 * <p><b>回滚动作由"取消时点"决定，不由调用方指定</b>：待接单/拣货中只释放预占与分配；
 * 已复核/已打包要把已拣货回拣货位；待装车已打印面单必须作废、且操作费按实际计。
 * 这些差异写死在 {@code CancelRollbackRule} 里，调用方只提供原因。
 *
 * @param reason 取消原因（写入状态流水，便于事后解释"为什么取消"）
 */
public record CancelOutOrderRo(@NotBlank(message = "取消原因不能为空")
                               @Size(max = 255, message = "取消原因不得超过 255 字符") String reason) {
}
