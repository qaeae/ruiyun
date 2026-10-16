package com.wms.platform.common.messaging;

/**
 * 消息拓扑常量（交换机 / 队列 / 路由键）。
 *
 * <p>全部集中在共享内核：生产者（审批发事件）与消费者（库存/财务执行）如果在两个地方各写一份字符串，
 * 任何拼写差异都会表现为"消息发出去了但没人消费"——而且**不报错、只是任务不执行**，
 * 是最难查的一类生产事故（与身份头常量同源问题，见 {@code HeaderConst} 的注释）。
 *
 * <p><b>队列按域隔离（P3.1 的根治改动）</b>：早期版本所有服务共享一个队列，
 * 结果是"带 AMQP 的服务都进了竞争组"，`wms-auth` 抢走结算事件却没有对应执行器 →
 * 重试耗尽 → 进死信队列（**审批已通过但业务不生效，且取决于谁先抢到消息**）。
 * 现在改为：
 * <pre>
 *   exchange: wms.approval（topic）
 *     ├─ routingKey approval.approved.iv  → queue wms.approval.iv  （库存域消费）
 *     ├─ routingKey approval.approved.fns → queue wms.approval.fns （财务域消费）
 *     └─ routingKey approval.approved.md  → queue wms.approval.md  （主数据域消费）
 *   死信：wms.dlx → 每个域一个 DLQ
 * </pre>
 * <b>队列归属跟随模块归属</b>：消费者代码写在哪个域模块里，就订阅哪个域的队列，
 * 于是"某个服务该不该消费"不再依赖配置开关，而是由**依赖关系天然决定**——
 * 服务没依赖 iv 模块，就根本不存在库存域的消费者。
 *
 * <p>命名约定：{@code wms.<域>.<事件>}，交换机用 topic 类型（便于按前缀订阅多个事件）。
 */
public final class EventTopics {

    /** 审批事件交换机（topic）。 */
    public static final String APPROVAL_EXCHANGE = "wms.approval";

    /** 审批通过路由键前缀（完整键 = 前缀 + 域码）。 */
    public static final String APPROVAL_APPROVED_PREFIX = "approval.approved.";

    /** 库存域。 */
    public static final String DOMAIN_IV = "iv";

    /** 财务域。 */
    public static final String DOMAIN_FNS = "fns";

    /** 主数据域。 */
    public static final String DOMAIN_MD = "md";

    /** 死信交换机（消费失败重试耗尽后进入，供人工排查——不丢消息）。 */
    public static final String DLX_EXCHANGE = "wms.dlx";

    /** 事件类型：审批通过。 */
    public static final String EVENT_TYPE_APPROVAL_APPROVED = "APPROVAL_APPROVED";

    /**
     * 审批通过事件的队列名（按域）。
     *
     * @param domain 域码（iv/fns/md）
     * @return 队列名
     */
    public static String queueOf(String domain) {
        return "wms.approval." + domain;
    }

    /**
     * 审批通过事件的路由键（按域）。
     *
     * @param domain 域码（iv/fns/md）
     * @return 路由键
     */
    public static String routingKeyOf(String domain) {
        return APPROVAL_APPROVED_PREFIX + domain;
    }

    /**
     * 某域的死信队列名。
     *
     * @param domain 域码
     * @return 死信队列名
     */
    public static String deadLetterQueueOf(String domain) {
        return "wms.approval." + domain + ".dlq";
    }

    /**
     * 所有需要声明队列的域（消费者未部署时队列也已存在：消息会**堆在队列里可见**，
     * 而不是因为"没有绑定"被 broker 直接丢弃——这是"未接入执行方"必须能被发现的前提）。
     *
     * @return 域码数组
     */
    public static String[] allDomains() {
        return new String[] {DOMAIN_IV, DOMAIN_FNS, DOMAIN_MD};
    }

    private EventTopics() {
    }
}
