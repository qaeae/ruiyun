package com.wms.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.cache.CacheService;
import com.wms.platform.common.cache.impl.NoopCacheService;
import com.wms.platform.common.cache.impl.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存装配（P3.2）：按 {@code wms.cache.enabled} **互斥**选择实现。
 *
 * <p><b>为什么用"配置值互斥"而不是 {@code @ConditionalOnMissingBean}</b>：
 * 后者只在自动配置类里可靠；在普通 {@code @Configuration} 中 Bean 定义顺序未定义，
 * 本项目已实测踩坑（两个执行策略同时注册导致启动失败，见 README §44.4）。
 *
 * <p><b>默认不启用</b>：与资金通道 {@code MOCK/REAL}、消息 {@code NONE/RABBIT} 同一种做法——
 * 默认不依赖外部组件，CI 与新人环境不会被 Redis 卡住。
 */
@Slf4j
@Configuration
public class CacheConfig {

    /**
     * Redis 缓存（{@code wms.cache.enabled=true}）。
     *
     * @param objectMapper      JSON（与业务同一份口径）
     * @param stringRedisTemplate Redis 模板（值由 {@link RedisCacheService} 统一编解码）
     * @return 缓存服务
     */
    @Bean
    @ConditionalOnProperty(prefix = "wms.cache", name = "enabled", havingValue = "true")
    public CacheService redisCacheService(ObjectMapper objectMapper, StringRedisTemplate stringRedisTemplate) {
        log.info("[缓存] 启用 Redis 缓存（前缀 {}）；注意：库存与资金数据**永不缓存**，仍以数据库为权威",
                RedisCacheService.GLOBAL_PREFIX);
        return new RedisCacheService(objectMapper, stringRedisTemplate);
    }

    /**
     * 空缓存（默认）。
     *
     * @return 缓存服务
     */
    @Bean
    @ConditionalOnProperty(prefix = "wms.cache", name = "enabled", havingValue = "false", matchIfMissing = true)
    public CacheService noopCacheService() {
        log.info("[缓存] 未启用（所有读取回源数据库；如需启用请设 wms.cache.enabled=true）");
        return new NoopCacheService();
    }
}
