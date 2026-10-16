package com.wms.platform.common.cache.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 缓存实现（{@code wms.cache.enabled=true} 时启用）。
 *
 * <p><b>为什么放在 {@code common.cache} 这一个类里</b>：架构规则 B11 要求
 * "Redis 操作收口到统一组件"——key 前缀、序列化、TTL、扫描删除都只在这里出现一次。
 * 业务代码只依赖 {@link CacheService} 端口。
 *
 * <p><b>序列化用 JSON（而不是 JDK 序列化）</b>：可读性差距很大——出问题时
 * 在 redis-cli 里能直接看到内容，而 JDK 序列化是一串乱码；同时避免"类版本变化导致
 * 反序列化失败"这种只在灰度时暴露的坑（反序列化失败这里按"未命中"处理，回源即可）。
 *
 * <p><b>键前缀</b>：统一加 {@value #GLOBAL_PREFIX}。同一个 Redis 实例上可能有多个项目/环境，
 * 没有全局前缀就会出现"清缓存清掉别人数据"。本项目已经踩过"配置 DataId 撞名读到别人配置"的坑。
 *
 * <p><b>批量失效用 SCAN 而不是 KEYS</b>：{@code KEYS} 会阻塞整个 Redis（O(N) 且单线程执行），
 * 在生产上是禁用命令；{@code SCAN} 增量游标不阻塞。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisCacheService implements CacheService {

    /** 全局键前缀（含项目命名空间，避免与同实例其它应用冲突）。 */
    public static final String GLOBAL_PREFIX = "wms:cache:";

    /** 单次 SCAN 的提示条数。 */
    private static final long SCAN_COUNT = 200;

    /** 值序列化（与业务使用同一份 ObjectMapper 口径，时间字段格式一致）。 */
    private final ObjectMapper objectMapper;

    /** Redis 模板（String 版：值统一由本类做 JSON 编解码）。 */
    private final StringRedisTemplate redisTemplate;

    @Override
    public <T> T get(String key, Class<T> clazz) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(fullKey(key));
        } catch (Exception ex) {
            // 缓存故障不能影响业务：读失败一律当作"未命中"，回源查库
            log.warn("[缓存] 读取失败，按未命中处理 key={} 原因={}", key, ex.getMessage());
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, clazz);
        } catch (Exception ex) {
            // 反序列化失败（如类结构变了）：删除脏数据并回源，避免每次都失败
            log.warn("[缓存] 反序列化失败，删除该键 key={} 原因={}", key, ex.getMessage());
            evict(key);
            return null;
        }
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        if (value == null) {
            // 负结果不缓存（见 CacheService 的口径说明）：这里直接忽略，防止调用方误传 null
            return;
        }
        try {
            redisTemplate.opsForValue().set(fullKey(key), objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ex) {
            // 写失败只记日志：缓存是加速手段，不能因为缓存故障让业务失败
            log.warn("[缓存] 写入失败（不影响业务） key={} 原因={}", key, ex.getMessage());
        }
    }

    @Override
    public void evict(String key) {
        try {
            redisTemplate.delete(fullKey(key));
        } catch (Exception ex) {
            log.warn("[缓存] 删除失败 key={} 原因={}", key, ex.getMessage());
        }
    }

    @Override
    public void evictByPrefix(String keyPrefix) {
        String pattern = GLOBAL_PREFIX + keyPrefix + "*";
        try {
            List<String> keys = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("[缓存] 批量失效 prefix={} 删除 {} 个键", keyPrefix, keys.size());
            }
        } catch (Exception ex) {
            log.warn("[缓存] 批量失效失败 prefix={} 原因={}", keyPrefix, ex.getMessage());
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    /**
     * 拼完整键。
     *
     * @param key 业务键
     * @return 带全局前缀的键
     */
    private String fullKey(String key) {
        return GLOBAL_PREFIX + key;
    }
}
