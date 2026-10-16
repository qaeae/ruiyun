package com.wms.platform.common.cache.impl;

import com.wms.platform.common.cache.CacheService;

import java.time.Duration;

/**
 * 空缓存实现（默认）：所有读都回源、写被忽略。
 *
 * <p><b>为什么默认是它</b>：与资金通道 {@code MOCK/REAL}、消息 {@code NONE/RABBIT}、
 * 调度 {@code enabled} 同一种做法——**默认不依赖外部组件**，CI 与新人环境不被 Redis 卡住。
 * 打开缓存只改配置（{@code wms.cache.enabled=true}）。
 *
 * <p>行为上"什么都不做"而不是"假装缓存了"：{@link #available()} 返回 false，
 * 调用方可据此在日志/健康检查里体现真实状态，不会出现"以为开了缓存其实没有"。
 */
public class NoopCacheService implements CacheService {

    @Override
    public <T> T get(String key, Class<T> clazz) {
        return null;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        // 未启用缓存：忽略写入（读侧永远回源，因此不存在"陈旧数据"风险）
    }

    @Override
    public void evict(String key) {
        // 未启用缓存：无需失效
    }

    @Override
    public void evictByPrefix(String keyPrefix) {
        // 未启用缓存：无需失效
    }

    @Override
    public boolean available() {
        return false;
    }
}
