package com.wms.platform.modules.fns.api.ro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 生成对账单入参（03 API-FNS-01）。
 *
 * <p><b>数据来源</b>：本入参携带**承运商侧对账文件**的内容；平台侧金额由服务端从 {@code fn_fee_order}
 * 取（{@code fee_type=LOGISTICS}）。这样两边数据来源独立，对账才有意义。
 *
 * @param carrierCode 承运商编码
 * @param period      账期（yyyyMM）
 * @param items       承运商对账明细
 */
public record ReconcileCreateRo(

        /** 承运商编码：必填。 */
        @NotBlank(message = "承运商编码不能为空")
        @Size(max = 32, message = "承运商编码不得超过 32 字符")
        String carrierCode,

        /** 账期：必填，yyyyMM（如 202610）。 */
        @NotBlank(message = "账期不能为空")
        @Pattern(regexp = "[0-9]{6}", message = "账期格式必须为 yyyyMM（如 202610）")
        String period,

        /** 承运商对账明细：至少一条。 */
        @NotEmpty(message = "对账明细不能为空")
        @Valid
        List<Item> items) {

    /**
     * 单条对账明细。
     *
     * @param waybillNo  运单号
     * @param merchantId 商家（可空：平台侧会按费用单补齐）
     * @param carrierFee 承运商计费金额
     */
    public record Item(

            /** 运单号：必填（对账比对的主键）。 */
            @NotBlank(message = "运单号不能为空")
            @Size(max = 64, message = "运单号不得超过 64 字符")
            String waybillNo,

            /** 商家（可空）。 */
            Long merchantId,

            /** 承运商计费金额：必填且非负。 */
            @NotNull(message = "承运商金额不能为空")
            @DecimalMin(value = "0", message = "承运商金额不能为负")
            BigDecimal carrierFee) {
    }
}
