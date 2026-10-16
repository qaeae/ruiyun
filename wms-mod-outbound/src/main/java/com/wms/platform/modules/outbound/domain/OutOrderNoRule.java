package com.wms.platform.modules.outbound.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 出库单单号规则（{@code 01} §1.1：{@code DO + yyyyMMdd + 序列}）。
 *
 * <p>与 {@code ApprovalRule.approvalNo}、{@code FeeFreezeRule.freezeNo}、{@code ShipmentNoRule.shpNo}
 * 同口径：**36 进制编码整颗雪花 ID**，而不是"取模 1 万的流水号"。后者在一天累计约 120 单时碰撞概率
 * 就接近 50%，表现为偶发 {@code uk_do_no} 冲突（建单失败）且无法稳定复现。
 */
public final class OutOrderNoRule {

    /** 前缀（{@code 01} 约定）。 */
    private static final String PREFIX = "DO";

    /** 部分发货余量单的后缀分隔符（避开 {@code uk_source_order} 的防重复下单约束）。 */
    public static final String PARTIAL_SUFFIX = "#P";

    private OutOrderNoRule() {
    }

    /**
     * 生成出库单号。
     *
     * @param id 雪花 ID
     * @return 出库单号（{@code out_order.do_no VARCHAR(32)} 之内）
     */
    public static String doNo(long id) {
        return PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + Long.toString(id, 36).toUpperCase(Locale.ROOT);
    }

    /**
     * 生成部分发货余量单的上游单号。
     *
     * <p><b>为什么必须换个号</b>：{@code out_order} 有 {@code uk_source_order(tenant_id, source_type,
     * source_order_no)}，余量单若沿用原单号会直接撞唯一键——而唯一键的存在正是"防重复下单"，
     * 不能为了拆单把它绕开，所以改为在原单号后加有序后缀（同一原单拆多次也不会互相撞）。
     *
     * @param originSourceOrderNo 原上游单号（可空）
     * @param originDoNo          原出库单号（原上游单号为空时用它兜底）
     * @param seq                 拆单序号（从 1 开始）
     * @return 余量单的上游单号
     */
    public static String partialSourceOrderNo(String originSourceOrderNo, String originDoNo, int seq) {
        String base = originSourceOrderNo == null || originSourceOrderNo.isBlank() ? originDoNo : originSourceOrderNo;
        return base + PARTIAL_SUFFIX + seq;
    }
}
