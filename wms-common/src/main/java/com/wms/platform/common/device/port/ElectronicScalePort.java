package com.wms.platform.common.device.port;

import com.wms.platform.common.device.dto.WeightReadingDto;

/**
 * 电子秤端口（称重读数，设备无关）。
 *
 * <p><b>为什么端口要有"等稳定"能力</b>：电子秤在放件瞬间跳数。若直接把跳数当作实重，
 * 会把正常包裹判成"偏差超阈值"而拦停（03 的 {@code 20003}），
 * 现场表现为"系统老是拦我"——比不校验更糟。因此端口把"等稳定"做成默认能力。
 */
public interface ElectronicScalePort {

    /** 连续读数的最大尝试次数（默认）。 */
    int DEFAULT_MAX_ATTEMPTS = 10;

    /** 两次读数之间的等待毫秒数（默认）。 */
    long DEFAULT_INTERVAL_MILLIS = 100L;

    /**
     * 读一次当前重量（不做稳定判断）。
     *
     * @param scaleCode 电子秤编码
     * @return 称重读数（含 {@code stable} 标记）
     */
    WeightReadingDto read(String scaleCode);

    /**
     * 读到稳定值为止（带次数上限，避免设备故障时无限等待）。
     *
     * @param scaleCode 电子秤编码
     * @return 稳定读数；达到次数上限仍未稳定时，返回最后一次读数（{@code stable=false}），由调用方决定是否拦停
     */
    default WeightReadingDto readStable(String scaleCode) {
        return readStable(scaleCode, DEFAULT_MAX_ATTEMPTS, DEFAULT_INTERVAL_MILLIS);
    }

    /**
     * 读到稳定值为止。
     *
     * @param scaleCode     电子秤编码
     * @param maxAttempts   最大尝试次数
     * @param intervalMillis 重试间隔（毫秒）
     * @return 稳定读数；未稳定则返回最后一次读数
     */
    default WeightReadingDto readStable(String scaleCode, int maxAttempts, long intervalMillis) {
        WeightReadingDto reading = null;
        // 1. 循环读数，直到稳定或达到次数上限
        for (int attempt = 0; attempt < Math.max(1, maxAttempts); attempt++) {
            reading = read(scaleCode);
            if (reading.stable()) {
                return reading;
            }
            // 2. 未稳定则等待下一次读数（现场设备跳数通常几十到几百毫秒内收敛）
            try {
                Thread.sleep(Math.max(0L, intervalMillis));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return reading;
            }
        }
        // 3. 兜底返回最后一次读数（stable=false 由调用方判定是否拦停）
        return reading;
    }
}
