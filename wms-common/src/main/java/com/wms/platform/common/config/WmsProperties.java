package com.wms.platform.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目自有配置项（前缀 {@code wms}），对应 {@code application.yml} 的 {@code wms.*}。
 *
 * <p>规范落地（B9）：配置值通过 {@code @ConfigurationProperties} + 构造注入获取，
 * <b>不使用字段 {@code @Value}</b>——字段注入无法在单测中直接 new，也无法保证不可变性。
 *
 * <p>字段一律带中文注释；新增配置项必须同步本类与 {@code application.yml}，
 * 并在注释中写清生产环境取值要求（尤其是密钥类）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "wms")
public class WmsProperties {

    /** 雪花 ID 配置。 */
    private final Snowflake snowflake = new Snowflake();

    /** 安全相关配置。 */
    private final Security security = new Security();

    /** 消息与发件箱配置（P2：事务性发件箱 + RabbitMQ）。 */
    private final Messaging messaging = new Messaging();

    /** XXL-JOB 执行器配置（P2.2）。 */
    private final XxlJob xxlJob = new XxlJob();

    /** 缓存配置（P3.2）。 */
    private final Cache cache = new Cache();

    /** 对象存储配置（P5）。 */
    private final Storage storage = new Storage();

    /** 资金通道配置（04-资金合规落地方案 §九）。 */
    private final Payment payment = new Payment();

    /** 设备接入配置（08-设备网关与本地设备接入）。 */
    private final Device device = new Device();

    /** 出库配置（W6 / 02 M7、03 API-OUT）。 */
    private final Outbound outbound = new Outbound();

    /**
     * 出库参数（{@code 02} M7-03/M7-06、{@code 04} §4.3）。
     *
     * <p>三项都是"现场口径、随仓可调"的东西，因此做成配置而不是写死常量：
     * 截单时间各仓不同、缓冲系数按承运商谈、是否需要先交接再装车取决于作业分区。
     */
    @Getter
    @Setter
    public static class Outbound {

        /**
         * 截单时刻（东八区本地时刻，格式 {@code HH:mm}）：该时刻（含）之前建的单是当日单。
         *
         * <p>默认 18:00（设计文档未给具体值，先按常见单仓作业时段取值并登记为待确认项）。
         */
        private String cutoffTime = "18:00";

        /**
         * 运费冻结缓冲系数（{@code 04} §4.3：冻结额 = 预估价 × (1 + 缓冲)，默认 10%，
         * 可按承运商配置）。冻结宁可略多不可略少——少了会在对账时出现"实费 > 冻结额"，
         * 那时钱已经花出去，只能走垫付。
         */
        private java.math.BigDecimal freezeBufferRate = new java.math.BigDecimal("0.1000");

        /** 装车前是否强制"分拣→装卸扫码交接"（{@code 01} §2.1 第 9 行前置：未交接不可装车，20005）。 */
        private boolean requireHandoverBeforeLoad = true;
    }

    /** 设备接入参数。 */
    @Getter
    @Setter
    public static class Device {

        /**
         * 设备实现模式：
         * <ul>
         *   <li>{@code SIMULATED}（默认）：使用内置模拟设备，**开发/联调/PoC/演练**用，无需硬件；</li>
         *   <li>{@code REAL}：真实设备。**一期服务端不直连硬件**——真实适配器由 W5 客户端
         *       （PDA/仓库 PC）实现，服务端只保留端口契约，因此启动即失败并给出指引，
         *       避免"配了 REAL 却以为在打印"的静默错误。</li>
         * </ul>
         */
        private String mode = "SIMULATED";

        /** 默认打印机编码（模拟设备与未指定打印机的场景使用）。 */
        private String defaultPrinterCode = "PRINTER-A-01";

        /** 默认电子秤编码（模拟设备与未指定秤的场景使用）。 */
        private String defaultScaleCode = "SCALE-A-01";
    }

    /** 雪花 ID 参数：多实例部署时必须保证 (datacenterId, workerId) 组合唯一，否则产生重复 ID。 */
    @Getter
    @Setter
    public static class Snowflake {

        /** 机房编号，取值 [0, 31]。 */
        private long datacenterId;

        /** 机器编号，取值 [0, 31]。 */
        private long workerId;
    }

    /** 安全参数。 */
    /**
     * 消息与发件箱配置（P2）。
     *
     * <p>{@code mode} 是这套设计的**总开关**：
     * <ul>
     *   <li>{@code NONE}（默认）：审批通过后**同事务内直接执行**——强一致，
     *       本地开发/集成测试不需要 broker；</li>
     *   <li>{@code RABBIT}：写事务性发件箱 → 投递 RabbitMQ → 消费方执行——最终一致，
     *       对应"执行方已经是另一个进程"的形态。</li>
     * </ul>
     * 与资金通道的 {@code MOCK/REAL} 同一种做法：**默认走不依赖外部组件的那条路**，
     * 要用真组件时显式打开（避免 CI 与新人环境被中间件卡住）。
     */
    @Getter
    @Setter
    public static class Messaging {

        /** 模式：NONE / RABBIT。 */
        private String mode = "NONE";

        /** 是否启用发件箱投递（RABBIT 模式下为 true；显式关掉可先攒事件后统一投递）。 */
        private boolean publisherEnabled;

        /** 单次扫描最大投递条数（由 outbox 重投任务使用）。 */
        private int relayBatchSize = 200;

        /** 单条消息最大重试次数（超过转"失败"状态，由告警/人工介入）。 */
        private int maxRetry = 8;

        /**
         * 是否为 MQ 模式。
         *
         * @return RABBIT 返回 true
         */
        public boolean isRabbit() {
            return "RABBIT".equalsIgnoreCase(mode);
        }
    }

    /**
     * XXL-JOB 执行器配置（P2.2）。默认关闭：不依赖调度中心也能跑。
     *
     * <p>并存方案：XXL-JOB 负责**触发**，事务与幂等仍由本项目的任务框架保证（详见 XxlJobConfig）。
     */
    @Getter
    @Setter
    public static class XxlJob {

        /** 是否启用执行器。 */
        private boolean enabled;

        /** 调度中心地址（多个用逗号分隔；单机部署填一个即可）。 */
        private String adminAddresses = "http://127.0.0.1:18081/xxl-job-admin";

        /** 通信令牌：必须与调度中心配置一致，否则注册/触发会被拒。 */
        private String accessToken = "";

        /** 执行器名（调度中心里按它分组，需与 xxl_job_group.appname 一致）。 */
        private String appName = "wms-core-executor";

        /** 执行器监听端口；0 = 由系统分配（单机多实例推荐）。 */
        private int port;

        /** 执行日志目录。 */
        private String logPath = "./logs/xxl-job";

        /** 执行日志保留天数。 */
        private int logRetentionDays = 7;
    }

    /**
     * 缓存配置（P3.2）。默认关闭：不依赖 Redis 也能跑。
     *
     * <p><b>口径提醒</b>：缓存只用于主数据解析结果这类读多写少、可由业务显式失效的数据；
     * **库存数量、冻结金额、账户余额等权威数据一律不缓存**（详见 CacheService 的类注释）。
     */
    @Getter
    @Setter
    public static class Cache {

        /** 是否启用 Redis 缓存。 */
        private boolean enabled;

        /** 主数据缓存 TTL（分钟）——给上限是防止永远不失效的脏数据长期存在。 */
        private int masterDataTtlMinutes = 10;
    }

    /**
     * 对象存储配置（P5）。默认 LOCAL（本地磁盘）：不依赖外部组件即可跑通回单上传。
     */
    @Getter
    @Setter
    public static class Storage {

        /** 模式：LOCAL / MINIO。 */
        private String mode = "LOCAL";

        /** 本地模式根目录。 */
        private String localRoot = "./data/storage";

        /** MinIO 服务地址。 */
        private String endpoint = "http://127.0.0.1:9000";

        /** MinIO 访问键。 */
        private String accessKey = "wms";

        /** MinIO 密钥。 */
        private String secretKey = "wms123456";

        /** MinIO 桶名。 */
        private String bucket = "wms-proofs";

        /** 预签名 URL 有效期（分钟）。 */
        private int presignedTtlMinutes = 30;

        /**
         * 是否为 MinIO 模式。
         *
         * @return MINIO 返回 true
         */
        public boolean isMinio() {
            return "MINIO".equalsIgnoreCase(mode);
        }
    }

    @Getter
    @Setter
    public static class Security {

        /**
         * 收件人 PII 加密密钥（Base64 编码的 32 字节）。
         * <b>生产环境必须由 KMS 注入</b>，禁止把真实密钥写进配置文件或提交进仓库。
         */
        private String piiKeyBase64;

        /**
         * 访问令牌签名密钥（HMAC-SHA256）。
         *
         * <p><b>生产环境必须由 KMS/密钥管理注入</b>；若仍是默认值 { dev-only-change-me}，
         * 应用在 { prod} profile 下**启动即失败**（{ StartupSecurityCheck}）——
         * 与资金通道 MOCK 同一条原则：不能让"以为安全其实没配"的形态上线。
         */
        private String tokenSecret = "dev-only-change-me";

        /**
         * 网关注入身份的**内部凭据**（对应请求头 { X-Gateway-Token}）。
         *
         * <p>用途：业务服务据此判断 { X-User-Id}/{ X-Tenant-Id} 这类身份头是网关注入的还是客户端伪造的。
         * 为空表示未启用防伪造校验（此时服务会**剥掉**这些头，身份仍只以自身令牌校验为准）——
         * 本地不开网关直接跑服务时属于这种情况；**生产必须设置**。
         */
        private String gatewayInternalToken = "";

        /**
         * 服务账号令牌（P3.4 服务间调用）：**后台任务**触发跨服务调用时作为身份使用。
         *
         * <p>为什么不能只用"内部令牌 + 身份头"代替：那样身份可信但**权限集为空**，
         * 被调方的 @RequiresPermission 会 403（实测：需要权限 stock:invariant:check）。
         * 服务账号可以只给最小权限，且审计上能分辨"是服务触发的"。
         */
        private String serviceToken = "";

        /**
         * 服务账号用户名（P3.4）：必须是一个真实的 RBAC 用户（默认 svc-ops），
         * 权限来自角色授权——这样服务身份也能"最小权限 + 可审计 + 可撤权"。
         */
        private String serviceAccountUsername = "svc-ops";

        /** 服务令牌有效期（分钟）。默认 30：后台任务用，越短泄露窗口越小，调用方自动续期。 */
        private long serviceTokenTtlMinutes = 30L;

        /** 令牌有效期（分钟）。默认 120 分钟：足够覆盖一次班次作业，又不至于长期有效。 */
        private long tokenTtlMinutes = 120L;
    }

    /** 资金通道参数。 */
    @Getter
    @Setter
    public static class Payment {

        /** 通道配置。 */
        private final Channel channel = new Channel();

        /** 通道开关。 */
        @Getter
        @Setter
        public static class Channel {

            /**
             * 通道模式：{@code MOCK}（账务模拟，开发/联调/演练）或 {@code REAL}（真实持牌机构）。
             * <b>生产环境必须为 {@code REAL}</b>——带模拟通道对商家真实收款属合规红线（04 §九）。
             */
            private String mode = "MOCK";
        }
    }
}
