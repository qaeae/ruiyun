package com.wms.platform.common.device.simulated;

import com.wms.platform.common.device.dto.WeightReadingDto;
import com.wms.platform.common.device.port.ElectronicScalePort;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟电子秤（开发/测试/PoC 用，无需硬件）。
 *
 * <p>关键能力是 {@link #setUnstableReads}：把前 N 次读数标记为**未稳定**，
 * 用来验证 {@code readStable} 真的会等——否则"称重拦停"逻辑的正确性无从测试
 * （真实现场最常见的误拦就是拿了跳数）。
 */
public class SimulatedElectronicScale implements ElectronicScalePort {

    /** 各台秤的当前读数（kg）。 */
    private final Map<String, BigDecimal> weights = new ConcurrentHashMap<>();

    /** 各台秤剩余"未稳定"次数。 */
    private final Map<String, AtomicInteger> unstableRemaining = new ConcurrentHashMap<>();

    /** 默认读数（未设置时）。 */
    private BigDecimal defaultWeight = new BigDecimal("1.0000");

    /**
     * 设置某台秤的读数。
     *
     * @param scaleCode 秤编码
     * @param weightKg  读数（kg）
     */
    public void setWeight(String scaleCode, BigDecimal weightKg) {
        weights.put(scaleCode, weightKg);
    }

    /**
     * 设置"前 N 次读数不稳定"（模拟放件瞬间跳数）。
     *
     * @param scaleCode 秤编码
     * @param times     未稳定次数
     */
    public void setUnstableReads(String scaleCode, int times) {
        unstableRemaining.put(scaleCode, new AtomicInteger(Math.max(0, times)));
    }

    /**
     * 设置默认读数（未单独设置读数的秤使用）。
     *
     * @param weightKg 默认读数（kg）
     */
    public void setDefaultWeight(BigDecimal weightKg) {
        this.defaultWeight = weightKg;
    }

    @Override
    public WeightReadingDto read(String scaleCode) {
        // 1. 取读数（未设置则用默认值）
        BigDecimal weight = weights.getOrDefault(scaleCode, defaultWeight);
        // 2. 判定稳定性：剩余未稳定次数 > 0 表示仍在跳数
        AtomicInteger remaining = unstableRemaining.computeIfAbsent(scaleCode, key -> new AtomicInteger(0));
        boolean stable = remaining.get() <= 0;
        if (!stable) {
            remaining.decrementAndGet();
        }
        // 3. 未稳定时返回"跳数"读数（真实秤在跳数阶段读到的值偏小），便于验证调用方不会误判
        BigDecimal reading = stable ? weight : weight.multiply(new BigDecimal("0.3"));
        return WeightReadingDto.of(scaleCode, reading, stable);
    }

    /** 清空设置（用例之间隔离）。 */
    public void reset() {
        weights.clear();
        unstableRemaining.clear();
        defaultWeight = new BigDecimal("1.0000");
    }
}
