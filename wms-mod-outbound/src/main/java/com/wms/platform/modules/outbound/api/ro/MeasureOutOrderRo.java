package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称重/量方录入（{@code 03} API-OUT-06，状态机第 6 行）。
 *
 * <p>偏差校验在服务端做（{@code WeightCheck}，阈值 20%，严格大于才拦停，{@code 20003}）。
 * 实称重量必填：没有实称数据就无法与申报重量比对，也就失去了"复核"的意义。
 *
 * @param actualWeight 实称重量 kg（正数）
 * @param actualVolume 实量体积 m³（可空）
 * @param packageCount 件数（可空）
 */
public record MeasureOutOrderRo(@NotNull(message = "实称重量不能为空")
                                @DecimalMin(value = "0.0001", message = "实称重量必须为正数")
                                BigDecimal actualWeight,
                                BigDecimal actualVolume,
                                Integer packageCount) {
}
