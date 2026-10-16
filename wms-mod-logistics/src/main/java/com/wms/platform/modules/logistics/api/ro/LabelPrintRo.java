package com.wms.platform.modules.logistics.api.ro;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 面单打印登记入参（03 API-LOG-03）。
 *
 * <p>幂等键 = {@code shpNo + printSeq}（03 §API-LOG-03）：用打印序号而不是"打印次数"，
 * 因为补打时序号递增，天然区分"第一次打印"与"第 N 次补打"，而计数会因重试而漂移。
 *
 * @param shpNo       装运单号
 * @param printSeq    打印序号（首次 = 1，补打 +1）
 * @param printerCode 目标打印机编码（{@code md_device.device_code}）
 * @param copies      打印份数（1~10）
 */
public record LabelPrintRo(

        /** 装运单号：必填。 */
        @NotBlank(message = "装运单号不能为空")
        String shpNo,

        /** 打印序号：必填，≥1。 */
        @NotNull(message = "打印序号不能为空")
        @Min(value = 1, message = "打印序号必须 ≥ 1")
        Integer printSeq,

        /** 打印机编码：必填。 */
        @NotBlank(message = "打印机编码不能为空")
        String printerCode,

        /** 打印份数：1~10。 */
        @Min(value = 1, message = "打印份数必须 ≥ 1")
        @Max(value = 10, message = "打印份数不得大于 10")
        int copies) {
}
