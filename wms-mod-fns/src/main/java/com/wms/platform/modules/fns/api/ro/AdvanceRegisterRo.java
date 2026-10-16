package com.wms.platform.modules.fns.api.ro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 登记垫付入参（03 API-FNS-06）。
 *
 * @param waybillNo     运单号（幂等键：同一运单只垫付一次）
 * @param merchantId    商家
 * @param freezeId      关联冻结单（可空）
 * @param amount        垫付金额
 * @param billingPeriod 账期（yyyyMM）
 */
public record AdvanceRegisterRo(

        /** 运单号：必填。 */
        @NotBlank(message = "运单号不能为空")
        @Size(max = 64, message = "运单号不得超过 64 字符")
        String waybillNo,

        /** 商家：必填。 */
        @NotNull(message = "商家不能为空")
        Long merchantId,

        /** 冻结单（可空）。 */
        Long freezeId,

        /** 垫付金额：必填且为正。 */
        @NotNull(message = "垫付金额不能为空")
        @DecimalMin(value = "0.0001", message = "垫付金额必须为正")
        BigDecimal amount,

        /** 账期：必填，yyyyMM。 */
        @NotBlank(message = "账期不能为空")
        @Size(max = 8, message = "账期格式不正确")
        String billingPeriod) {
}
