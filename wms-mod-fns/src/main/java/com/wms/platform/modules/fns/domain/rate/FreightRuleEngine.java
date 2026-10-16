package com.wms.platform.modules.fns.domain.rate;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 运费计费规则引擎（自研 DSL，P3.3）。
 *
 * <p><b>为什么自研而不是引入 Drools</b>：
 * <ul>
 *   <li>本项目的计费规则是**结构化的价目表**（区域 + 重量区间 + 首重/续重 + 体积重 + 最低消费），
 *       不是"大量互相影响的业务条件"——用规则引擎的收益很小，而引入 KIE 运行时、
 *       规则文件版本管理、调试链路（错误栈里全是反射）的成本很高；</li>
 *   <li>财务口径要求**可读、可复现、可单测**：把规则写成纯函数 + 数据表，
 *       任何一笔运费都能用"输入 + 价目表版本"复算出同一个数，且单测直接断言边界；</li>
 *   <li>规则若真复杂到需要引擎（如多承运商博弈、动态定价），届时可整体替换本类，
 *       因为它的输入输出已经是稳定的数据结构（{@link ParcelFacts} → {@link FreightQuote}）。</li>
 * </ul>
 *
 * <p><b>本类的纪律</b>：
 * <ol>
 *   <li><b>纯函数</b>：不查库、不读时钟、不用 ThreadLocal——因此可以被穷举测试；</li>
 *   <li><b>绝不静默返回 0</b>：没有可用价目表 → {@code 40006}；区域/重量没被覆盖 → {@code 40007}。
 *       静默 0 元是最危险的失败方式——持续漏计费且报表上看不出来；</li>
 *   <li><b>可解释</b>：返回构造好的轨迹（{@link FreightQuote#trace()}），
 *       直接用于回答商家/承运商的"为什么是这个价"。</li>
 * </ol>
 *
 * <p><b>金额口径</b>：全程 {@link BigDecimal}，计算精度 6 位、输出 4 位（与库中 DECIMAL(18,4) 一致），
 * 全部 {@code HALF_UP}。**不用 double**——浮点误差在结算场景会变成真实的钱差。
 */
public final class FreightRuleEngine {

    /** 计算中间精度。 */
    private static final int SCALE_CALC = 6;

    /** 金额输出精度（与 fn_rate_item/fn_fee_order 的 DECIMAL(18,4) 一致）。 */
    private static final int SCALE_MONEY = 4;

    /**
     * 试算运费。
     *
     * @param card  价目表（含版本与缓冲系数）；为 {@code null} 抛 {@code 40006}
     * @param facts 包裹事实
     * @return 试算结果（含命中版本与轨迹）
     * @throws BizException 价目表不可用（40006）或规则未覆盖（40007）
     */
    public static FreightQuote quote(RateCard card, ParcelFacts facts) {
        // 1. 价目表必须存在（不允许"没有价目表就按 0 算"）
        if (card == null || card.items() == null || card.items().isEmpty()) {
            throw new BizException(ErrorCode.RATE_CARD_UNAVAILABLE,
                    "没有可用的运费价目表（请先配置并启用价目表，再试算）");
        }
        List<String> trace = new ArrayList<>();

        // 2. 先按区域筛出候选规则；区域没覆盖 → 明确报错（而不是回落成 0 或随便取一条）
        List<RateItem> candidates = card.items().stream()
                .filter(item -> item.regionCode().equals(facts.regionCode()))
                .toList();
        if (candidates.isEmpty()) {
            throw new BizException(ErrorCode.RATE_ITEM_NOT_MATCHED,
                    "价目表 " + card.cardCode() + " v" + card.version() + " 未覆盖区域：" + facts.regionCode());
        }
        trace.add("价目表 " + card.cardCode() + " v" + card.version()
                + "（缓冲系数 " + card.bufferRatio().toPlainString() + "）");
        trace.add("区域 " + facts.regionCode() + " 命中 " + candidates.size() + " 条候选规则");

        // 3. 体积重：取候选规则中的体积重系数（同区域通常一致；不一致时以优先级最高者为准）
        RateItem weightSource = candidates.stream()
                .max(Comparator.comparingInt(RateItem::priority).thenComparing(RateItem::weightFrom))
                .orElseThrow();
        BigDecimal volumeWeight = facts.volumeWeight(weightSource.volumeDivisor());
        BigDecimal actualWeight = facts.actualWeightKg() == null ? BigDecimal.ZERO : facts.actualWeightKg();
        // 计费重 = max(实重, 体积重)：轻抛货按体积计费，这是行业通行口径
        BigDecimal chargeableWeight = actualWeight.max(volumeWeight);
        trace.add("实重 " + actualWeight.toPlainString() + "kg，体积重 " + volumeWeight.toPlainString()
                + "kg（系数 " + weightSource.volumeDivisor().toPlainString() + "）→ 计费重 "
                + chargeableWeight.toPlainString() + "kg");

        // 4. 在候选里找覆盖该计费重的规则（半开区间；多条命中取优先级大者）
        RateItem matched = candidates.stream()
                .filter(item -> item.covers(chargeableWeight))
                .max(Comparator.comparingInt(RateItem::priority))
                .orElseThrow(() -> new BizException(ErrorCode.RATE_ITEM_NOT_MATCHED,
                        "价目表 " + card.cardCode() + " v" + card.version() + " 未覆盖计费重 "
                                + chargeableWeight.toPlainString() + "kg（区域 " + facts.regionCode() + "）"));
        trace.add("命中规则：" + matched.describe());

        // 5. 计费：首重价 + 超出部分按续重单位向上取整 × 续重单价
        BigDecimal fee = matched.firstPrice();
        trace.add("首重 " + matched.firstWeight().toPlainString() + "kg 计价 " + fee.toPlainString() + " 元");
        int additionalUnits = 0;
        BigDecimal over = chargeableWeight.subtract(matched.firstWeight());
        if (over.signum() > 0) {
            // 向上取整：2.1kg 按 3kg 算（行业惯例，也是对承运商成本的保守估计）
            additionalUnits = over.divide(matched.addUnit(), 0, RoundingMode.CEILING).intValueExact();
            BigDecimal addFee = matched.addPrice().multiply(BigDecimal.valueOf(additionalUnits))
                    .setScale(SCALE_CALC, RoundingMode.HALF_UP);
            fee = fee.add(addFee);
            trace.add("超出 " + over.toPlainString() + "kg → 续重 " + additionalUnits + " 档 × "
                    + matched.addPrice().toPlainString() + " 元 = " + addFee.toPlainString() + " 元");
        } else {
            trace.add("未超出首重，不计续重");
        }

        // 6. 最低消费（低于起价按起价收）
        boolean minChargeApplied = fee.compareTo(matched.minCharge()) < 0;
        if (minChargeApplied) {
            trace.add("未达最低消费 " + matched.minCharge().toPlainString() + " 元 → 按最低消费计");
            fee = matched.minCharge();
        }
        fee = fee.setScale(SCALE_MONEY, RoundingMode.HALF_UP);

        // 7. 预估价 = 基础运费 × (1 + 缓冲系数)：冻结/预估要留缓冲，避免实际回传超预估导致余额不足
        BigDecimal estimatedFee = fee.multiply(BigDecimal.ONE.add(card.bufferRatio()))
                .setScale(SCALE_MONEY, RoundingMode.HALF_UP);
        trace.add("基础运费 " + fee.toPlainString() + " 元 → 预估价（含缓冲）"
                + estimatedFee.toPlainString() + " 元");

        return new FreightQuote(fee, estimatedFee, card.cardCode(), card.version(), card.bufferRatio(),
                actualWeight, volumeWeight, chargeableWeight, additionalUnits, minChargeApplied,
                matched.describe(), trace);
    }

    private FreightRuleEngine() {
    }
}
