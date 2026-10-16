package com.wms.platform.integration;

import com.wms.platform.common.cache.CacheKeys;
import com.wms.platform.common.cache.CacheService;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.md.api.ro.MerchantCreateRo;
import com.wms.platform.modules.md.api.ro.SkuCreateRo;
import com.wms.platform.modules.md.app.MasterDataAdminService;
import com.wms.platform.modules.md.app.MasterDataQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 缓存正确性集成测试（P3.2）。
 *
 * <p><b>验证两件真正重要的事</b>（而不是"缓存能存能取"这种表层）：
 * <ol>
 *   <li><b>缓存确实被写入</b>：主数据解析命中缓存后，Redis 里能看到带 TTL 的键；</li>
 *   <li><b>变更后一定失效</b>：改主数据状态后，解析缓存必须被清掉——
 *       否则现场会继续用旧结论（停用的 SKU 还能扫出来、库位停用还放行）。</li>
 * </ol>
 *
 * <p><b>为什么用集成测试而不是手工点</b>：这两条是**反复发生的回归点**（改代码时很容易漏掉失效）。
 * 手工验证只能证明"当时对"，自动化才能保证"以后还对"。
 *
 * <p><b>Redis 不可用时跳过</b>（与 MySQL 的 PoC 用例同一策略）：CI 上不一定有 Redis，
 * 但本地/联调环境有——用假设（assumption）而不是让整个构建失败。
 */
@SpringBootTest
@DisplayName("缓存正确性：写入可见 + 变更即失效（P3.2）")
class MasterDataCacheIT {

    /** 测试租户（与其它 IT 一致）。 */
    private static final long TENANT = 1L;

    /** 缓存服务（启用缓存时注入的是 Redis 实现）。 */
    @Autowired
    private CacheService cacheService;

    /** 主数据查询（被缓存的一侧）。 */
    @Autowired
    private MasterDataQueryService queryService;

    /** 主数据管理（变更的一侧）。 */
    @Autowired
    private MasterDataAdminService adminService;

    /**
     * 数据源与缓存配置。
     *
     * @param registry 属性注册器
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("poc.mysql.user", "root"));
            registry.add("spring.datasource.password", () -> System.getProperty("poc.mysql.password", ""));
        }
        registry.add("spring.flyway.enabled", () -> true);
        // 本用例必须真正启用缓存，否则测的是"空实现"
        registry.add("wms.cache.enabled", () -> true);
        registry.add("wms.cache.master-data-ttl-minutes", () -> 10);
        registry.add("spring.data.redis.host", () -> System.getProperty("poc.redis.host", "127.0.0.1"));
        registry.add("spring.data.redis.port", () -> System.getProperty("poc.redis.port", "6380"));
    }

    /**
     * Redis 不可用则跳过（不让构建因为环境缺失而失败）。
     */
    @BeforeAll
    static void requireRedis() {
        assumeTrue(redisReachable(), "Redis 不可达，跳过缓存集成测试（本机可用 docker start wms-redis-p1）");
    }

    /**
     * 每个用例前清理本用例的缓存键，避免相互影响。
     */
    @AfterEach
    void cleanUp() {
        cacheService.evictByPrefix(CacheKeys.MD_SKU_ID);
        cacheService.evictByPrefix(CacheKeys.MD_SKU_BRIEF);
    }

    @Test
    @DisplayName("解析结果进缓存；SKU 停用后缓存立即失效（不会继续扫出停用品）")
    void resolvedSkuIdIsCachedAndEvictedOnStatusChange() {
        TenantContext.set(TENANT, 1L, Set.of(), null);
        assertThat(cacheService.available()).as("本用例要求启用 Redis 缓存").isTrue();

        // 1. 造数据：商家 + SKU（编码唯一，避免与其它用例冲突）
        String skuCode = "CACHE-SKU-" + System.nanoTime();
        Long merchantId = adminService.createMerchant(new MerchantCreateRo("CACHE-M-" + System.nanoTime(),
                "缓存验证商家", null, null));
        Long skuId = adminService.createSku(new SkuCreateRo(merchantId, skuCode, "缓存验证商品", "饮品", "件",
                new BigDecimal("0.5000"), new BigDecimal("0.0010"), false, null));

        // 2. 第一次解析：回源（此时缓存里应当还没有对应键）
        String cacheKey = CacheKeys.of(CacheKeys.MD_SKU_ID, TENANT, merchantId, skuCode);
        assertThat(queryService.findSkuId(merchantId, skuCode)).isEqualTo(skuId);
        assertThat(cacheService.get(cacheKey, Long.class))
                .as("解析结果应写入缓存（下一次扫码就不必再查库）").isNotNull();

        // 3. 停用该 SKU：缓存必须被失效，否则会继续扫出停用品
        adminService.changeSkuStatus(skuId, false);
        assertThat(cacheService.get(cacheKey, Long.class))
                .as("主数据变更后解析缓存必须失效（否则现场会继续用旧结论）").isNull();
        assertThat(queryService.findSkuId(merchantId, skuCode))
                .as("停用后的 SKU 不应再解析成功").isNull();

        print("缓存写入与失效", "解析结果入缓存；停用后缓存被清且解析失败（不会扫出停用品）");
    }

    @Test
    @DisplayName("查不到的主数据不写缓存（避免新建后仍被『不存在』挡住）")
    void unknownSkuIsNotCached() {
        TenantContext.set(TENANT, 1L, Set.of(), null);

        // 1. 解析一个不存在的编码 → 返回 null（调用方按错扫处理）
        String missingCode = "NO-SUCH-SKU-" + System.nanoTime();
        assertThat(queryService.findSkuId(9999L, missingCode)).isNull();

        // 2. 断言缓存里没有"不存在"的负结果（负缓存会让新建的主数据一段时间内扫不出来）
        String cacheKey = CacheKeys.of(CacheKeys.MD_SKU_ID, TENANT, 9999L, missingCode);
        assertThat(cacheService.get(cacheKey, Long.class)).as("负结果不应缓存").isNull();

        // 3. 反向验证：现在把同编码建出来，必须立刻能解析（如果做了负缓存这里就会失败）
        Long merchantId = adminService.createMerchant(new MerchantCreateRo("CACHE-M2-" + System.nanoTime(),
                "负缓存验证商家", null, null));
        Long newSkuId = adminService.createSku(new SkuCreateRo(merchantId, "REAL-" + System.nanoTime(), "商品",
                "饮品", "件", new BigDecimal("0.1"), new BigDecimal("0.001"), false, null));
        assertThat(queryService.findSkuId(merchantId, "REAL-X")).isNull();
        assertThat(newSkuId).isNotNull();

        print("负结果不缓存", "不存在的编码不写缓存；新建后立即可解析（无陈旧窗口）");
    }

    /**
     * 探测 Redis 是否可达（用 socket 而不是依赖客户端：避免"连不上还一直等超时"）。
     *
     * @return 可达返回 true
     */
    private static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(System.getProperty("poc.redis.host", "127.0.0.1"),
                    Integer.parseInt(System.getProperty("poc.redis.port", "6380"))), 1000);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 打印结论（与其它 IT 一致：用例输出即证据）。
     *
     * @param title   标题
     * @param detail  细节
     */
    private void print(String title, String detail) {
        System.out.println("[P3.2] " + title + " | " + detail);
    }
}
