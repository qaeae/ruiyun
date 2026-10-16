package com.wms.platform.common.nacos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * 应用就绪后汇报 Nacos 接入结果（注册 + 配置）。
 *
 * <p><b>为什么需要它（真实踩坑）</b>：配置拉取发生在 {@code EnvironmentPostProcessor} 阶段，
 * 那时日志系统还没初始化，**打出来的日志会全部丢失**。实测现象是：
 * Nacos 配置确实生效了（令牌有效期跟着 Nacos 的值从 120 变成 300 分钟），
 * 但日志里一条 Nacos 记录都没有 —— 运维无法判断"配置中心到底连上没有"。
 * 因此把结论延迟到 {@link ApplicationReadyEvent} 汇报（此时日志一定可用）。
 *
 * <p>汇报三条信息：**配置**（是否拉到、拉了哪些 DataId、是否降级）、**注册**（服务名与状态）、
 * 以及必要的告警（降级 / 注册失败都要能一眼看到）。
 */
@Slf4j
@RequiredArgsConstructor
public class NacosStartupReporter implements ApplicationListener<ApplicationReadyEvent> {

    /** 环境（读取 NacosConfigLoader 写入的状态标记）。 */
    private final Environment environment;

    /** 注册器（读取注册状态）。 */
    private final NacosServiceRegistrar registrar;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 1. 配置中心结论
        String status = environment.getProperty(NacosConfigLoader.KEY_STATUS, NacosConfigLoader.STATUS_DISABLED);
        String dataIds = environment.getProperty(NacosConfigLoader.KEY_DATA_IDS, "");
        String message = environment.getProperty(NacosConfigLoader.KEY_MESSAGE, "");
        boolean degraded = environment.getProperty(NacosConfigLoader.KEY_DEGRADED, Boolean.class, false);

        // 2. 注册中心结论
        String registerStatus = registrar.status();

        // 3. 汇报：禁用是"正常但需知情"，降级/注册失败是"必须处理的告警"
        if (degraded) {
            // 降级有两种（都按 ERROR 输出）：① 服务端不可达但读到了本地快照（**可能过期**，最危险——看起来一切正常）；
            // ② 拉取失败且没有快照（用的是 jar 内配置）。不报出来，运维就发现不了"跑的是旧配置"。
            log.error("[Nacos] 配置中心**降级运行** status={} {}", status, message);
            log.error("[Nacos] 请立即确认配置版本是否符合预期；若该场景要求强依赖，可设 WMS_NACOS_FAIL_FAST=true 让启动直接失败");
        } else if (NacosConfigLoader.STATUS_LOADED.equals(status)) {
            log.info("[Nacos] 配置中心已生效 dataIds=[{}] {}", dataIds, message);
        } else {
            log.info("[Nacos] 配置中心状态={} {}", status, message);
        }
        log.info("[Nacos] 注册中心状态={}", registerStatus);
        if (NacosConfigLoader.STATUS_DISABLED.equals(status) && !"未启用".equals(registerStatus)
                && !"已关闭注册".equals(registerStatus)) {
            // 只用注册中心、不用配置中心是合法组合，但值得记一笔，避免排查时误判
            log.info("[Nacos] 当前仅使用注册中心（未拉取远端配置）");
        }
    }
}
