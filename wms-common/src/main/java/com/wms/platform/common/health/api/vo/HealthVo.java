package com.wms.platform.common.health.api.vo;

import lombok.Getter;

/**
 * 健康检查响应（规范条目 B2/B3：响应用 Vo，不用 {@code Map} 返回）。
 */
@Getter
public class HealthVo {

    /** 应用名（spring.application.name）。 */
    private final String application;

    /** 服务状态：固定 UP（进程存活即算健康；依赖健康由探针与监控负责）。 */
    private final String status;

    /** 服务端时间（东八区，格式 yyyy-MM-ddTHH:mm:ss）。 */
    private final String serverTime;

    /**
     * Nacos 注册状态（如「已注册」「未启用」「注册失败：...」）。
     *
     * <p>为什么要暴露：注册失败时应用**故意**继续启动（降级），如果这里不显示，
     * 就会出现"服务在跑但服务发现里没有它"的静默故障。
     */
    private final String nacos;

    private HealthVo(String application, String status, String serverTime, String nacos) {
        this.application = application;
        this.status = status;
        this.serverTime = serverTime;
        this.nacos = nacos;
    }

    /**
     * 静态工厂（规范条目 B4：禁止 new + 连续 setter）。
     *
     * @param application 应用名
     * @param status      服务状态
     * @param serverTime  服务端时间
     * @param nacos       Nacos 注册状态
     * @return 健康检查响应
     */
    public static HealthVo of(String application, String status, String serverTime, String nacos) {
        return new HealthVo(application, status, serverTime, nacos);
    }
}
