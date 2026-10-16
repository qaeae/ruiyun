package com.wms.platform.poc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC-4 · 库存三态并发扣减（{@code 06-PoC执行清单.md} §P4）。
 *
 * <p><b>要证伪的风险</b>：设计里"三态库存 + 条件更新 + 流水幂等键"这套机制，在真实并发下是否
 * 真的不超卖、不死锁。这是 W3 库存域开工前的门禁——如果这里不成立，后面所有出库流程都要改。
 *
 * <p><b>为什么用原生 JDBC 而不是 MyBatis/Service</b>：本 PoC 观测的是**数据库锁行为**
 * （行锁、唯一键、CHECK 约束）。走 ORM 会把 SQL 与事务边界藏起来，实验结论就不可信了。
 * W3 用 MyBatis-Plus 落地时，SQL 必须与这里的语句**语义等价**。
 *
 * <p><b>怎么跑</b>（两种模式，二者选一）：
 * <pre>
 * # 1) 外部一次性 MySQL（本机已验证：端口 3399 的临时实例）
 * mvn -pl wms-core -am verify -Dit.skip=false \
 *     -Dpoc.mysql.url=jdbc:mysql://127.0.0.1:3399/wms_poc4 -Dpoc.mysql.user=root -Dpoc.mysql.password=
 *
 * # 2) 有 Docker 时：不传 poc.mysql.url，自动起 Testcontainers MySQL 8.4
 * mvn -pl wms-core -am verify -Dit.skip=false
 * </pre>
 * 两者都没有时**跳过**（不是失败），保证无环境的机器上 CI 仍是绿的。
 *
 * <p>注意：外部模式下会执行 Flyway {@code clean} 重置库，因此**只允许指向库名含 "poc" 的库**，
 * 防止有人把参数指到真实库上——这是 04 §一 的合规红线（不允许不可恢复的破坏性操作）。
 */
@DisplayName("PoC-4 库存三态并发扣减")
class InventoryConcurrencyPocIT {

    // ===================== 夹具常量（仅本类使用，按 B6 留在类内） =====================

    /** 租户 ID（一期固定 1）。 */
    private static final long TENANT_ID = 1L;

    /** 仓库 ID。 */
    private static final long WAREHOUSE_ID = 1L;

    /** 商家 ID。 */
    private static final long MERCHANT_ID = 1L;

    /** 热点 SKU（并发主战场）。 */
    private static final long HOT_SKU_ID = 1001L;

    /** 冷门 SKU（用于交叉扣减的死锁实验）。 */
    private static final long COLD_SKU_ID = 1002L;

    /** 库位 ID。 */
    private static final long LOCATION_ID = 1L;

    /** 单件数量。 */
    private static final BigDecimal ONE = new BigDecimal("1.0000");

    // ===================== SQL：与 W3 落地 SQL 保持语义等价 =====================

    /**
     * 预占（下单）：可用 → 预占，物理不变。{@code available_qty >= ?} 即"防超卖闸门"。
     *
     * <p>这是**条件更新（CAS）**，不是"先查再改"：并发下"先查再改"必然超卖，
     * 而条件更新由 InnoDB 在行锁内完成"判断 + 修改"，天然原子。
     */
    private static final String SQL_RESERVE = """
            UPDATE iv_sku
               SET available_qty = available_qty - ?,
                   reserved_qty  = reserved_qty + ?,
                   version       = version + 1
             WHERE tenant_id = ? AND warehouse_id = ? AND merchant_id = ? AND sku_id = ?
               AND deleted = 0
               AND available_qty >= ?
            """;

    /** 装车出库（扣减）：物理 -、预占 -，可用不变（预留已在前一步转为预占）。 */
    private static final String SQL_SHIP = """
            UPDATE iv_sku
               SET physical_qty = physical_qty - ?,
                   reserved_qty = reserved_qty - ?,
                   version      = version + 1
             WHERE tenant_id = ? AND warehouse_id = ? AND merchant_id = ? AND sku_id = ?
               AND deleted = 0
               AND reserved_qty >= ?
            """;

    /** 写库存流水（幂等键唯一，防重复扣减的最后防线）。 */
    private static final String SQL_MOVEMENT = """
            INSERT INTO iv_movement
              (id, tenant_id, movement_no, warehouse_id, merchant_id, sku_id, location_id, batch_no,
               movement_type, qty, physical_after, available_after, reserved_after,
               biz_type, biz_id, biz_no, idem_key, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, '', ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, NOW(3))
            """;

    /** 三态不变量检查：返回违反行数（必须恒为 0）。 */
    private static final String SQL_INVARIANT_CHECK = """
            SELECT COUNT(*) FROM iv_sku
             WHERE deleted = 0
               AND (physical_qty <> available_qty + reserved_qty
                    OR physical_qty < 0 OR available_qty < 0 OR reserved_qty < 0)
            """;

    /** 库位级 ↔ SKU 级对账：两表物理库存差（必须为 0）。 */
    private static final String SQL_RECONCILE_DIFF = """
            SELECT (SELECT COALESCE(SUM(physical_qty), 0) FROM iv_location
                     WHERE tenant_id = ? AND warehouse_id = ? AND deleted = 0)
                 - (SELECT COALESCE(SUM(physical_qty), 0) FROM iv_sku
                     WHERE tenant_id = ? AND warehouse_id = ? AND deleted = 0)
            """;

    // ===================== 环境与生命周期 =====================

    /** 连接池（并发线程数 200 时需足够连接）。 */
    private static HikariDataSource dataSource;

    /** Testcontainers 容器（外部模式下为 null，需在关闭时停止）。 */
    private static MySQLContainer<?> container;

    /** 是否处于"可重置"模式（外部模式需库名含 poc；容器模式天然可重置）。 */
    private static boolean disposable;

    @BeforeAll
    static void initDatabase() {
        // 1. 优先用外部一次性 MySQL（本机无 Docker 时走这条）
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            // 1.1 安全闸门：只允许指向库名含 "poc" 的库，避免误清真实库
            Assumptions.assumeTrue(url.contains("poc"),
                    "poc.mysql.url 的库名必须包含 'poc'（本 PoC 会执行 Flyway clean 重置库）");
            String user = System.getProperty("poc.mysql.user", "root");
            String password = System.getProperty("poc.mysql.password", "");
            dataSource = pool(url, user, password, 0);
            disposable = true;
            prepareSchema();
            return;
        }

        // 2. 其次用 Testcontainers（CI 有 Docker 时走这条）
        if (DockerClientFactory.instance().isDockerAvailable()) {
            container = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("wms_poc4")
                    .withUsername("poc")
                    .withPassword("poc");
            container.start();
            dataSource = pool(container.getJdbcUrl(), container.getUsername(), container.getPassword(), 0);
            disposable = true;
            prepareSchema();
            return;
        }

        // 3. 两者都不可用：跳过而非失败（保证无环境机器上 CI 为绿）
        Assumptions.abort("既未提供 -Dpoc.mysql.url，也没有可用的 Docker，跳过 PoC-4（不算失败）");
    }

    @AfterAll
    static void closeDatabase() {
        if (container != null) {
            container.stop();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /** 每个用例重置夹具：热点 SKU 物理=可用=100、预占=0，库位物理=100。 */
    @BeforeEach
    void resetFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM iv_movement WHERE tenant_id = " + TENANT_ID);
            st.executeUpdate("DELETE FROM iv_sku WHERE tenant_id = " + TENANT_ID);
            st.executeUpdate("DELETE FROM iv_location WHERE tenant_id = " + TENANT_ID);
            // 1. SKU 级三态：物理 100 = 可用 100 + 预占 0
            st.executeUpdate("""
                    INSERT INTO iv_sku (id, tenant_id, warehouse_id, merchant_id, sku_id,
                                        physical_qty, available_qty, reserved_qty, version, deleted)
                    VALUES (2001, 1, 1, 1, 1001, 100.0000, 100.0000, 0.0000, 0, 0),
                           (2002, 1, 1, 1, 1002, 100.0000, 100.0000, 0.0000, 0, 0)
                    """);
            // 2. 库位级明细：与 SKU 级对账一致
            st.executeUpdate("""
                    INSERT INTO iv_location (id, tenant_id, warehouse_id, location_id, merchant_id, sku_id,
                                             batch_no, physical_qty, allocated_qty, version, deleted)
                    VALUES (3001, 1, 1, 1, 1, 1001, '', 100.0000, 0.0000, 0, 0),
                           (3002, 1, 1, 1, 1, 1002, '', 100.0000, 0.0000, 0, 0)
                    """);
        }
    }

    // ===================== 步骤 1：并发预占不超卖 =====================

    @Test
    @DisplayName("步骤1：100 线程并发预占各 1 件 → 恰 100 单成功，第 101 单被拒（50001）")
    void step1_concurrentReserveShouldNotOversell() throws Exception {
        // 1. 100 线程同时预占，每单 1 件（库存恰好 100）
        ConcurrencyResult result = runConcurrently(100, i -> {
            long orderId = 9000L + i;
            return reserve(HOT_SKU_ID, ONE, "DO:" + orderId + ":RESERVE", orderId);
        });

        // 2. 断言：全部成功、无死锁、无锁等待超时
        assertThat(result.success.get()).as("100 件库存被 100 线程各预占 1 件，应全部成功").isEqualTo(100);
        assertThat(result.stockRejected.get()).as("不应出现库存不足").isZero();
        assertThat(result.deadlocks.get()).as("单行条件更新不应产生死锁").isZero();

        // 3. 第 101 单必须被拒（10001/50001 类业务失败，且库存不被扣成负数）
        TaskOutcome over = reserve(HOT_SKU_ID, ONE, "DO:9100:RESERVE", 9100L);
        assertThat(over.success).as("库存耗尽后第 101 单必须被拒").isFalse();
        assertThat(over.errorCode).as("拒绝原因应为可用库存不足（50001）")
                .isEqualTo(50001);

        // 4. 三态终值：物理 100 = 可用 0 + 预占 100
        SkuState state = readSku(HOT_SKU_ID);
        assertThat(state.physical).isEqualByComparingTo("100");
        assertThat(state.available).isEqualByComparingTo("0");
        assertThat(state.reserved).isEqualByComparingTo("100");

        printReport("步骤1 并发预占", result, "终值: " + state);
    }

    @Test
    @DisplayName("步骤1加强：库存 50 却来 200 线程 → 恰 50 单成功（硬核超卖验证）")
    void step1b_oversellUnderExtremeContention() throws Exception {
        // 1. 把热点 SKU 改成只有 50 件（物理=可用=50）
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE iv_sku SET physical_qty = 50.0000, available_qty = 50.0000 "
                    + "WHERE tenant_id = 1 AND sku_id = " + HOT_SKU_ID);
        }

        // 2. 200 线程抢 50 件库存
        ConcurrencyResult result = runConcurrently(200, i -> {
            long orderId = 8000L + i;
            return reserve(HOT_SKU_ID, ONE, "DO:" + orderId + ":RESERVE", orderId);
        });

        // 3. 断言：成功数恰好等于库存数，一件都不许多卖
        assertThat(result.success.get()).as("200 线程抢 50 件，只能成功 50 单").isEqualTo(50);
        assertThat(result.stockRejected.get()).as("其余 150 单应被库存不足拒绝").isEqualTo(150);
        assertThat(result.deadlocks.get()).isZero();

        // 4. 三态必须自洽且不为负
        SkuState state = readSku(HOT_SKU_ID);
        assertThat(state.available).isEqualByComparingTo("0");
        assertThat(state.reserved).isEqualByComparingTo("50");
        assertThat(invariantViolations()).as("三态不变量必须成立").isZero();

        printReport("步骤1加强 极限抢购", result, "终值: " + state);
    }

    // ===================== 步骤 2：三态不变量并发校验 =====================

    @Test
    @DisplayName("步骤2：并发预占后三态不变量 physical = available + reserved 恒成立")
    void step2_invariantHoldsUnderConcurrency() throws Exception {
        // 1. 并发预占（同时每个线程结束后立即抽查不变量，尽量抓"中间态"）
        AtomicInteger checks = new AtomicInteger();
        ConcurrencyResult result = runConcurrently(100, i -> {
            long orderId = 7000L + i;
            TaskOutcome outcome = reserve(HOT_SKU_ID, ONE, "DO:" + orderId + ":RESERVE", orderId);
            // 2. 读一次不变量（读已提交数据，不是幻读）
            checks.incrementAndGet();
            assertThat(invariantViolations()).as("任意时刻不变量都应成立").isZero();
            return outcome;
        });

        assertThat(result.success.get()).isEqualTo(100);
        assertThat(checks.get()).isEqualTo(100);
        assertThat(invariantViolations()).isZero();

        // 3. 再用一条 SQL 全表校验（覆盖所有行，含冷门 SKU）
        printReport("步骤2 不变量并发校验", result, "抽查次数: " + checks.get() + "，违反行数: 0");
    }

    // ===================== 步骤 3：并发装车扣减 + 死锁观测 =====================

    @Test
    @DisplayName("步骤3：并发装车扣减 100 次 → 无死锁、无超卖，终值全 0")
    void step3_concurrentShipShouldNotDeadlock() throws Exception {
        // 1. 先把 100 件全部预占（模拟已下单待出库）
        for (int i = 0; i < 100; i++) {
            long orderId = 6000L + i;
            assertThat(reserve(HOT_SKU_ID, ONE, "DO:" + orderId + ":RESERVE", orderId).success).isTrue();
        }

        // 2. 100 线程并发装车出库（每单扣 1 件：物理 -、预占 -）
        ConcurrencyResult result = runConcurrently(100, i -> {
            long orderId = 6000L + i;
            return ship(HOT_SKU_ID, ONE, "DO:" + orderId + ":LOAD", orderId);
        });

        // 3. 断言：全部成功、零死锁、零锁等待超时
        assertThat(result.success.get()).as("100 单装车应全部成功").isEqualTo(100);
        assertThat(result.deadlocks.get()).as("单行条件更新不应产生死锁").isZero();
        assertThat(result.lockWaitTimeouts.get()).as("不应出现锁等待超时").isZero();

        // 4. 终值：三态全 0（实物已出库、无残留预占）
        SkuState state = readSku(HOT_SKU_ID);
        assertThat(state.physical).isEqualByComparingTo("0");
        assertThat(state.available).isEqualByComparingTo("0");
        assertThat(state.reserved).isEqualByComparingTo("0");
        assertThat(invariantViolations()).isZero();

        // 5. 流水条数 = 100 预占 + 100 出库（一条不多一条不少）
        assertThat(countMovements()).as("流水应精确等于 200 条").isEqualTo(200);

        printReport("步骤3 并发装车扣减", result, "终值: " + state + "，流水条数: " + countMovements());
    }

    @Test
    @DisplayName("步骤3补充：交叉顺序扣减的死锁实验（证明「按 sku_id 排序」是必要的）")
    void step3b_deadlockExperimentProvesOrderingIsRequired() throws Exception {
        // 1. 无序场景：一半线程 A→B，另一半 B→A，各自一个事务内先锁两行再提交
        int unordered = runCrossUpdateDeadlockProbe(false);
        // 2. 有序场景：全部线程按 sku_id 升序（先 1001 再 1002）
        int ordered = runCrossUpdateDeadlockProbe(true);

        // 3. 结论：无序会死锁（这正是仓库扫描顺序不可控的现实），排序后归零
        System.out.printf("[POC4] 交叉扣减死锁实验：无序=%d 次，按 sku_id 排序=%d 次%n", unordered, ordered);
        assertThat(ordered).as("统一按 sku_id 排序后不应再出现死锁").isZero();
        assertThat(invariantViolations()).isZero();

        printReport("步骤3补充 交叉扣减死锁实验", new ConcurrencyResult(),
                "无序死锁=" + unordered + " 次，排序后=" + ordered + " 次（→ W3 落地必须排序）");
    }

    // ===================== 步骤 4：幂等键防重复扣减 =====================

    @Test
    @DisplayName("步骤4：重复幂等键被 uk_movement_idem 拒绝（ERROR 1062）")
    void step4_duplicateMovementShouldBeRejected() throws Exception {
        // 1. 先正常预占一次（写入流水）
        assertThat(reserve(HOT_SKU_ID, ONE, "DO:5001:RESERVE", 5001L).success).isTrue();

        // 2. 用同一幂等键再插一条流水 → 必须被唯一键拒绝
        int errorCode = insertMovementRaw("DO:5001:RESERVE", 5001L);
        assertThat(errorCode).as("重复幂等键应触发 MySQL 1062（Duplicate entry）").isEqualTo(1062);

        // 3. 再跑一次"重复预占"：条件更新仍会成功扣库存，但流水插不进去 → 事务回滚，库存不变
        TaskOutcome duplicate = reserve(HOT_SKU_ID, ONE, "DO:5001:RESERVE", 5001L);
        assertThat(duplicate.success).as("重复幂等键的预占应失败（流水冲突导致事务回滚）").isFalse();
        assertThat(duplicate.duplicateKey).as("失败原因应为唯一键冲突").isTrue();

        // 4. 关键断言：库存只被扣了一次（这正是流水幂等键存在的意义）
        SkuState state = readSku(HOT_SKU_ID);
        assertThat(state.reserved).as("重复请求不得二次扣减库存").isEqualByComparingTo("1");
        assertThat(state.available).isEqualByComparingTo("99");
        assertThat(invariantViolations()).isZero();

        printReport("步骤4 幂等键防重复", new ConcurrencyResult(),
                "第二次插入错误码=" + errorCode + "，终值: " + state + "（只扣一次 ✓）");
    }

    // ===================== 步骤 5：CHECK 约束兜底 =====================

    @Test
    @DisplayName("步骤5：越界写入被 ck_iv_sku_balance 拒绝（ERROR 3819）")
    void step5_invariantBreachShouldBeRejectedByCheckConstraint() throws Exception {
        // 1. 直接写一条 physical=10 但 available=5 / reserved=4 的越界数据
        int errorCode;
        String message;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO iv_sku (id, tenant_id, warehouse_id, merchant_id, sku_id,
                                         physical_qty, available_qty, reserved_qty, version, deleted)
                     VALUES (4001, 1, 1, 1, 9999, 10.0000, 5.0000, 4.0000, 0, 0)
                     """)) {
            ps.executeUpdate();
            errorCode = 0;
            message = "居然插入成功";
        } catch (SQLException ex) {
            errorCode = ex.getErrorCode();
            message = ex.getMessage();
        }

        // 2. 断言：数据库层拒绝（3819 = CHECK 约束违反），而不是靠应用层自觉
        assertThat(errorCode).as("越界数据必须被数据库拒绝").isEqualTo(3819);
        assertThat(message).contains("ck_iv_sku_balance");
        assertThat(invariantViolations()).isZero();

        printReport("步骤5 CHECK 约束兜底", new ConcurrencyResult(),
                "错误码=" + errorCode + "（" + message.split("\n")[0] + "）");
    }

    // ===================== 步骤 6：库位级 ↔ SKU 级对账 =====================

    @Test
    @DisplayName("步骤6：库位级 ↔ SKU 级对账，差异为 0")
    void step6_locationAndSkuReconcile() throws Exception {
        // 1. 并发预占（不动库位级：预占只影响 SKU 级可用/预占）
        ConcurrencyResult result = runConcurrently(50, i -> {
            long orderId = 4000L + i;
            return reserve(HOT_SKU_ID, ONE, "DO:" + orderId + ":RESERVE", orderId);
        });
        assertThat(result.success.get()).isEqualTo(50);

        // 2. 对账：库位级物理合计 与 SKU 级物理合计必须相等（预占不影响物理库存）
        BigDecimal diff = reconcileDiff();
        assertThat(diff).as("库位级与 SKU 级物理库存差异必须为 0").isEqualByComparingTo("0");

        // 3. 再验证库位级仍有 100（预占不减少实物），SKU 级物理也仍是 100
        SkuState state = readSku(HOT_SKU_ID);
        assertThat(state.physical).isEqualByComparingTo("100");
        assertThat(locationPhysical(HOT_SKU_ID)).isEqualByComparingTo("100");

        printReport("步骤6 库位↔SKU 对账", result, "差异=" + diff + "，库位物理=" + locationPhysical(HOT_SKU_ID));
    }

    // ===================== 基础设施：并发执行与观测 =====================

    /** 单个任务的结果。 */
    private record TaskOutcome(boolean success, int errorCode, boolean duplicateKey) {
    }

    /** 并发执行汇总。 */
    private static final class ConcurrencyResult {
        /** 成功数。 */
        final AtomicInteger success = new AtomicInteger();
        /** 因库存不足被拒数（50001）。 */
        final AtomicInteger stockRejected = new AtomicInteger();
        /** 死锁数（MySQL 1213 / SQLState 40001）。 */
        final AtomicInteger deadlocks = new AtomicInteger();
        /** 锁等待超时数（MySQL 1205）。 */
        final AtomicInteger lockWaitTimeouts = new AtomicInteger();
        /** 其它错误数。 */
        final AtomicInteger others = new AtomicInteger();
        /** 各任务耗时（毫秒）。 */
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * 用固定线程池并发执行任务，用 {@link CountDownLatch} 让所有线程尽量同时开跑（制造真实争抢）。
     *
     * @param threads 并发线程数
     * @param task    任务（入参为序号）
     * @return 汇总结果
     */
    private ConcurrencyResult runConcurrently(int threads, ConcurrentTask task)
            throws InterruptedException {
        ConcurrencyResult result = new ConcurrencyResult();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        long begin = System.nanoTime();
                        TaskOutcome outcome = task.apply(index);
                        result.latencies.add((System.nanoTime() - begin) / 1_000_000);
                        if (outcome.success) {
                            result.success.incrementAndGet();
                        } else if (outcome.errorCode == 50001) {
                            result.stockRejected.incrementAndGet();
                        } else {
                            result.others.incrementAndGet();
                        }
                    } catch (SQLException ex) {
                        classifySqlError(result, ex);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            // 同时开跑
            startGate.countDown();
            boolean finished = doneGate.await(120, TimeUnit.SECONDS);
            assertThat(finished).as("并发任务应在 120 秒内全部结束").isTrue();
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    /** 把 SQL 异常归类为死锁 / 锁等待超时 / 其它。 */
    private void classifySqlError(ConcurrencyResult result, SQLException ex) {
        // 1. 死锁：MySQL 1213，SQLState 40001
        if (ex.getErrorCode() == 1213 || "40001".equals(ex.getSQLState())) {
            result.deadlocks.incrementAndGet();
            return;
        }
        // 2. 锁等待超时：MySQL 1205，SQLState HY000
        if (ex.getErrorCode() == 1205) {
            result.lockWaitTimeouts.incrementAndGet();
            return;
        }
        // 3. 其它（含唯一键冲突等）
        result.others.incrementAndGet();
    }

    /**
     * 预占：条件更新 + 写流水，同一事务。
     *
     * @param skuId      SKU
     * @param qty        数量
     * @param idemKey    幂等键
     * @param bizId      业务单据 ID
     * @return 结果（成功 / 库存不足 / 唯一键冲突）
     */
    private TaskOutcome reserve(long skuId, BigDecimal qty, String idemKey, long bizId) throws SQLException {
        return inTransaction(conn -> {
            int affected;
            try (PreparedStatement ps = conn.prepareStatement(SQL_RESERVE)) {
                ps.setBigDecimal(1, qty);
                ps.setBigDecimal(2, qty);
                ps.setLong(3, TENANT_ID);
                ps.setLong(4, WAREHOUSE_ID);
                ps.setLong(5, MERCHANT_ID);
                ps.setLong(6, skuId);
                ps.setBigDecimal(7, qty);
                affected = ps.executeUpdate();
            }
            // 1. 条件更新未命中 = 可用库存不足（这是唯一的"超卖闸门"）
            if (affected == 0) {
                return new TaskOutcome(false, 50001, false);
            }
            // 2. 写流水（幂等键唯一，重复即回滚整个事务）
            insertMovement(conn, "RESERVE", qty.negate(), "DO", bizId, "DO:" + bizId, idemKey);
            return new TaskOutcome(true, 0, false);
        });
    }

    /**
     * 装车出库：物理 -、预占 -，同一事务写流水。
     *
     * @param skuId   SKU
     * @param qty     数量
     * @param idemKey 幂等键
     * @param bizId   业务单据 ID
     * @return 结果
     */
    private TaskOutcome ship(long skuId, BigDecimal qty, String idemKey, long bizId) throws SQLException {
        return inTransaction(conn -> {
            int affected;
            try (PreparedStatement ps = conn.prepareStatement(SQL_SHIP)) {
                ps.setBigDecimal(1, qty);
                ps.setBigDecimal(2, qty);
                ps.setLong(3, TENANT_ID);
                ps.setLong(4, WAREHOUSE_ID);
                ps.setLong(5, MERCHANT_ID);
                ps.setLong(6, skuId);
                ps.setBigDecimal(7, qty);
                affected = ps.executeUpdate();
            }
            if (affected == 0) {
                return new TaskOutcome(false, 50001, false);
            }
            insertMovement(conn, "SHIP", qty.negate(), "DO", bizId, "DO:" + bizId, idemKey);
            return new TaskOutcome(true, 0, false);
        });
    }

    /** 在一个事务内执行：命中唯一键冲突时回滚并标记。 */
    private TaskOutcome inTransaction(SqlFunction body) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TaskOutcome outcome = body.apply(conn);
                conn.commit();
                return outcome;
            } catch (SQLException ex) {
                conn.rollback();
                if (ex.getErrorCode() == 1062) {
                    return new TaskOutcome(false, 1062, true);
                }
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 写库存流水。 */
    private void insertMovement(Connection conn, String type, BigDecimal qty,
                                String bizType, long bizId, String bizNo, String idemKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_MOVEMENT)) {
            ps.setLong(1, System.nanoTime());   // PoC 用纳秒当 ID 足矣（真实实现走雪花）
            ps.setLong(2, TENANT_ID);
            ps.setString(3, "MV" + System.nanoTime());
            ps.setLong(4, WAREHOUSE_ID);
            ps.setLong(5, MERCHANT_ID);
            ps.setLong(6, HOT_SKU_ID);
            ps.setLong(7, LOCATION_ID);
            ps.setString(8, type);
            ps.setBigDecimal(9, qty);
            ps.setString(10, bizType);
            ps.setLong(11, bizId);
            ps.setString(12, bizNo);
            ps.setString(13, idemKey);
            ps.executeUpdate();
        }
    }

    /** 直接插一条流水，返回错误码（0 表示成功）——用于验证唯一键。 */
    private int insertMovementRaw(String idemKey, long bizId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertMovement(conn, "RESERVE", ONE.negate(), "DO", bizId, "DO:" + bizId, idemKey);
            return 0;
        } catch (SQLException ex) {
            return ex.getErrorCode();
        }
    }

    /**
     * 交叉扣减死锁实验：每个事务更新两个 SKU（每线程各 1 次），比较"无序"与"按 sku_id 排序"。
     *
     * @param ordered true = 统一按 sku_id 升序加锁
     * @return 死锁次数
     */
    private int runCrossUpdateDeadlockProbe(boolean ordered) throws InterruptedException {
        int threads = 40;
        AtomicInteger deadlocks = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final boolean reverse = !ordered && (i % 2 == 1);
                pool.submit(() -> {
                    try {
                        startGate.await();
                        try (Connection conn = dataSource.getConnection()) {
                            conn.setAutoCommit(false);
                            try {
                                // 1. 第一把锁：无序场景下一半线程反序（模拟扫码顺序不可控）
                                long first = reverse ? COLD_SKU_ID : HOT_SKU_ID;
                                long second = reverse ? HOT_SKU_ID : COLD_SKU_ID;
                                lockRow(conn, first);
                                // 2. 留出持锁窗口，放大交叉加锁概率
                                Thread.sleep(5);
                                lockRow(conn, second);
                                conn.commit();
                            } catch (SQLException ex) {
                                conn.rollback();
                                if (ex.getErrorCode() == 1213 || "40001".equals(ex.getSQLState())) {
                                    deadlocks.incrementAndGet();
                                }
                            } finally {
                                conn.setAutoCommit(true);
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (SQLException ex) {
                        // 连接/回滚层面的异常不计入死锁统计（死锁已在事务内计数）
                        System.out.println("[POC4] 交叉扣减实验出现 SQL 异常: " + ex.getMessage());
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            doneGate.await(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // 实验会改动库存，重置夹具，避免影响其它用例
        resetFixtureQuietly();
        return deadlocks.get();
    }

    /** 在事务内锁一行（条件更新，与真实扣减同构）。 */
    private void lockRow(Connection conn, long skuId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_RESERVE)) {
            ps.setBigDecimal(1, ONE);
            ps.setBigDecimal(2, ONE);
            ps.setLong(3, TENANT_ID);
            ps.setLong(4, WAREHOUSE_ID);
            ps.setLong(5, MERCHANT_ID);
            ps.setLong(6, skuId);
            ps.setBigDecimal(7, ONE);
            ps.executeUpdate();
        }
    }

    // ===================== 观测工具 =====================

    /** SKU 三态快照。 */
    private record SkuState(BigDecimal physical, BigDecimal available, BigDecimal reserved, int version) {
        @Override
        public String toString() {
            return "physical=" + physical + " available=" + available
                    + " reserved=" + reserved + " version=" + version;
        }
    }

    /** 读 SKU 三态。 */
    private SkuState readSku(long skuId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT physical_qty, available_qty, reserved_qty, version FROM iv_sku
                      WHERE tenant_id = ? AND warehouse_id = ? AND merchant_id = ? AND sku_id = ?
                     """)) {
            ps.setLong(1, TENANT_ID);
            ps.setLong(2, WAREHOUSE_ID);
            ps.setLong(3, MERCHANT_ID);
            ps.setLong(4, skuId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("夹具 SKU 必须存在").isTrue();
                return new SkuState(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getInt(4));
            }
        }
    }

    /** 三态不变量违反行数。 */
    private long invariantViolations() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SQL_INVARIANT_CHECK)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 库位级物理库存。 */
    private BigDecimal locationPhysical(long skuId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT COALESCE(SUM(physical_qty), 0) FROM iv_location
                      WHERE tenant_id = ? AND warehouse_id = ? AND sku_id = ? AND deleted = 0
                     """)) {
            ps.setLong(1, TENANT_ID);
            ps.setLong(2, WAREHOUSE_ID);
            ps.setLong(3, skuId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** 库位级与 SKU 级对账差异。 */
    private BigDecimal reconcileDiff() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_RECONCILE_DIFF)) {
            ps.setLong(1, TENANT_ID);
            ps.setLong(2, WAREHOUSE_ID);
            ps.setLong(3, TENANT_ID);
            ps.setLong(4, WAREHOUSE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** 流水条数。 */
    private long countMovements() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM iv_movement WHERE tenant_id = " + TENANT_ID)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ===================== 建库与连接池 =====================

    /** 用 Flyway 重置并初始化 Schema（只在可重置的库上执行）。 */
    private static void prepareSchema() {
        // 1. 一次性库：先清空（cleanDisabled(false) 仅在本 PoC 的临时库上生效）
        // 2. 再按 db/migration 迁移（与生产同一套 DDL，保证实验对象就是真实结构）
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(!disposable)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 建连接池。 */
    private static HikariDataSource pool(String url, String user, String password, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        // 并发 200 线程需要足够连接；同时不超过一次性实例的 max_connections
        config.setMaximumPoolSize(maxPoolSize > 0 ? maxPoolSize : 210);
        config.setConnectionTimeout(30_000);
        config.setPoolName("poc4-pool");
        return new HikariDataSource(config);
    }

    /** 静默重置夹具（实验收尾用）。 */
    private void resetFixtureQuietly() {
        try {
            resetFixture();
        } catch (SQLException ex) {
            System.out.println("[POC4] 夹具重置失败: " + ex.getMessage());
        }
    }

    /** 打印统一格式的实验报告（便于从构建日志里 grep）。 */
    private void printReport(String step, ConcurrencyResult result, String extra) {
        List<Long> latencies = new ArrayList<>(result.latencies);
        Collections.sort(latencies);
        long p95 = latencies.isEmpty() ? 0 : latencies.get((int) Math.max(0, Math.ceil(latencies.size() * 0.95) - 1));
        System.out.printf("[POC4] %s | 成功=%d 库存拒绝=%d 死锁=%d 锁超时=%d 其它=%d | p95=%dms | %s%n",
                step, result.success.get(), result.stockRejected.get(), result.deadlocks.get(),
                result.lockWaitTimeouts.get(), result.others.get(), p95, extra);
    }

    /** 带 SQLException 的并发任务（IntFunction 不允许抛受检异常，故自定义）。 */
    @FunctionalInterface
    private interface ConcurrentTask {
        TaskOutcome apply(int index) throws SQLException;
    }

    /** 带 SQLException 的函数式接口。 */
    @FunctionalInterface
    private interface SqlFunction {
        TaskOutcome apply(Connection conn) throws SQLException;
    }
}
