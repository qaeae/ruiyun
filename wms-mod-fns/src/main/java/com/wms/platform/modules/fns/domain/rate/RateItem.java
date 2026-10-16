package com.wms.platform.modules.fns.domain.rate;

import java.math.BigDecimal;

/**
 * 价目表明细（区域 + 重量区间 → 首重/续重价）。
 *
 * <p><b>重量区间是"半开区间" [weightFrom, weightTo)</b>：相邻档共用边界时不会重叠。
 * 若用闭区间 [from, to]，"首重 1kg"与"续重 1-3kg"会在 1kg 处同时命中——
 * 计费结果就会取决于代码里"先匹配到哪个"，这是最容易产生争议的一类缺陷。
 *
 * @param regionCode     目的地区域码
 * @param weightFrom     计费重下界（含）
 * @param weightTo       计费重上界（不含）；{@code null} 表示无上限
 * @param firstWeight    首重（kg）
 * @param firstPrice     首重价（元）
 * @param addUnit        续重单位（kg）
 * @param addPrice       续重单价（元/单位）
 * @param volumeDivisor  体积重系数（体积 cm³ / 系数 = 体积重 kg）
 * @param minCharge      最低消费（元）
 * @param priority       同区间多条时取优先级大者
 */
public record RateItem(String regionCode, BigDecimal weightFrom, BigDecimal weightTo, BigDecimal firstWeight,
                       BigDecimal firstPrice, BigDecimal addUnit, BigDecimal addPrice, BigDecimal volumeDivisor,
                       BigDecimal minCharge, int priority) {

    /**
     * 判断某计费重是否落在本区间（半开）。
     *
     * @param chargeableWeight 计费重
     * @return 命中返回 true
     */
    public boolean covers(BigDecimal chargeableWeight) {
        if (chargeableWeight.compareTo(weightFrom) < 0) {
            return false;
        }
        return weightTo == null || chargeableWeight.compareTo(weightTo) < 0;
    }

    /**
     * 人类可读的区间描述（写进计费轨迹，方便对账解释）。
     *
     * @return 描述
     */
    public String describe() {
        return regionCode + " [" + weightFrom.toPlainString() + ", "
                + (weightTo == null ? "∞" : weightTo.toPlainString()) + ") "
                + "首重" + firstWeight.toPlainString() + "kg/" + firstPrice.toPlainString() + "元"
                + "，续重" + addUnit.toPlainString() + "kg/" + addPrice.toPlainString() + "元"
                + "，最低" + minCharge.toPlainString() + "元";
    }
}
