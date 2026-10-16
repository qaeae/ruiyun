package com.wms.platform.modules.outbound.domain;

/**
 * 出库单状态迁移事件（{@code 01} §2.1 的"事件"列，同时也是状态流水的 {@code event} 取值）。
 *
 * <p>事件名同时用作 {@code pf_biz_status_log.event} 与幂等键后缀（{@code <doId>+<event>}），
 * 因此**必须集中定义**：字符串散落各处时，只要有一处拼写不同，状态流水就会出现两种写法，
 * 而"按事件查轨迹"和"按幂等键判重"都会静默失效（不报错，只是查不到 / 判不出重复）。
 */
public enum OutOrderEvent {

    /** 建单（商家下单/上游推送）。 */
    CREATE("建单"),

    /** 派发拣货任务。 */
    DISPATCH("派发拣货"),

    /** 拣货完成上报。 */
    PICK_COMPLETE("拣货完成"),

    /** 缺货上报。 */
    SHORTAGE("缺货上报"),

    /** 缺货恢复拣货。 */
    RESUME("恢复拣货"),

    /** 称重/量方录入。 */
    MEASURE("称重量方"),

    /** 打包 + 面单绑定。 */
    PACK("打包绑面单"),

    /** 扫码交接给装卸工。 */
    HANDOVER("交接装卸"),

    /** 装车扫码确认（出库确认，库存出账）。 */
    LOAD("装车出库"),

    /** 取消（按阶段回滚）。 */
    CANCEL("取消"),

    /** 异常拦截。 */
    HOLD("异常拦截"),

    /** 部分发货（余量生成新单，原单转已出库）。 */
    PARTIAL_SHIP("部分发货");

    /** 中文名（写入状态流水的备注与错误消息）。 */
    private final String label;

    OutOrderEvent(String label) {
        this.label = label;
    }

    /**
     * 中文名。
     *
     * @return 中文名
     */
    public String getLabel() {
        return label;
    }
}
