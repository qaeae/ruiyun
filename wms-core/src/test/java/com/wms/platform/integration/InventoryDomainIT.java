package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.MoveCommand;
import com.wms.platform.modules.iv.app.StockOpCommand;
import com.wms.platform.modules.iv.app.StockView;
import com.wms.platform.modules.iv.domain.InventoryInvariant;
import com.wms.platform.modules.md.app.MasterDataQueryService;
import com.wms.platform.poc.PocMysqlCondition;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存域集成测试（W3），跑在**真实 MySQL + 真实 Spring 上下文**上。
 *
 * <p>为什么不能只靠单元测试：三态代数是纯逻辑（有单测），但**账与本事的对应关系**必须落库验证——
 * 条件更新的 {@code WHERE} 写错、库位账漏扣、CHECK 约束没生效，这些都只在真库上才暴露。
 * 特别是 {@code 01} §4 不变量 2（库位合计 = SKU 实物），它是**跨两张表的约定**，
 * 单测无法覆盖。
 *
 * <p>运行方式（与 PoC 夹具一致：有外部 MySQL 或 Docker 才跑，否则跳过）：
 * <pre>
 * mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=InventoryDomainIT \
 *     -Dpoc.mysql.url=jdbc:mysql://127.0.0.1:3399/wms_poc4 -Dpoc.mysql.user=root -Dpoc.mysql.password=
 * </pre>
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest
@DisplayName("库存域（真实库）")
class InventoryDomainIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 仓库。 */
    private static final long WAREHOUSE = 1L;

    /** 商家。 */
    private static final long MERCHANT = 1L;

    /** 商品。 */
    private static final long SKU = 1001L;

    /** 库位 A。 */
    private static final long LOC_A = 2001L;

    /** 库位 B。 */
    private static final long LOC_B = 2002L;

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

    /** 数据访问（夹具用）。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 被测服务。 */
    @Autowired
    private InventoryService inventoryService;

    /** 主数据解析（错扫判定）。 */
    @Autowired
    private MasterDataQueryService masterData;

    /** 每个用例重置库存与主数据。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"iv_movement", "iv_location", "iv_sku", "md_location", "md_zone",
                "md_warehouse", "md_sku", "md_merchant"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
    }

    @Test
    @DisplayName("收货 → 预占 → 释放：三态正确且库位账与 SKU 账始终平")
    void receiveReserveReleaseShouldKeepTwoLevelBalanced() {
        // 1. 收货 100 到库位 A
        StockView received = inventoryService.receive(op("RECEIVE:1", LOC_A, "100", "ASN", "ASN20261014000001"));
        assertThat(received.physicalQty()).isEqualByComparingTo("100");
        assertThat(received.availableQty()).isEqualByComparingTo("100");
        assertThat(received.locationQtySum()).as("库位账 = SKU 账（不变量 2）").isEqualByComparingTo("100");

        // 2. 预占 30
        StockView reserved = inventoryService.reserve(op("RESERVE:1", null, "30", "DO", "DO20261014000001"));
        assertThat(reserved.availableQty()).isEqualByComparingTo("70");
        assertThat(reserved.reservedQty()).isEqualByComparingTo("30");
        assertThat(reserved.physicalQty()).isEqualByComparingTo("100");

        // 3. 释放 10
        StockView released = inventoryService.release(op("RELEASE:1", null, "10", "DO", "DO20261014000001"));
        assertThat(released.availableQty()).isEqualByComparingTo("80");
        assertThat(released.reservedQty()).isEqualByComparingTo("20");

        // 4. 不变量全通过 + 流水 3 条
        assertThat(inventoryService.checkInvariants()).allSatisfy(result -> assertThat(result.passed()).isTrue());
        assertThat(movementCount()).isEqualTo(3);

        print("收货/预占/释放", "三态 100/80/20，库位账 100，流水 3 条");
    }

    @Test
    @DisplayName("出库：SKU 实物与库位实物同减（否则不变量 2 必报差异）")
    void shipShouldReduceBothLevels() {
        // 1. 备货并预占
        inventoryService.receive(op("RECEIVE:1", LOC_A, "100", "ASN", "ASN1"));
        inventoryService.reserve(op("RESERVE:1", null, "40", "DO", "DO1"));

        // 2. 出库 40（带库位）
        StockView shipped = inventoryService.ship(op("SHIP:1", LOC_A, "40", "DO", "DO1"));

        assertThat(shipped.physicalQty()).isEqualByComparingTo("60");
        assertThat(shipped.reservedQty()).isEqualByComparingTo("0");
        assertThat(shipped.availableQty()).isEqualByComparingTo("60");
        assertThat(shipped.locationQtySum()).as("两级账仍平").isEqualByComparingTo("60");
        assertThat(decimal("SELECT physical_qty FROM iv_location WHERE location_id = " + LOC_A))
                .isEqualByComparingTo("60");

        // 3. 出库不带库位 → 拒绝（避免库位账多出来）
        assertThatThrownBy(() -> inventoryService.ship(op("SHIP:2", null, "1", "DO", "DO2")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须指定库位");

        print("出库", "60/60/0，库位 A 60；不带库位的出库被拒");
    }

    @Test
    @DisplayName("移库：库位 A→B，SKU 三态不变、两级账仍平、产生两条流水")
    void moveShouldNotChangeSkuState() {
        // 1. 备货 20 到 A
        inventoryService.receive(op("RECEIVE:1", LOC_A, "20", "ASN", "ASN1"));
        long before = movementCount();

        // 2. 移库 5：A→B
        inventoryService.move(new MoveCommand(WAREHOUSE, MERCHANT, SKU, new BigDecimal("5"), LOC_A, LOC_B,
                "MOVE20261014000001", "MOVE:1"));

        // 3. SKU 三态不变；库位 A 15、B 5
        StockView state = inventoryService.querySku(WAREHOUSE, MERCHANT, SKU);
        assertThat(state.physicalQty()).as("移库不该改总量").isEqualByComparingTo("20");
        assertThat(state.locationQtySum()).isEqualByComparingTo("20");
        assertThat(decimal("SELECT physical_qty FROM iv_location WHERE location_id = " + LOC_A))
                .isEqualByComparingTo("15");
        assertThat(decimal("SELECT physical_qty FROM iv_location WHERE location_id = " + LOC_B))
                .isEqualByComparingTo("5");
        // 4. 两条流水（源 −5、目标 +5）
        assertThat(movementCount() - before).isEqualTo(2);
        assertThat(decimal("SELECT SUM(qty) FROM iv_movement WHERE movement_type = 'MOVE'"))
                .as("移库两条流水合计为 0（总量不变）").isEqualByComparingTo("0");

        print("移库", "A 15 / B 5，SKU 总量 20 不变，双流水合计 0");
    }

    @Test
    @DisplayName("分配 → 拣货：只动库位 allocated，不动 SKU 三态")
    void allocateAndPickShouldOnlyTouchLocation() {
        // 1. 备货
        inventoryService.receive(op("RECEIVE:1", LOC_A, "50", "ASN", "ASN1"));

        // 2. 分配 10、拣货 4
        inventoryService.allocate(op("ALLOCATE:1", LOC_A, "10", "DO", "DO1"));
        inventoryService.pick(op("PICK:1", LOC_A, "4", "DO", "DO1"));

        // 3. 库位 allocated = 6；SKU 三态不变
        assertThat(decimal("SELECT allocated_qty FROM iv_location WHERE location_id = " + LOC_A))
                .isEqualByComparingTo("6");
        StockView state = inventoryService.querySku(WAREHOUSE, MERCHANT, SKU);
        assertThat(state.physicalQty()).isEqualByComparingTo("50");
        assertThat(state.availableQty()).isEqualByComparingTo("50");
        assertThat(state.reservedQty()).isEqualByComparingTo("0");

        // 4. 拣货超过可拣量 → 拒绝
        assertThatThrownBy(() -> inventoryService.pick(op("PICK:2", LOC_A, "7", "DO", "DO1")))
                .isInstanceOf(BizException.class);

        print("分配/拣货", "库位 allocated 6，SKU 三态保持 50/50/0");
    }

    @Test
    @DisplayName("幂等：同一幂等键重复收货/预占 → 不重复加减（uk_movement_idem 生效）")
    void duplicateIdemKeyShouldNotApplyTwice() {
        // 1. 收货 100
        inventoryService.receive(op("RECEIVE:1", LOC_A, "100", "ASN", "ASN1"));
        // 2. 用同一幂等键再收一次（模拟网络重试）
        StockView again = inventoryService.receive(op("RECEIVE:1", LOC_A, "100", "ASN", "ASN1"));

        assertThat(again.physicalQty()).as("重复收货不得再加 100").isEqualByComparingTo("100");
        assertThat(movementCount()).as("流水仍只有 1 条").isEqualTo(1);

        // 3. 预占同理
        inventoryService.reserve(op("RESERVE:1", null, "30", "DO", "DO1"));
        StockView reserveAgain = inventoryService.reserve(op("RESERVE:1", null, "30", "DO", "DO1"));
        assertThat(reserveAgain.reservedQty()).isEqualByComparingTo("30");

        print("幂等", "重复收货/预占均未重复加减，流水不增");
    }

    @Test
    @DisplayName("负向：超量预占 50001、不可拣库位 50003、调整变负 50004")
    void negativeCasesShouldFailWithCodes() {
        // 1. 只有 5 件却预占 6
        inventoryService.receive(op("RECEIVE:1", LOC_A, "5", "ASN", "ASN1"));
        assertThatThrownBy(() -> inventoryService.reserve(op("RESERVE:1", null, "6", "DO", "DO1")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AVAILABLE_STOCK_INSUFFICIENT));
        // 2. 调整到负数
        assertThatThrownBy(() -> inventoryService.adjust(op("ADJUST:1", LOC_A, "-6", "STOCKTAKE", "ST1")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVARIANT_VIOLATED));
        // 3. 不变量仍全部成立（失败的动作必须完全回滚）
        assertThat(inventoryService.checkInvariants()).allSatisfy(result -> assertThat(result.passed()).isTrue());
        assertThat(decimal("SELECT physical_qty FROM iv_sku WHERE sku_id = " + SKU)).isEqualByComparingTo("5");

        print("负向用例", "超量预占/调整变负均被拒，且失败动作完全回滚");
    }

    @Test
    @DisplayName("主数据解析：条码 → 内部 ID；不存在的库位返回 null（错扫判定依据）")
    void masterDataResolutionShouldDriveMissScanDetection() {
        // 1. 备主数据（库位 A、商品 SKU-001）
        jdbc.update("INSERT INTO md_zone (id, tenant_id, warehouse_id, zone_code, zone_type) "
                + "VALUES (3001, 1, ?, 'Z-A', '存储区')", WAREHOUSE);
        jdbc.update("INSERT INTO md_location (id, tenant_id, warehouse_id, zone_id, location_code, is_pickable, status) "
                + "VALUES (?, 1, ?, 3001, 'A-01-02-03', 1, '启用')", LOC_A, WAREHOUSE);
        jdbc.update("INSERT INTO md_sku (id, tenant_id, merchant_id, sku_code, name, unit, weight_kg, status) "
                + "VALUES (?, 1, ?, 'SKU-001', '测试商品', '件', 1.5000, '启用')", SKU, MERCHANT);

        // 2. 解析成功
        assertThat(masterData.findSkuId(MERCHANT, "SKU-001")).isEqualTo(SKU);
        assertThat(masterData.findLocationId(WAREHOUSE, "A-01-02-03")).isEqualTo(LOC_A);

        // 3. 不存在的库位/商品 → null（调用方据此判为错扫）
        assertThat(masterData.findLocationId(WAREHOUSE, "X-99-99-99")).isNull();
        assertThat(masterData.findSkuId(MERCHANT, "SKU-NOT-EXIST")).isNull();

        // 4. 校验库位可拣：不存在的库位抛 50003
        assertThatThrownBy(() -> masterData.assertLocationPickable(WAREHOUSE, "X-99-99-99"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.LOCATION_NOT_PICKABLE));

        print("主数据解析", "条码→ID 成功；不存在库位返回 null 并可抛 50003（错扫判定）");
    }

    @Test
    @DisplayName("不变量校验接口：制造差异后必须能检出（否则日终任务形同虚设）")
    void invariantCheckShouldDetectSeededDiff() throws Exception {
        // 1. 正常备货 → 全部通过
        inventoryService.receive(op("RECEIVE:1", LOC_A, "10", "ASN", "ASN1"));
        assertThat(inventoryService.checkInvariants()).allSatisfy(result -> assertThat(result.passed()).isTrue());

        // 2. 直接改库（模拟"有人绕过应用改了库位账"），不变量 2 必须检出
        jdbc.update("UPDATE iv_location SET physical_qty = physical_qty - 3 WHERE location_id = " + LOC_A);
        List<InventoryInvariant.InvariantResult> results = inventoryService.checkInvariants();

        assertThat(results).anySatisfy(result -> {
            assertThat(result.name()).isEqualTo(InventoryInvariant.NAME_LOCATION_SUM_MATCH);
            assertThat(result.passed()).as("库位账与 SKU 账不等时必须检出").isFalse();
        });

        print("不变量校验", "制造 3 件库位差异后成功检出（不变量2 违反 1 行）");
    }

    // ===================== 夹具与工具 =====================

    /**
     * 组装命令。
     *
     * @param idemKey    幂等键
     * @param locationId 库位（可空）
     * @param qty        数量
     * @param bizType    业务类型
     * @param bizNo      业务单号
     * @return 命令
     */
    private StockOpCommand op(String idemKey, Long locationId, String qty, String bizType, String bizNo) {
        return StockOpCommand.of(WAREHOUSE, MERCHANT, SKU, new BigDecimal(qty), bizType, 1L, bizNo, idemKey,
                locationId);
    }

    /**
     * 流水条数。
     *
     * @return 条数
     */
    private long movementCount() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM iv_movement WHERE tenant_id = " + TENANT, Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 查金额/数量。
     *
     * @param sql SQL
     * @return 值
     */
    private BigDecimal decimal(String sql) {
        return jdbc.queryForObject(sql, BigDecimal.class);
    }

    /**
     * 打印实验报告行。
     *
     * @param step   步骤
     * @param detail 明细
     */
    private void print(String step, String detail) {
        System.out.printf("[W3-IV] %s | %s%n", step, detail);
    }
}
