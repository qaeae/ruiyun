package com.wms.platform.modules.acct.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 费用冻结规则（{@code 04-资金合规落地方案} §4.3）。
 *
 * <p>三条口径写死在领域层（不在 Service 里散落判断）：
 * <ol>
 *   <li><b>冻结额 = 预估价 × (1 + 缓冲系数)</b>，四舍五入到 4 位小数（与 {@code DECIMAL(18,4)} 对齐）。
 *       缓冲系数默认 10%（可按承运商配置）；</li>
 *   <li><b>前置校验用「可用 = 账面余额 − 冻结额」</b>：即 {@code ac_merchant_account.balance} 是
 *       含冻结的账面余额，冻结动作**只增加 freeze_amount、不动 balance**（{@code ac_ledger.change_amount = 0}）。
 *       这一点必须对齐，否则会得出"冻结时要把钱从 balance 扣掉"的错误实现——那会让日终勾稽
 *       （{@code balance + freeze_amount + deposit_amount} 对齐存管账户）两边都错；</li>
 *   <li>金额必须为正：0 元冻结毫无意义，却会在 {@code ac_fee_freeze} 留下噪声行。</li>
 * </ol>
 */
public final class FeeFreezeRule {

    /** 金额与数量口径的小数位（与 DDL 的 {@code DECIMAL(18,4)} 一致）。 */
    private static final int SCALE = 4;

    /** 单号前缀（{@code 01} 约定：冻结单号 {@code FRZ + yyyyMMdd + 序列}）。 */
    private static final String FREEZE_NO_PREFIX = "FRZ";

    /** 流水号前缀（{@code 01} 约定：资金流水号 {@code LG + yyyyMMdd + 序列}）。 */
    private static final String LEDGER_NO_PREFIX = "LG";

    private FeeFreezeRule() {
    }

    /**
     * 计算冻结额。
     *
     * @param estimatedAmount 预估价（元）
     * @param bufferRate      缓冲系数（如 {@code 0.1000}）
     * @return 冻结额（4 位小数）
     */
    public static BigDecimal frozenAmount(BigDecimal estimatedAmount, BigDecimal bufferRate) {
        // 1. 预估价必须为正（否则宁可让调用方显式给 0 价的业务处理，也不能静默冻结 0 元）
        if (estimatedAmount == null || estimatedAmount.signum() <= 0) {
            throw new com.wms.platform.common.api.BizException(
                    com.wms.platform.common.api.ErrorCode.PARAM_ERROR, "冻结预估价必须为正数");
        }
        // 2. 缓冲系数不可为负（为负等于倒贴，属配置错误）
        BigDecimal rate = bufferRate == null ? BigDecimal.ZERO : bufferRate;
        if (rate.signum() < 0) {
            throw new com.wms.platform.common.api.BizException(
                    com.wms.platform.common.api.ErrorCode.PARAM_ERROR, "缓冲系数不可为负：" + rate);
        }
        // 3. 冻结额 = 预估价 × (1 + 缓冲)，四舍五入到 4 位
        return estimatedAmount.multiply(BigDecimal.ONE.add(rate)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 生成冻结单号：{@code FRZ + yyyyMMdd + 雪花ID 的 36 进制}。
     *
     * <p>与 {@code ApprovalRule.approvalNo} 同口径：**不用"取模 1 万的流水号"**——那种做法在一天内
     * 累计约 120 单时碰撞概率就接近 50%，表现是偶发唯一键冲突（业务直接失败）。36 进制编码是双射，
     * 全局唯一且随雪花 ID 递增。
     *
     * @param id 雪花 ID
     * @return 冻结单号
     */
    public static String freezeNo(long id) {
        return FREEZE_NO_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + Long.toString(id, 36).toUpperCase(Locale.ROOT);
    }

    /**
     * 生成资金流水号：{@code LG + yyyyMMdd + 雪花ID 的 36 进制}（口径同 {@link #freezeNo(long)}）。
     *
     * @param id 雪花 ID
     * @return 流水号
     */
    public static String ledgerNo(long id) {
        return LEDGER_NO_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + Long.toString(id, 36).toUpperCase(Locale.ROOT);
    }
}
