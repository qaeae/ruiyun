package com.wms.platform.modules.md.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 箱规单位换算规则（件 / 箱 / 托）。
 *
 * <p><b>为什么换算要抽成领域规则而不是在 Service 里乘一下</b>：量纲错误是"数错了都不知道错在哪"的
 * 典型场景——上架 2 托若被当成 2 件记账，账面与实际差几十倍，且**当天不会有人发现**
 * （盘点才对得上）。所以换算必须：
 * <ol>
 *   <li>有唯一口径（{@code 1 unit_from = factor unit_to}）；</li>
 *   <li>非法换算（自换算、factor ≤ 0、无路径）**当场拒绝**，不返回"看起来合理"的值；</li>
 *   <li>支持**两跳推导**（如 托→箱 + 箱→件 ⇒ 托→件）：只配了相邻两级的仓很常见，
 *       要求必须直配 托→件 会让现场为了"系统能算"而乱配系数。</li>
 * </ol>
 *
 * <p>换算结果一律按 6 位小数（与 {@code factor} 精度一致）四舍五入；除法用
 * {@link RoundingMode#HALF_UP}，避免 {@code 1/3} 之类的无限小数在累加时漂移。
 */
public final class UnitConversion {

    /** 件。 */
    public static final String UNIT_PIECE = "件";

    /** 箱。 */
    public static final String UNIT_BOX = "箱";

    /** 托。 */
    public static final String UNIT_PALLET = "托";

    /** 允许的单位（工厂/仓库现场真正会用的三个）。 */
    public static final List<String> ALLOWED_UNITS = List.of(UNIT_PIECE, UNIT_BOX, UNIT_PALLET);

    /** 结果精度。 */
    public static final int SCALE = 6;

    private UnitConversion() {
    }

    /**
     * 校验单位是否受支持。
     *
     * @param unit 单位
     * @throws BizException 不支持时抛 {@code 10001}
     */
    public static void assertUnitSupported(String unit) {
        if (unit == null || !ALLOWED_UNITS.contains(unit.trim())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "不支持的单位：" + unit + "（仅支持 " + String.join("/", ALLOWED_UNITS) + "）");
        }
    }

    /**
     * 校验一条换算配置。
     *
     * @param unitFrom 源单位
     * @param unitTo   目标单位
     * @param factor   系数
     * @throws BizException 非法时抛 {@code 10001}
     */
    public static void assertConfigValid(String unitFrom, String unitTo, BigDecimal factor) {
        // 1. 单位合法性
        assertUnitSupported(unitFrom);
        assertUnitSupported(unitTo);
        // 2. 自换算无意义（配了会让"换算链"出现环，推导结果依赖遍历顺序）
        if (unitFrom.trim().equals(unitTo.trim())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "源单位与目标单位不能相同：" + unitFrom);
        }
        // 3. 系数必须为正（0 或负数会让所有下游数量变成错误的量纲）
        if (factor == null || factor.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "换算系数必须大于 0，实际：" + factor);
        }
    }

    /**
     * 单跳换算。
     *
     * @param qty    数量
     * @param factor 系数（{@code 1 unitFrom = factor unitTo}）
     * @return 换算结果
     */
    public static BigDecimal convertDirect(BigDecimal qty, BigDecimal factor) {
        if (qty == null) {
            return BigDecimal.ZERO;
        }
        if (factor == null || factor.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "换算系数非法：" + factor);
        }
        return qty.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 在换算表里查找路径并换算（支持一跳与两跳）。
     *
     * @param qty       数量
     * @param unitFrom  源单位
     * @param unitTo    目标单位
     * @param factors   已配置的换算：key = "from&gt;to"，value = 系数
     * @return 换算结果
     * @throws BizException 未配置换算路径时抛 {@code 10001}（**不返回原值**：静默返回原值会让量纲错得无声无息）
     */
    public static BigDecimal convert(BigDecimal qty, String unitFrom, String unitTo, Map<String, BigDecimal> factors) {
        // 1. 同单位直接返回
        if (unitFrom != null && unitFrom.equals(unitTo)) {
            return qty == null ? BigDecimal.ZERO : qty.setScale(SCALE, RoundingMode.HALF_UP);
        }
        // 2. 一跳
        BigDecimal direct = factors.get(key(unitFrom, unitTo));
        if (direct != null) {
            return convertDirect(qty, direct);
        }
        // 3. 两跳（from → mid → to）
        BigDecimal twoHop = null;
        for (String mid : ALLOWED_UNITS) {
            BigDecimal first = factors.get(key(unitFrom, mid));
            BigDecimal second = factors.get(key(mid, unitTo));
            if (first != null && second != null) {
                twoHop = first.multiply(second);
                break;
            }
        }
        if (twoHop != null) {
            return convertDirect(qty, twoHop);
        }
        // 4. 反向换算（只配了 件→箱，却要 箱→件）：用倒数，但显式说明
        BigDecimal reverse = factors.get(key(unitTo, unitFrom));
        if (reverse != null && reverse.signum() > 0) {
            return qty.divide(reverse, SCALE, RoundingMode.HALF_UP);
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                "未配置换算关系：" + unitFrom + " → " + unitTo + "（请先维护箱规）");
    }

    /**
     * 生成换算表的 key。
     *
     * @param unitFrom 源单位
     * @param unitTo   目标单位
     * @return key
     */
    public static String key(String unitFrom, String unitTo) {
        return unitFrom + ">" + unitTo;
    }

    /**
     * 全仓校验：同一 SKU 的换算链不得出现矛盾（如 箱→件=10 且 托→件=100 但 托→箱=5）。
     *
     * <p>发现矛盾时返回提示清单而不是自动纠正——**系数是谁定的、为什么冲突，只有业务能回答**；
     * 系统擅自从两个值里挑一个，等于替业务做了它没做的决定。
     *
     * @param factors 换算配置
     * @return 矛盾提示（空表示无矛盾）
     */
    public static List<String> findConflicts(Map<String, BigDecimal> factors) {
        List<String> conflicts = new ArrayList<>();
        for (String from : ALLOWED_UNITS) {
            for (String to : ALLOWED_UNITS) {
                if (from.equals(to)) {
                    continue;
                }
                BigDecimal direct = factors.get(key(from, to));
                if (direct == null) {
                    continue;
                }
                for (String mid : ALLOWED_UNITS) {
                    BigDecimal first = factors.get(key(from, mid));
                    BigDecimal second = factors.get(key(mid, to));
                    if (first == null || second == null) {
                        continue;
                    }
                    BigDecimal indirect = first.multiply(second);
                    if (direct.compareTo(indirect) != 0) {
                        conflicts.add(from + "→" + to + " 直配 " + direct.stripTrailingZeros().toPlainString()
                                + "，但经 " + mid + " 推导为 " + indirect.stripTrailingZeros().toPlainString());
                    }
                }
            }
        }
        return conflicts;
    }
}
