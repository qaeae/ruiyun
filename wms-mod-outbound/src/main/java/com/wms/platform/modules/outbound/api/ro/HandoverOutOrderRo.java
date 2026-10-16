package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.constraints.Size;

/**
 * 扫码交接给装卸工（{@code 03} API-OUT-08，状态机第 8 行）。
 *
 * <p>交接是**强制**的（{@code 01} §2.1 第 9 行前置："交接扫码成功（强制）"）：它把
 * "货在分拣手里"与"货在装卸工手里"的责任界面钉住，也是事后追责的唯一凭据。
 * 因此载荷很小——重点是记录交接双方与时间。
 *
 * @param remark 交接说明（可空）
 */
public record HandoverOutOrderRo(@Size(max = 255, message = "交接说明不得超过 255 字符") String remark) {
}
