package com.wms.platform.modules.fns.domain.rate;

import java.math.BigDecimal;

/**
 * 计费所需的包裹事实（引擎的输入，全部为纯数据）。
 *
 * @param regionCode      目的地区域码
 * @param actualWeightKg  实际重量（kg）
 * @param volumeCm3       体积（cm³）；传 0 或 null 表示不参与体积重比较
 */
public record ParcelFacts(String regionCode, BigDecimal actualWeightKg, BigDecimal volumeCm3) {

    /**
     * 体积重（kg）= 体积 cm³ / 系数；体积未知时返回 0。
     *
     * @param volumeDivisor 体积重系数
     * @return 体积重
     */
    public BigDecimal volumeWeight(BigDecimal volumeDivisor) {
        if (volumeCm3 == null || volumeCm3.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return volumeCm3.divide(volumeDivisor, 6, java.math.RoundingMode.HALF_UP);
    }
}
