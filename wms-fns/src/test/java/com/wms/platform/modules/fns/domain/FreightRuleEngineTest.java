package com.wms.platform.modules.fns.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.modules.fns.domain.rate.FreightQuote;
import com.wms.platform.modules.fns.domain.rate.FreightRuleEngine;
import com.wms.platform.modules.fns.domain.rate.ParcelFacts;
import com.wms.platform.modules.fns.domain.rate.RateCard;
import com.wms.platform.modules.fns.domain.rate.RateItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运费规则引擎单测（P3.3）。
 *
 * <p><b>重点在边界</b>：计费争议几乎都发生在边界上——正好等于首重、正好卡在区间上界、
 * 体积重刚好超过实重、恰好等于最低消费。差一点点就是真金白银的差错，
 * 因此每条边界都单独断言，而不是只测"一个正常单子算得对"。
 *
 * <p>另有三条"失败方式"的断言（未覆盖区域/未覆盖重量/无价目表）——它们比正常路径更重要：
 * 静默按 0 元计费会持续漏计费且报表上看不出来。
 */
@DisplayName("运费规则引擎：边界、最低消费、体积重、未覆盖必须报错")
class FreightRuleEngineTest {

    /** 上海区域标准档：[0,3) kg，首重 1kg/8 元，续重 1kg/2 元，体积系数 6000。 */
    private static final RateItem SH_STANDARD = new RateItem("SH", new BigDecimal("0"), new BigDecimal("3"),
            new BigDecimal("1"), new BigDecimal("8"), new BigDecimal("1"), new BigDecimal("2"),
            new BigDecimal("6000"), new BigDecimal("0"), 0);

    /** 上海区域重货档：[3,∞) kg，首重 3kg/12 元，续重 1kg/2.5 元，最低消费 20 元。 */
    private static final RateItem SH_HEAVY = new RateItem("SH", new BigDecimal("3"), null,
            new BigDecimal("3"), new BigDecimal("12"), new BigDecimal("1"), new BigDecimal("2.5"),
            new BigDecimal("6000"), new BigDecimal("20"), 0);

    /**
     * 构造测试价目表。
     *
     * @param bufferRatio 缓冲系数
     * @param items       明细
     * @return 价目表（版本固定为 7，便于断言"命中版本回传"）
     */
    private RateCard card(String bufferRatio, RateItem... items) {
        return new RateCard("FREIGHT-SH", "SF", 7, new BigDecimal(bufferRatio), List.of(items));
    }

    @Test
    @DisplayName("首重内：按首重价；缓冲系数只影响预估价")
    void withinFirstWeight() {
        FreightQuote quote = FreightRuleEngine.quote(card("0.1", SH_STANDARD),
                new ParcelFacts("SH", new BigDecimal("0.8"), null));

        assertThat(quote.freightFee()).isEqualByComparingTo("8.0000");
        assertThat(quote.estimatedFee()).as("8 × 1.1").isEqualByComparingTo("8.8000");
        assertThat(quote.additionalUnits()).isZero();
        assertThat(quote.cardVersion()).as("命中版本必须回传（复算依据）").isEqualTo(7);
    }

    @Test
    @DisplayName("边界：恰好等于首重 → 不计续重（1.0000kg）")
    void exactlyFirstWeight() {
        FreightQuote quote = FreightRuleEngine.quote(card("0", SH_STANDARD),
                new ParcelFacts("SH", new BigDecimal("1.0000"), null));

        assertThat(quote.freightFee()).isEqualByComparingTo("8.0000");
        assertThat(quote.additionalUnits()).isZero();
    }

    @Test
    @DisplayName("边界：刚超首重一点点 → 计 1 档续重（向上取整）")
    void justOverFirstWeight() {
        FreightQuote quote = FreightRuleEngine.quote(card("0", SH_STANDARD),
                new ParcelFacts("SH", new BigDecimal("1.0001"), null));

        assertThat(quote.additionalUnits()).as("向上取整：超过即计一档").isEqualTo(1);
        assertThat(quote.freightFee()).isEqualByComparingTo("10.0000");
    }

    @Test
    @DisplayName("续重向上取整：2.1kg → 2 档（2.1-1=1.1 → ceil=2）")
    void additionalWeightRoundsUp() {
        FreightQuote quote = FreightRuleEngine.quote(card("0", SH_STANDARD),
                new ParcelFacts("SH", new BigDecimal("2.1"), null));

        assertThat(quote.additionalUnits()).isEqualTo(2);
        assertThat(quote.freightFee()).isEqualByComparingTo("12.0000");
    }

    @Test
    @DisplayName("区间半开：3.0000kg 走重货档，不会同时命中两档")
    void weightRangeIsHalfOpen() {
        FreightQuote quote = FreightRuleEngine.quote(card("0", SH_STANDARD, SH_HEAVY),
                new ParcelFacts("SH", new BigDecimal("3.0000"), null));

        assertThat(quote.matchedRule()).as("恰好 3kg 应命中 [3, ∞) 档").contains("[3, ∞)");
        assertThat(quote.freightFee()).isEqualByComparingTo("20.0000");
        assertThat(quote.minChargeApplied()).as("重货档首重 12 元 < 最低消费 20 元").isTrue();
    }

    @Test
    @DisplayName("体积重占优：轻抛货按体积重计费（体积 30000cm³ / 6000 = 5kg）")
    void volumeWeightWins() {
        // 这里用**不含最低消费**的重货档，专门验证体积重与续重公式
        // （若误用带最低消费的档，费用会被 20 元托底，就掩盖了公式本身是否正确——
        //   第一版测试就踩了这个坑：期望 17 实际 20，是**期望写错**而不是引擎错）
        RateItem heavyNoMin = new RateItem("SH", new BigDecimal("3"), null,
                new BigDecimal("3"), new BigDecimal("12"), new BigDecimal("1"), new BigDecimal("2.5"),
                new BigDecimal("6000"), new BigDecimal("0"), 0);
        FreightQuote quote = FreightRuleEngine.quote(card("0", SH_STANDARD, heavyNoMin),
                new ParcelFacts("SH", new BigDecimal("2"), new BigDecimal("30000")));

        assertThat(quote.volumeWeightKg()).isEqualByComparingTo("5");
        assertThat(quote.chargeableWeight()).isEqualByComparingTo("5");
        assertThat(quote.minChargeApplied()).isFalse();
        assertThat(quote.freightFee()).as("12 + (5-3)×2.5 = 17").isEqualByComparingTo("17.0000");
    }

    @Test
    @DisplayName("最低消费：低于起价按起价收，并标记已触发")
    void minChargeApplied() {
        RateItem lowWithMin = new RateItem("SH", new BigDecimal("0"), new BigDecimal("3"),
                new BigDecimal("1"), new BigDecimal("8"), new BigDecimal("1"), new BigDecimal("2"),
                new BigDecimal("6000"), new BigDecimal("20"), 0);
        FreightQuote quote = FreightRuleEngine.quote(card("0", lowWithMin),
                new ParcelFacts("SH", new BigDecimal("0.5"), null));

        assertThat(quote.minChargeApplied()).isTrue();
        assertThat(quote.freightFee()).isEqualByComparingTo("20.0000");
    }

    @Test
    @DisplayName("未覆盖区域 → 40007（绝不静默按 0 计费）")
    void uncoveredRegionIsRejected() {
        assertThatThrownBy(() -> FreightRuleEngine.quote(card("0", SH_STANDARD),
                new ParcelFacts("BJ", new BigDecimal("1"), null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RATE_ITEM_NOT_MATCHED))
                .hasMessageContaining("未覆盖区域");
    }

    @Test
    @DisplayName("未覆盖重量区间 → 40007（不允许多收/少收之间出现静默空档）")
    void uncoveredWeightIsRejected() {
        RateItem tiny = new RateItem("SH", new BigDecimal("0"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("8"), new BigDecimal("1"), new BigDecimal("2"),
                new BigDecimal("6000"), new BigDecimal("0"), 0);
        assertThatThrownBy(() -> FreightRuleEngine.quote(card("0", tiny),
                new ParcelFacts("SH", new BigDecimal("2"), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未覆盖计费重");
    }

    @Test
    @DisplayName("价目表缺失 → 40006（不允许把『没有价目表』当成 0 元）")
    void missingCardIsRejected() {
        assertThatThrownBy(() -> FreightRuleEngine.quote(null,
                new ParcelFacts("SH", new BigDecimal("1"), null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RATE_CARD_UNAVAILABLE));
    }

    @Test
    @DisplayName("可复现：同一输入 + 同一版本必得同一结果")
    void reproducible() {
        RateCard rateCard = card("0.15", SH_STANDARD, SH_HEAVY);
        ParcelFacts facts = new ParcelFacts("SH", new BigDecimal("4.2"), new BigDecimal("12000"));
        FreightQuote first = FreightRuleEngine.quote(rateCard, facts);
        FreightQuote second = FreightRuleEngine.quote(rateCard, facts);

        assertThat(first.freightFee()).isEqualByComparingTo(second.freightFee());
        assertThat(first.estimatedFee()).isEqualByComparingTo(second.estimatedFee());
        assertThat(first.cardVersion()).isEqualTo(second.cardVersion());
    }

    @Test
    @DisplayName("可解释：轨迹能说明每一分钱的来源（对账与申诉用）")
    void explainable() {
        FreightQuote quote = FreightRuleEngine.quote(card("0.1", SH_STANDARD, SH_HEAVY),
                new ParcelFacts("SH", new BigDecimal("5"), null));

        assertThat(quote.trace()).isNotEmpty();
        assertThat(String.join("\n", quote.trace()))
                .as("轨迹需包含版本、计费重、命中规则与预估价")
                .contains("v7").contains("计费重").contains("命中规则").contains("预估价");
        System.out.println("[P3.3] 计费轨迹 | " + String.join(" → ", quote.trace()));
    }
}
