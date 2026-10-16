package com.wms.platform.modules.pda.api.ro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量补传入参（03 §3.12 API-PDA-01 {@code POST /pda/sync/batch}）。
 *
 * <p>单批上限 **200 条**（03 明文）：上限既保护服务端（200 条 × 每条的幂等与流水写库），
 * 也保护 PDA——离线 2 小时后回来补传，若一次全量上传，弱网下失败重传的代价会指数级放大。
 *
 * @param deviceCode 设备编号（必须与请求头 {@code X-Device-Code} 一致）
 * @param operatorId 操作人
 * @param batchSeq   批次序号（PDA 本地自增，用于对账"哪一批补传过"）
 * @param items      补传明细（1~200 条）
 */
public record PdaSyncBatchRo(

        /** 设备编号：必填。 */
        @NotBlank(message = "设备编号不能为空")
        String deviceCode,

        /** 操作人：可空（设备绑定的操作人）。 */
        Long operatorId,

        /** 批次序号：必填。 */
        @NotNull(message = "批次序号不能为空")
        Integer batchSeq,

        /** 补传明细：1~200 条。 */
        @NotEmpty(message = "补传明细不能为空")
        @Size(max = 200, message = "单批补传不得超过 200 条")
        @Valid
        List<PdaSyncItemRo> items) {
}
