package com.wms.platform.modules.pda.api.ro;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单条补传项（03 §七 请求体 {@code items[]} 的元素）。
 *
 * @param taskId       任务号（服务端下发）
 * @param action       动作：PICK/LOAD/PUTAWAY
 * @param seq          动作内序号（与 taskId+action 一起构成幂等键）
 * @param bizNo        业务单号（DO 号 / ASN 号）
 * @param skuCode      商品条码
 * @param locationCode 库位编码
 * @param qty          数量
 * @param scannedAt    PDA 本地扫码时间（**仅作参考**，客户端时钟可能有偏差）
 */
public record PdaSyncItemRo(

        /** 任务号：必填。 */
        @NotBlank(message = "任务号不能为空")
        String taskId,

        /** 动作：必填，PICK/LOAD/PUTAWAY。 */
        @NotBlank(message = "补传动作不能为空")
        String action,

        /** 序号：必填，≥1。 */
        @NotNull(message = "序号不能为空")
        @Min(value = 1, message = "序号必须 ≥ 1")
        Integer seq,

        /** 业务单号：必填。 */
        @NotBlank(message = "业务单号不能为空")
        String bizNo,

        /** 商品条码：上架/装车可空（装车按单确认）。 */
        String skuCode,

        /** 库位编码：上架/拣货可空（装车无库位）。 */
        String locationCode,

        /** 数量：可空（装车按单确认时无数量）。 */
        BigDecimal qty,

        /** PDA 本地扫码时间：可空。 */
        LocalDateTime scannedAt) {
}
