package com.wms.platform.common.approval;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

/**
 * 需要双人复核的业务类型（`02` M2-05 复核矩阵）。
 *
 * <p><b>为什么用白名单枚举而不是"调用方随便传字符串"</b>：复核矩阵是**制度**，不是参数。
 * 若允许任意 bizType，"哪些操作必须复核"就变成了每个调用方各自的理解；
 * 枚举化之后，漏配会编译不过、传错会启动即报错。
 *
 * <p>矩阵来源：{@code 02} M2-05「退款/调价/库存调整/押金退还/账单冲正/差异放行」，
 * 另加本项目在实现中发现同样高危的两类：{@link #SETTLE_DISPATCH}（结算下发 = 真正动钱）、
 * {@link #ADMIN_GRANT}（授予超管 = 绕过全部权限检查）。
 */
public enum ApprovalBizType {

    /** 库存调整（盘亏/盘盈、手工调整）。 */
    STOCK_ADJUST("库存调整", true, "iv"),

    /** 账单冲正（只红冲不冲正）。 */
    BILL_REVERSE("账单冲正", true, "fns"),

    /** 退款。 */
    REFUND("退款", true, "fns"),

    /** 押金退还/抵扣。 */
    DEPOSIT_REFUND("押金退还", true, "fns"),

    /** 调价（价目表变更）。 */
    PRICE_CHANGE("调价", true, "md"),

    /** 对账差异放行（确认未处理完的差异）。 */
    DIFF_RELEASE("差异放行", true, "fns"),

    /** 结算下发（真正动钱）。 */
    SETTLE_DISPATCH("结算下发", true, "fns"),

    /** 授予/取消超管权限。 */
    ADMIN_GRANT("超管授权", false, "md");

    /**
     * 归属域码（iv/fns/md）——**审批事件该投给哪个域的队列，只在这里定义一次**。
     *
     * <p>为什么放在枚举里而不是散在生产者/消费者各处：路由错了的表现是"消息没人消费"
     * （不报错、只是业务不生效）。集中在一处后，"某个业务类型归哪个域"一眼可见、评审可查，
     * 也不会出现生产者按 A 域的键发、消费者按 B 域的队列收。
     */
    private final String domain;

    /** 中文名称。 */
    private final String label;

    /** 是否涉及金额（涉及金额的必须填 amount，便于复核人一眼看到风险量级）。 */
    private final boolean amountRequired;

    /**
     * 事件路由键（审批通过）。
     *
     * @return 路由键，如 {@code approval.approved.iv}
     */
    public String routingKey() {
        return com.wms.platform.common.messaging.EventTopics.routingKeyOf(domain);
    }

    /**
     * 归属域码。
     *
     * @return iv/fns/md
     */
    public String domainCode() {
        return domain;
    }

    ApprovalBizType(String label, boolean amountRequired, String domain) {
        this.label = label;
        this.amountRequired = amountRequired;
        this.domain = domain;
    }

    /**
     * 中文名称。
     *
     * @return 名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 是否涉及金额。
     *
     * @return 涉及返回 {@code true}
     */
    public String code() {
        return name();
    }

    /**
     * 是否涉及金额。
     *
     * @return 涉及返回 {@code true}
     */
    public boolean isAmountRequired() {
        return amountRequired;
    }

    /**
     * 反查（仅接受枚举名；中文名不做反查——落库与接口都用枚举名，避免两套写法）。
     *
     * @param value 枚举名
     * @return 业务类型
     * @throws BizException 非法时抛 {@code 10001}
     */
    public static ApprovalBizType of(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "复核业务类型不能为空");
        }
        for (ApprovalBizType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                "非法的复核业务类型：" + value + "（不在复核矩阵内；如需新增请先更新 02 M2-05 矩阵与本枚举）");
    }
}
