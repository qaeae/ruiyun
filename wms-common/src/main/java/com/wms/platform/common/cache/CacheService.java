package com.wms.platform.common.cache;

import java.time.Duration;

/**
 * 缓存端口（P3.2）。
 *
 * <p><b>为什么要有这层抽象（B11 纪律）</b>：Redis 的 key 拼接、序列化方式、TTL 策略一旦散落在各业务类里，
 * 就会出现"同一个键两种拼法""有人存 JSON 有人存字符串""忘了加 TTL 导致数据永久驻留"这类问题。
 * 收口到一个端口后，业务代码只表达"我要缓存什么、多久"，实现细节集中在一处。
 * 架构规则也据此拦住了直接依赖 {@code RedisTemplate} 的写法。
 *
 * <p><b>缓存边界（本项目明确口径）</b>：
 * <ul>
 *   <li><b>可以缓存</b>：主数据解析结果（SKU/库位编码 → ID）——读多写少、变更后可由业务显式失效；</li>
 *   <li><b>绝不缓存</b>：库存数量、冻结金额、账户余额等**资金与库存的权威数据**——
 *       它们必须每次读数据库（条件更新 + 唯一键才是权威），缓存会直接破坏正确性
 *       （详见 README「Redis 只做缓存/会话/限流」的口径）。</li>
 * </ul>
 *
 * <p><b>负结果不缓存</b>：查不到就回源。缓存"不存在"看似省一次查询，但主数据刚创建时
 * 会把"不存在"这个错误结论继续用一段时间（表现为"新建的 SKU 扫码说找不到"），
 * 这类问题现场很难理解。宁可多查一次库。
 */
public interface CacheService {

    /**
     * 读缓存。
     *
     * @param key   键（不含命名空间前缀，由实现统一加）
     * @param clazz 值类型
     * @param <T>   类型
     * @return 值；未命中或不可用时返回 {@code null}
     */
    <T> T get(String key, Class<T> clazz);

    /**
     * 写缓存。
     *
     * @param key   键
     * @param value 值
     * @param ttl   存活时间（**必须给**，避免数据永久驻留）
     */
    void put(String key, Object value, Duration ttl);

    /**
     * 删除单个键。
     *
     * @param key 键
     */
    void evict(String key);

    /**
     * 按前缀批量失效（主数据变更后清理整类缓存）。
     *
     * <p>用前缀扫描（SCAN 语义）而不是维护键清单：缓存可以随时丢失、重建，
     * 为它维护一份"键清单"反而引入了一致性负担。
     *
     * @param keyPrefix 键前缀
     */
    void evictByPrefix(String keyPrefix);

    /**
     * 缓存是否可用（不可用时所有读都回源、写被忽略，业务行为退化为"无缓存"）。
     *
     * @return 可用返回 true
     */
    boolean available();
}
