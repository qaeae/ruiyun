package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.md.api.ro.LocationCreateRo;
import com.wms.platform.modules.md.api.ro.MerchantCreateRo;
import com.wms.platform.modules.md.api.ro.SkuCreateRo;
import com.wms.platform.modules.md.api.ro.UnitConvCreateRo;
import com.wms.platform.modules.md.api.ro.WarehouseCreateRo;
import com.wms.platform.modules.md.api.ro.ZoneCreateRo;
import com.wms.platform.modules.md.api.vo.MerchantVo;
import com.wms.platform.modules.md.app.MasterDataAdminService;
import com.wms.platform.modules.md.app.MasterDataQueryService;
import com.wms.platform.poc.PocMysqlCondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主数据维护集成测试（W3，03 API-MD-01/02/03/04/07）。
 *
 * <p>为什么必须落真库：主数据的价值全在**唯一键与层级约束**上，而这些是数据库行为
 * （{@code uk_merchant_no}、{@code ck_unit_conv_factor_positive}）；同时 PII 加密必须
 * 验证"库里存的确实是密文、接口只出脱敏值"，这在内存里测不出来。
 *
 * <p>运行方式：{@code mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=MasterDataIT
 * -Dpoc.mysql.url=... -Dpoc.mysql.user=root -Dpoc.mysql.password=}
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest
@DisplayName("主数据维护（真实库）")
class MasterDataIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 操作人。 */
    private static final long OPERATOR = 9001L;

    /** Testcontainers 容器（外部模式为 null）。 */
    private static MySQLContainer<?> container;

    /**
     * 注入数据源（外部 MySQL 优先）。
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
        } else {
            container = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("wms_w3").withUsername("poc").withPassword("poc");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        registry.add("spring.flyway.enabled", () -> true);
    }

    /** 数据访问。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 主数据维护服务。 */
    @Autowired
    private MasterDataAdminService adminService;

    /** 主数据解析服务（库存与 PDA 的共同依赖）。 */
    @Autowired
    private MasterDataQueryService queryService;

    /** 每个用例重置主数据。 */
    @BeforeEach
    void resetFixtures() {
        TenantContext.set(TENANT, OPERATOR, Set.of(), "PC-TEST");
        for (String table : new String[]{"md_sku_unit_conv", "md_sku", "md_location", "md_zone", "md_warehouse",
                "md_merchant"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
    }

    /** 清理上下文。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("商家：新增成功、编号重复被拒、手机号落库为密文且接口只出脱敏值")
    void merchantCreateShouldEncryptPiiAndRejectDuplicate() {
        // 1. 新增
        Long id = adminService.createMerchant(new MerchantCreateRo("M-001", "上海某某商贸", "张三", "13812345678"));
        assertThat(id).isNotNull();

        // 2. 编号重复 → 拒绝（人话错误，不是数据库报错）
        assertThatThrownBy(() -> adminService.createMerchant(
                new MerchantCreateRo("M-001", "另一家", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");

        // 3. 库里是密文（不等于明文），且脱敏列已填
        String enc = jdbc.queryForObject("SELECT contact_phone_enc FROM md_merchant WHERE id = " + id, String.class);
        String masked = jdbc.queryForObject("SELECT contact_phone_masked FROM md_merchant WHERE id = " + id,
                String.class);
        assertThat(enc).as("不得明文落库").isNotEqualTo("13812345678").isNotBlank();
        assertThat(masked).as("脱敏值只保留首尾").doesNotContain("12345");

        // 4. 接口返回的列表只有脱敏值
        List<MerchantVo> list = adminService.listMerchants(null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).contactPhoneMasked()).isEqualTo(masked);

        print("商家", "新增成功；编号重复被拒；手机号密文落库 + 列表只出脱敏值");
    }

    @Test
    @DisplayName("层级一致性：库区必须属于该仓库、库位必须属于该库区、编码唯一")
    void hierarchyShouldBeEnforced() {
        // 1. 两个仓库
        Long whA = adminService.createWarehouse(new WarehouseCreateRo("WH-A", "A仓", "上海", "上海", "浦东", null));
        Long whB = adminService.createWarehouse(new WarehouseCreateRo("WH-B", "B仓", "上海", "上海", "嘉定", null));

        // 2. 仓库编码重复 → 拒绝
        assertThatThrownBy(() -> adminService.createWarehouse(new WarehouseCreateRo("WH-A", "重复", null, null, null, null)))
                .hasMessageContaining("已存在");

        // 3. 库区挂在 A 仓下
        Long zoneA = adminService.createZone(new ZoneCreateRo(whA, "Z-A1", "存储区", "存储区"));
        assertThatThrownBy(() -> adminService.createZone(new ZoneCreateRo(999999L, "Z-X", "存储区", "存储区")))
                .as("不存在的仓库必须拒绝").hasMessageContaining("仓库不存在");
        assertThatThrownBy(() -> adminService.createZone(new ZoneCreateRo(whA, "Z-A1", "存储区", "存储区")))
                .as("同仓库内库区编码唯一").hasMessageContaining("已存在");
        assertThatThrownBy(() -> adminService.createZone(new ZoneCreateRo(whA, "Z-BAD", "杂物间", "杂物间")))
                .as("非法类型必须拒绝（避免自由文本）").hasMessageContaining("不支持的库区类型");

        // 4. 库位：库区不属于传入仓库 → 拒绝（跨仓库挂库位会导致跨仓拣货，库存永远对不上）
        assertThatThrownBy(() -> adminService.createLocation(
                new LocationCreateRo(whB, zoneA, "A-01-01-01", "A", true, 1)))
                .isInstanceOf(BizException.class).hasMessageContaining("不一致");

        // 5. 正常创建 + 编码唯一
        Long locId = adminService.createLocation(new LocationCreateRo(whA, zoneA, "A-01-01-01", "A", true, 2));
        assertThat(locId).isNotNull();
        assertThatThrownBy(() -> adminService.createLocation(
                new LocationCreateRo(whA, zoneA, "A-01-01-01", "A", true, 1)))
                .hasMessageContaining("已存在");

        // 6. 库位列表带出折算托盘位（超占费用依据）
        assertThat(adminService.listLocations(whA)).singleElement()
                .satisfies(vo -> {
                    assertThat(vo.quotaUnit()).isEqualTo(2);
                    assertThat(vo.pickable()).isTrue();
                });

        print("层级一致性", "仓库/库区/库位三级校验生效；库区挂错仓、类型非法、编码重复均被拒");
    }

    @Test
    @DisplayName("SKU：单位合法、条码商家内唯一、禁入品类必须给原因")
    void skuValidation() {
        Long merchant = adminService.createMerchant(new MerchantCreateRo("M-002", "商家", null, null));

        // 1. 正常新增（带重量体积，供运费预估）
        Long skuId = adminService.createSku(new SkuCreateRo(merchant, "SKU-001", "矿泉水", "饮品", "件",
                new BigDecimal("0.5000"), new BigDecimal("0.0010"), false, null));
        assertThat(skuId).isNotNull();

        // 2. 单位非法 → 拒绝
        assertThatThrownBy(() -> adminService.createSku(new SkuCreateRo(merchant, "SKU-002", "x", null, "打",
                null, null, false, null)))
                .hasMessageContaining("不支持的单位");

        // 3. 条码重复 → 拒绝
        assertThatThrownBy(() -> adminService.createSku(new SkuCreateRo(merchant, "SKU-001", "重复", "饮品", "件",
                null, null, false, null)))
                .hasMessageContaining("已存在");

        // 4. 禁入品类必须给原因（否则几个月后没人说得清为什么禁）
        assertThatThrownBy(() -> adminService.createSku(new SkuCreateRo(merchant, "SKU-003", "冻品", "冷链", "箱",
                null, null, true, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("禁入原因");

        // 5. 解析服务能按条码找到（库存与 PDA 依赖此入口）
        assertThat(queryService.findSkuId(merchant, "SKU-001")).isEqualTo(skuId);

        print("SKU", "单位/唯一/禁入原因校验生效；条码可被解析服务找到");
    }

    @Test
    @DisplayName("箱规换算：配置 → 换算（含两跳）→ 重复配置被拒")
    void unitConversionRoundTrip() {
        Long merchant = adminService.createMerchant(new MerchantCreateRo("M-003", "商家", null, null));
        Long skuId = adminService.createSku(new SkuCreateRo(merchant, "SKU-010", "整箱商品", "饮品", "件",
                new BigDecimal("1.0000"), null, false, null));

        // 1. 配置 托→箱=20、箱→件=24
        adminService.addUnitConversion(skuId, new UnitConvCreateRo("托", "箱", new BigDecimal("20")));
        adminService.addUnitConversion(skuId, new UnitConvCreateRo("箱", "件", new BigDecimal("24")));

        // 2. 两跳换算：2 托 = 960 件
        assertThat(adminService.convertQty(skuId, new BigDecimal("2"), "托", "件"))
                .isEqualByComparingTo("960.000000");

        // 3. 重复配置 → 拒绝
        assertThatThrownBy(() -> adminService.addUnitConversion(skuId,
                new UnitConvCreateRo("托", "箱", new BigDecimal("20"))))
                .hasMessageContaining("已存在");

        // 4. 非法配置 → 拒绝（自换算 / 零系数）
        assertThatThrownBy(() -> adminService.addUnitConversion(skuId,
                new UnitConvCreateRo("箱", "箱", BigDecimal.TEN)))
                .hasMessageContaining("不能相同");
        assertThatThrownBy(() -> adminService.addUnitConversion(skuId,
                new UnitConvCreateRo("箱", "托", BigDecimal.ZERO)))
                .hasMessageContaining("必须大于 0");

        // 5. 未配置路径 → 拒绝（不返回原值）
        assertThatThrownBy(() -> adminService.convertQty(skuId, BigDecimal.ONE, "件", "托"))
                .isInstanceOf(BizException.class).hasMessageContaining("未配置换算关系");

        // 6. 数据库 CHECK 兜底：直接插零系数会被数据库拒绝（应用层之外的最后防线）
        assertThatThrownBy(() -> jdbc.update("INSERT INTO md_sku_unit_conv (id, tenant_id, sku_id, unit_from, "
                + "unit_to, factor, deleted) VALUES (999001, 1, " + skuId + ", '件', '托', 0, 0)"))
                .as("ck_unit_conv_factor_positive 必须拦住零系数")
                .hasMessageContaining("ck_unit_conv_factor_positive");

        print("箱规换算", "两跳换算 2 托 = 960 件；重复/非法配置被拒；库层 CHECK 兜底生效");
    }

    @Test
    @DisplayName("启停用：停用的库位/SKU 在解析入口即失效（现场扫到就拒绝，而不是靠运维改库）")
    void disableShouldTakeEffectAtResolution() {
        Long merchant = adminService.createMerchant(new MerchantCreateRo("M-004", "商家", null, null));
        Long warehouse = adminService.createWarehouse(new WarehouseCreateRo("WH-C", "C仓", null, null, null, null));
        Long zone = adminService.createZone(new ZoneCreateRo(warehouse, "Z-C1", "存储区", "存储区"));
        Long location = adminService.createLocation(new LocationCreateRo(warehouse, zone, "C-01-01-01", "A", true, 1));
        Long skuId = adminService.createSku(new SkuCreateRo(merchant, "SKU-C1", "停用品", null, "件",
                null, null, false, null));

        // 1. 停用前可解析
        assertThat(queryService.findLocationId(warehouse, "C-01-01-01")).isEqualTo(location);
        assertThat(queryService.findSkuId(merchant, "SKU-C1")).isEqualTo(skuId);

        // 2. 停用
        adminService.changeLocationStatus(location, false);
        adminService.changeSkuStatus(skuId, false);

        // 3. 停用后解析不出（现场扫码会被判为不可用；库存域也无法继续在此库位作业）
        assertThat(queryService.findLocationId(warehouse, "C-01-01-01")).isNull();
        assertThat(queryService.findSkuId(merchant, "SKU-C1")).isNull();

        // 4. 不存在的 ID 启停用 → 拒绝（静默成功会让调用方以为改成功了）
        assertThatThrownBy(() -> adminService.changeLocationStatus(888888L, true))
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR))
                .hasMessageContaining("库位不存在");

        print("启停用", "停用后解析入口立即失效；不存在的 ID 拒绝而非静默成功");
    }

    /**
     * 打印实验报告行。
     *
     * @param step   步骤
     * @param detail 明细
     */
    private void print(String step, String detail) {
        System.out.printf("[W3-MD] %s | %s%n", step, detail);
    }
}
