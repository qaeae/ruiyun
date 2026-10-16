package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.cache.CacheService;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.fns.app.RateCardService;
import com.wms.platform.modules.fns.domain.rate.FreightQuote;
import com.wms.platform.modules.fns.domain.rate.ParcelFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 价目表与运费试算集成测试（P3.3，真实 MySQL）。
 *
 * <p>验证四件在真实库里才会暴露的事：
 * <ol>
 *   <li><b>版本化</b>：调价是"新增版本"，旧版本保留（历史运费可复算）；</li>
 *   <li><b>草稿不可计费、启用才生效</b>：避免"建了价目表就开始收钱"；</li>
 *   <li><b>同时只有一个启用版本</b>：启用新版本会自动停用旧版本；</li>
 *   <li><b>未覆盖必须报错</b>：不是静默 0 元（这条在真实库上验证是因为它跨了缓存与查询）。</li>
 * </ol>
 */
@SpringBootTest(classes = com.wms.platform.fns.FnsApplication.class)
@DisplayName("价目表：版本化、启用生效、未覆盖报错（P3.3）")
class RateCardIT {

    /** 测试租户。 */
    private static final long TENANT = 1L;

    /** 价目表与试算服务。 */
    @Autowired
    private RateCardService rateCardService;

    /** 缓存（价目表缓存需在启用新版本后被清掉）。 */
    @Autowired
    private CacheService cacheService;

    /**
     * 数据源。
     *
     * @param registry 属性注册器
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("poc.mysql.user", "root"));
            registry.add("spring.datasource.password", () -> System.getProperty("poc.mysql.password", ""));
        }
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    @DisplayName("端到端：建版本 → 加明细 → 启用 → 试算（含命中版本）；未覆盖区域报 40007")
    void rateCardLifecycle() {
        TenantContext.set(TENANT, 1L, Set.of(), null);
        String cardCode = "IT-FREIGHT-" + System.nanoTime();

        // 1. 建版本（草稿）并加明细：上海 [0,3) 首重1kg/8元、续重1kg/2元；重货 [3,∞) 首重3kg/12元、续重1kg/2.5元
        int version = rateCardService.createVersion(cardCode, "IT 价目表", "SF", new BigDecimal("0.1000"));
        assertThat(version).isEqualTo(1);
        rateCardService.addItem(cardCode, version, "SH", new BigDecimal("0"), new BigDecimal("3"),
                new BigDecimal("1"), new BigDecimal("8"), new BigDecimal("1"), new BigDecimal("2"),
                new BigDecimal("6000"), new BigDecimal("0"), 0);
        rateCardService.addItem(cardCode, version, "SH", new BigDecimal("3"), null,
                new BigDecimal("3"), new BigDecimal("12"), new BigDecimal("1"), new BigDecimal("2.5"),
                new BigDecimal("6000"), new BigDecimal("0"), 0);

        // 2. 草稿状态不可计费（防止"建了就开始收钱"）
        assertThatThrownBy(() -> rateCardService.estimate(cardCode,
                new ParcelFacts("SH", new BigDecimal("1"), null), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RATE_CARD_UNAVAILABLE));

        // 3. 启用后试算：2kg → 8 + 1×2 = 10 元，预估价含 10% 缓冲 = 11 元
        rateCardService.enableVersion(cardCode, version);
        FreightQuote quote = rateCardService.estimate(cardCode,
                new ParcelFacts("SH", new BigDecimal("2"), null), null);
        assertThat(quote.freightFee()).isEqualByComparingTo("10.0000");
        assertThat(quote.estimatedFee()).isEqualByComparingTo("11.0000");
        assertThat(quote.cardVersion()).as("命中版本必须回传（落费用单后可复算）").isEqualTo(1);

        // 4. 未覆盖区域 → 40007（不静默计 0）
        assertThatThrownBy(() -> rateCardService.estimate(cardCode,
                new ParcelFacts("BJ", new BigDecimal("1"), null), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未覆盖区域");

        // 5. 调价 = 新增版本；启用新版本后旧版本自动停用，且**缓存必须被清**（否则还按旧价算）
        int newVersion = rateCardService.createVersion(cardCode, "IT 价目表", "SF", new BigDecimal("0.1000"));
        assertThat(newVersion).isEqualTo(2);
        rateCardService.addItem(cardCode, newVersion, "SH", new BigDecimal("0"), null,
                new BigDecimal("1"), new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("2"),
                new BigDecimal("6000"), new BigDecimal("0"), 0);
        rateCardService.enableVersion(cardCode, newVersion);

        FreightQuote afterChange = rateCardService.estimate(cardCode,
                new ParcelFacts("SH", new BigDecimal("2"), null), null);
        assertThat(afterChange.cardVersion()).as("启用新版本后必须按新版本计费（缓存失效）").isEqualTo(2);
        assertThat(afterChange.freightFee()).as("新版本：10 + 1×2 = 12").isEqualByComparingTo("12.0000");

        // 6. 已启用版本不允许再加明细（否则历史运费不可复算）
        assertThatThrownBy(() -> rateCardService.addItem(cardCode, newVersion, "SH", new BigDecimal("10"), null,
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("6000"), new BigDecimal("0"), 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许修改");

        print("价目表生命周期", "草稿不可计费 → 启用生效（v1 十元/预估价十一元）→ 未覆盖区域报 40007 → "
                + "调价新增 v2 并自动停用 v1、缓存失效（十二元）→ 已启用版本拒绝改价");
        cacheService.evictByPrefix("fns:rate-card");
    }

    /**
     * 打印结论（与其它 IT 一致：用例输出即证据）。
     *
     * @param title  标题
     * @param detail 细节
     */
    private void print(String title, String detail) {
        System.out.println("[P3.3] " + title + " | " + detail);
    }
}
