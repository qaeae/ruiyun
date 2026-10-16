package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 打包 + 面单绑定（{@code 03} API-OUT-07，状态机第 7 行）。
 *
 * <p>面单校验的必要性：打包是"货与单绑定"的最后一道人工关口，扫错面单会把 A 商家/收件人的货
 * 贴上 B 的面单发出去——这类错发的处理成本极高（跨城拦截、理赔）。
 * 因此服务端必须校验 {@code waybillNo} 属于本单的装运单，否则 {@code 20004 面单与单据不匹配}。
 *
 * @param waybillNo    承运单号（取号后由物流域生成；必须属于本 DO 的装运单）
 * @param packageCount 件数（可空，覆盖称重时录入的件数）
 */
public record PackOutOrderRo(@NotBlank(message = "运单号不能为空") @Size(max = 64) String waybillNo,
                             Integer packageCount) {
}
