package com.wms.platform.common.device.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 称重读数（电子秤端口采集结果）。
 *
 * <p>{@code stable} 的含义：电子秤在物体放上/取下瞬间会跳数，**未稳定时取数会导致偏差误判**
 * （比如刚放上就读到 0.3kg，被判"偏差超阈值"直接拦停）。因此端口提供读数 + 稳定标记，
 * 由 {@code readStable} 负责等待稳定。
 *
 * @param scaleCode  电子秤编码（{@code md_device.device_code}）
 * @param weightKg   读数（kg，DECIMAL(12,4) 口径）
 * @param stable     读数是否已稳定
 * @param capturedAt 采集时间
 */
public record WeightReadingDto(String scaleCode, BigDecimal weightKg, boolean stable, LocalDateTime capturedAt) {

    /**
     * 静态工厂（B4）。
     *
     * @param scaleCode 电子秤编码
     * @param weightKg  读数（kg）
     * @param stable    是否稳定
     * @return 称重读数
     */
    public static WeightReadingDto of(String scaleCode, BigDecimal weightKg, boolean stable) {
        return new WeightReadingDto(scaleCode, weightKg, stable, LocalDateTime.now());
    }
}
