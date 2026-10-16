package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.iv.api.ro.StocktakeCreateRo;
import com.wms.platform.modules.iv.api.ro.StocktakeRecordRo;
import com.wms.platform.modules.iv.api.vo.StocktakeVo;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.StocktakeService;
import com.wms.platform.modules.iv.domain.StocktakeRule;
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
 * 盘点单集成测试（W3，03 API-IV-03）。
 *
 * <p>验证链路：**建单固定账面快照 → 录入实盘 → 提交 → 双人审批 → 生成 ADJUST 流水 → 不变量仍成立**。
 * 全是跨表跨状态的行为（快照基准、状态机、条件更新、双人复核），单测覆盖不到。
 *
 * <p>运行方式：{@code mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=StocktakeIT
 * -Dpoc.mysql.url=... -Dpoc.mysql.user=root -Dpoc.mysql.password=}
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest
@DisplayName("盘点单（真实库）")
class StocktakeIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 仓库。 */
    private static final long WAREHOUSE = 1L;

    /** 商家。 */
    private static final long MERCHANT = 1L;

    /** 商品 A。 */
    private static final long SKU_A = 3001L;

    /** 商品 B。 */
    private static final long SKU_B = 3002L;

    /** 库位 A。 */
    private static final long LOC_A = 4001L;

    /** 库位 B。 */
    private static final long LOC_B = 4002L;

    /** 创建人（仓管）。 */
    private static final long CREATOR = 5001L;

    /** 审批人（复核人）。 */
    private static final long APPROVER = 5002L;

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

    /** 盘点服务。 */
    @Autowired
    private StocktakeService stocktakeService;

    /** 库存服务（造初始账 + 校验不变量）。 */
    @Autowired
    private InventoryService inventoryService;

    /** 每个用例重置数据并造两行库位账（SKU_A@LOC_A=100、SKU_B@LOC_B=50）。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"iv_stocktake_item", "iv_stocktake", "iv_movement", "iv_location", "iv_sku",
                "md_location"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
        // 库位主数据（盘点范围按库区过滤时要用）
        jdbc.update("INSERT INTO md_location (id, tenant_id, warehouse_id, zone_id, location_code, is_pickable, "
                        + "status, deleted) VALUES (?, 1, ?, 9001, 'A-01-01-01', 1, '启用', 0)",
                LOC_A, WAREHOUSE);
        jdbc.update("INSERT INTO md_location (id, tenant_id, warehouse_id, zone_id, location_code, is_pickable, "
                        + "status, deleted) VALUES (?, 1, ?, 9001, 'A-01-01-02', 1, '启用', 0)",
                LOC_B, WAREHOUSE);
        // 库存账（走库存域建账，保证三态不变量一开始就成立）
        TenantContext.set(TENANT, CREATOR, Set.of(WAREHOUSE), null);
        inventoryService.receive(command("SEED-A", SKU_A, LOC_A, "100"));
        inventoryService.receive(command("SEED-B", SKU_B, LOC_B, "50"));
    }

    /** 清理上下文。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("全链路：建单快照 → 录入（盘亏 5 / 盘盈 3）→ 提交 → 双人审批 → 生成 ADJUST 流水且不变量成立")
    void fullStocktakeFlow() {
        // 1. 建单：明细来自账面快照（SKU_A@LOC_A=100、SKU_B@LOC_B=50）
        String no = stocktakeService.create(new StocktakeCreateRo(WAREHOUSE, null, "全仓", "月末盘点"));
        StocktakeVo created = stocktakeService.detail(no);
        assertThat(created.status()).isEqualTo(StocktakeRule.STATUS_DRAFT);
        assertThat(created.itemCount()).as("两条有实物的库位账").isEqualTo(2);
        assertThat(created.items()).allSatisfy(item -> {
            assertThat(item.actualQty()).as("未录入的实盘必须是 null（0 与没盘要可区分）").isNull();
            assertThat(item.diffQty()).isNull();
        });

        // 2. 录入：SKU_A 盘亏 5（100→95）、SKU_B 盘盈 3（50→53）
        int recorded = stocktakeService.record(no, new StocktakeRecordRo(List.of(
                new StocktakeRecordRo.Item(SKU_A, LOC_A, new BigDecimal("95.0000"), "少 5 件"),
                new StocktakeRecordRo.Item(SKU_B, LOC_B, new BigDecimal("53.0000"), "多 3 件"))));
        assertThat(recorded).isEqualTo(2);
        assertThat(stocktakeService.detail(no).items()).anySatisfy(item -> {
            if (SKU_A == item.skuId()) {
                assertThat(item.diffQty()).isEqualByComparingTo("-5.0000");
            }
        });

        // 3. 提交审批：统计到 2 条差异
        int diffCount = stocktakeService.submit(no);
        assertThat(diffCount).isEqualTo(2);
        assertThat(stocktakeService.detail(no).status()).isEqualTo(StocktakeRule.STATUS_PENDING);

        // 4. 双人复核：创建人自审被拒（80002），且状态保持在「待审批」
        assertThatThrownBy(() -> stocktakeService.approve(no, CREATOR))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVER_MUST_DIFFER));
        assertThat(stocktakeService.detail(no).status()).as("失败审批不得改变状态")
                .isEqualTo(StocktakeRule.STATUS_PENDING);

        // 5. 换人审批通过：生成 2 条 ADJUST 流水
        int adjusted = stocktakeService.approve(no, APPROVER);
        assertThat(adjusted).isEqualTo(2);
        assertThat(stocktakeService.detail(no).status()).isEqualTo(StocktakeRule.STATUS_APPROVED);
        assertThat(count("SELECT COUNT(*) FROM iv_movement WHERE movement_type = 'ADJUST'")).isEqualTo(2);

        // 6. 库存已按实盘归位：SKU_A=95、SKU_B=53，且三态不变量与两级账都成立
        assertThat(decimal("SELECT physical_qty FROM iv_sku WHERE sku_id = " + SKU_A)).isEqualByComparingTo("95");
        assertThat(decimal("SELECT physical_qty FROM iv_sku WHERE sku_id = " + SKU_B)).isEqualByComparingTo("53");
        assertThat(decimal("SELECT physical_qty FROM iv_location WHERE location_id = " + LOC_A))
                .as("库位账同步调整（不变量 2）").isEqualByComparingTo("95");
        assertThat(inventoryService.checkInvariants())
                .allSatisfy(result -> assertThat(result.passed())
                        .as(result.name() + " 应通过").isTrue());

        print("全链路", "建单 2 条明细 → 录入盘亏5/盘盈3 → 自审被拒 → 换人审批 → 2 条 ADJUST 流水 + 不变量成立");
    }

    @Test
    @DisplayName("重复审批不会二次调整（幂等键 = 单号+SKU+库位，已审批状态再点也改不动）")
    void duplicateApproveShouldNotAdjustTwice() {
        String no = stocktakeService.create(new StocktakeCreateRo(WAREHOUSE, null, "全仓", null));
        stocktakeService.record(no, new StocktakeRecordRo(List.of(
                new StocktakeRecordRo.Item(SKU_A, LOC_A, new BigDecimal("90.0000"), null))));
        stocktakeService.submit(no);
        stocktakeService.approve(no, APPROVER);

        // 再审批一次：状态已不是「待审批」→ 拒绝（不会二次扣减）
        assertThatThrownBy(() -> stocktakeService.approve(no, APPROVER))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ILLEGAL_STATUS_TRANSITION));
        assertThat(decimal("SELECT physical_qty FROM iv_sku WHERE sku_id = " + SKU_A))
                .as("库存只被调整一次").isEqualByComparingTo("90");
        assertThat(count("SELECT COUNT(*) FROM iv_movement WHERE movement_type = 'ADJUST'")).isEqualTo(1);

        print("重复审批", "二次审批被拒，库存只调整一次（ADJUST 流水 1 条）");
    }

    @Test
    @DisplayName("提交后明细不可再改（否则审批看到的内容与执行的不一致）")
    void itemsAreFrozenAfterSubmit() {
        String no = stocktakeService.create(new StocktakeCreateRo(WAREHOUSE, null, "全仓", null));
        stocktakeService.record(no, new StocktakeRecordRo(List.of(
                new StocktakeRecordRo.Item(SKU_A, LOC_A, new BigDecimal("95.0000"), null))));
        stocktakeService.submit(no);

        // 提交后再录入 → 拒绝（状态不是「待录入」）
        assertThatThrownBy(() -> stocktakeService.record(no, new StocktakeRecordRo(List.of(
                new StocktakeRecordRo.Item(SKU_A, LOC_A, new BigDecimal("10.0000"), "偷改")))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ILLEGAL_STATUS_TRANSITION));

        // 明细仍是提交时的 95，差异 −5（本单含 2 条明细：SKU_A 已录入、SKU_B 尚未录入，故按 SKU 定位）
        StocktakeVo detail = stocktakeService.detail(no);
        assertThat(detail.items()).filteredOn(item -> SKU_A == item.skuId()).singleElement().satisfies(item -> {
            assertThat(item.actualQty()).isEqualByComparingTo("95.0000");
            assertThat(item.diffQty()).isEqualByComparingTo("-5.0000");
            assertThat(item.itemStatus()).isEqualTo(StocktakeRule.ITEM_RECORDED);
        });

        print("提交后冻结", "二次录入被拒，明细保持提交时的值（审批内容 = 执行内容）");
    }

    @Test
    @DisplayName("驳回：需填原因、终态不得再审批、不产生任何调整流水")
    void rejectFlow() {
        String no = stocktakeService.create(new StocktakeCreateRo(WAREHOUSE, null, "全仓", null));
        stocktakeService.record(no, new StocktakeRecordRo(List.of(
                new StocktakeRecordRo.Item(SKU_A, LOC_A, new BigDecimal("95.0000"), null))));
        stocktakeService.submit(no);

        // 1. 不填原因 → 拒绝
        assertThatThrownBy(() -> stocktakeService.reject(no, "  ", APPROVER))
                .isInstanceOf(BizException.class).hasMessageContaining("驳回必须填写原因");

        // 2. 正常驳回
        stocktakeService.reject(no, "实盘数与交接记录不符，请重盘", APPROVER);
        StocktakeVo rejected = stocktakeService.detail(no);
        assertThat(rejected.status()).isEqualTo(StocktakeRule.STATUS_REJECTED);
        assertThat(rejected.reason()).contains("请重盘");

        // 3. 终态：不能再审批，也不能再录入
        assertThatThrownBy(() -> stocktakeService.approve(no, APPROVER))
                .isInstanceOf(BizException.class).hasMessageContaining("仅「待审批」可审批");
        assertThatThrownBy(() -> stocktakeService.record(no, new StocktakeRecordRo(List.of(
                new StocktakeRecordRo.Item(SKU_A, LOC_A, new BigDecimal("90.0000"), null)))))
                .isInstanceOf(BizException.class);

        // 4. 库存未被改动
        assertThat(decimal("SELECT physical_qty FROM iv_sku WHERE sku_id = " + SKU_A)).isEqualByComparingTo("100");
        assertThat(count("SELECT COUNT(*) FROM iv_movement WHERE movement_type = 'ADJUST'")).isZero();

        print("驳回", "必须填原因；终态不可再审批/录入；库存零改动");
    }

    @Test
    @DisplayName("范围外商品不能通过盘点单调整（防止把别人的库存改掉）")
    void itemsOutsideScopeAreRejected() {
        String no = stocktakeService.create(new StocktakeCreateRo(WAREHOUSE, null, "全仓", null));

        // SKU_A 在盘点范围内；构造一条不存在的 SKU@库位组合 → 拒绝
        assertThatThrownBy(() -> stocktakeService.record(no, new StocktakeRecordRo(List.of(
                new StocktakeRecordRo.Item(SKU_A, LOC_B, new BigDecimal("1.0000"), "越界尝试")))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在本次盘点范围内");

        print("范围校验", "范围内的 SKU@库位 才能录入（越界录入被拒）");
    }

    /**
     * 组装库存命令。
     *
     * @param idemKey    幂等键
     * @param skuId      商品
     * @param locationId 库位
     * @param qty        数量
     * @return 命令
     */
    private com.wms.platform.modules.iv.app.StockOpCommand command(String idemKey, long skuId, long locationId,
                                                                  String qty) {
        return com.wms.platform.modules.iv.app.StockOpCommand.of(WAREHOUSE, MERCHANT, skuId, new BigDecimal(qty),
                "ASN", 1L, "ASN-SEED-" + skuId, idemKey, locationId);
    }

    /**
     * 查数量。
     *
     * @param sql SQL
     * @return 值
     */
    private BigDecimal decimal(String sql) {
        return jdbc.queryForObject(sql, BigDecimal.class);
    }

    /**
     * 查条数。
     *
     * @param sql SQL
     * @return 条数
     */
    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 打印实验报告行。
     *
     * @param step   步骤
     * @param detail 明细
     */
    private void print(String step, String detail) {
        System.out.printf("[W3-PT] %s | %s%n", step, detail);
    }
}
