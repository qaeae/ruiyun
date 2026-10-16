package com.wms.platform.modules.au.api.ro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 提交复核申请入参（03 API-AUTH-04）。
 *
 * <p>一期只对**已接入执行器的业务类型**开放通用入口（其余类型由各自业务接口发起），
 * 因此这里要求显式传 {@code bizType}。
 *
 * @param bizType     业务类型枚举名
 * @param bizId       业务对象 ID（可空）
 * @param bizNo       业务单号（可空）
 * @param warehouseId 仓库（可空）
 * @param merchantId  商家（可空）
 * @param amount      涉及金额（可空；涉金额类型必填）
 * @param payloadJson 审批内容快照（JSON 字符串，执行时以它为准）
 * @param reason      申请事由
 */
public record ApprovalSubmitRo(

        /** 业务类型：必填。 */
        @NotBlank(message = "复核业务类型不能为空")
        @Size(max = 32, message = "业务类型不得超过 32 字符")
        String bizType,

        /** 业务对象 ID（可空）。 */
        Long bizId,

        /** 业务单号（可空）。 */
        @Size(max = 64, message = "业务单号不得超过 64 字符")
        String bizNo,

        /** 仓库（可空）。 */
        Long warehouseId,

        /** 商家（可空）。 */
        Long merchantId,

        /** 涉及金额（可空）。 */
        BigDecimal amount,

        /** 审批内容快照：必填。 */
        @NotBlank(message = "审批内容快照不能为空（执行时以它为准，不接受实时参数）")
        String payloadJson,

        /** 申请事由：必填。 */
        @NotBlank(message = "申请事由不能为空")
        @Size(max = 255, message = "申请事由不得超过 255 字符")
        String reason) {
}
