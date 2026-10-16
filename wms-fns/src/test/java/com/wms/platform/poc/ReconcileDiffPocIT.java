package com.wms.platform.poc;

import com.wms.platform.modules.fns.domain.AdvancePaymentRule;
import com.wms.platform.modules.fns.domain.FallbackDeductionRule;
import com.wms.platform.modules.fns.domain.HandleResult;
import com.wms.platform.modules.fns.domain.Liability;
import com.wms.platform.modules.fns.domain.ReconcileDiffRule;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC-5 · 对账差异闭环 + 兜底扣减（{@code 06-PoC执行清单.md} §P5）。
 *
 * <p><b>要证伪的风险</b>：差异阈值、7 天兜底、垫付与限制、月结批次这几件事，
 * 在真实数据与真实约束下能不能闭环。它比其他 PoC 更"财务"——错一分钱、错一天，
 * 后果是给承运商多付或少付、或把商家错误停服。
 *
 * <p><b>口径说明（判定规则来自领域类，不是本测试自造）</b>：
 * 阈值判定用 {@link ReconcileDiffRule}、兜底用 {@link FallbackDeductionRule}、
 * 垫付与限制用 {@link AdvancePaymentRule}——这些类 W4 对账模块会直接复用，
 * 所以 PoC 验证的是**将要上线的规则本身**，而不是测试里另写一套。
 *
 * <p><b>怎么跑</b>（与 PoC-4 相同的双模式）：
 * <pre>
 * mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=ReconcileDiffPocIT \
 *     -Dpoc.mysql.url=jdbc:mysql://127.0.0.1:3399/wms_poc4 -Dpoc.mysql.user=root -Dpoc.mysql.password=
 * </pre>
 * 未提供 URL 且无 Docker 时**跳过（不算失败）**。
 */
@DisplayName("PoC-5 对账差异闭环 + 兜底扣减")
class ReconcileDiffPocIT {

    // ===================== 夹具常量 =====================

    /** 租户。 */
    private static final long TENANT_ID = 1L;

    /** 商家 A（对账与垫付主体）。 */
    private static final long MERCHANT_A = 1L;

    /** 承运商编码。 */
    private static final String CARRIER = "SF";

    /** 账期。 */
    private static final String PERIOD = "2026-10";

    /** 对账单号。 */
    private static final String RCL_NO = "RCL2026100001";

    // ===================== 环境 =====================

    /** 连接池。 */
    private static HikariDataSource dataSource;

    /** Testcontainers 容器（外部模式为 null）。 */
    private static MySQLContainer<?> container;

    /** 是否可重置（外部模式要求库名含 poc；容器模式天然可重置）。 */
    private static boolean disposable;

    @BeforeAll
    static void initDatabase() {
        // 1. 外部一次性 MySQL 优先
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            Assumptions.assumeTrue(url.contains("poc"),
                    "poc.mysql.url 的库名必须包含 'poc'（本 PoC 会执行 Flyway clean 重置库）");
            dataSource = pool(url, System.getProperty("poc.mysql.user", "root"),
                    System.getProperty("poc.mysql.password", ""));
            disposable = true;
            prepareSchema();
            return;
        }
        // 2. 其次 Testcontainers
        if (DockerClientFactory.instance().isDockerAvailable()) {
            container = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("wms_poc5").withUsername("poc").withPassword("poc");
            container.start();
            dataSource = pool(container.getJdbcUrl(), container.getUsername(), container.getPassword());
            disposable = true;
            prepareSchema();
            return;
        }
        // 3. 都不可用：跳过而非失败
        Assumptions.abort("既未提供 -Dpoc.mysql.url，也没有可用 Docker，跳过 PoC-5（不算失败）");
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

    /** 每个用例重置本 PoC 涉及的表（表间无外键约束，顺序无要求）。 */
    @BeforeEach
    void resetTables() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String table : new String[]{"fn_reconcile_item", "fn_reconcile_bill", "fn_settle_batch",
                    "fn_advance_payment", "ac_ledger", "ac_fee_freeze", "lg_shipment", "ac_merchant_account"}) {
                st.executeUpdate("DELETE FROM " + table + " WHERE tenant_id = " + TENANT_ID);
            }
        }
    }

    // ===================== 步骤 1–2：差异判定与对账单聚合 =====================

    @Test
    @DisplayName("步骤1-2：20 单四类差异 → 13 自动平账 / 3 挂账 / 2 强制人工；对账单金额可复算")
    void step1And2_diffJudgementAndBillAggregation() throws SQLException {
        // 1. 造 20 张运单的平台费用与承运商费用（用领域规则判定后落库）
        long billId = insertBill();
        List<ReconcileDiffRule.Result> results = new ArrayList<>();
        int seq = 0;
        // 1.1 无差异 10 单
        for (int i = 0; i < 10; i++) {
            results.add(insertItem(billId, ++seq, new BigDecimal("100.0000"), new BigDecimal("100.0000"), null));
        }
        // 1.2 差异 0.5 元 3 单（≤1 元 → 自动平账）
        for (int i = 0; i < 3; i++) {
            results.add(insertItem(billId, ++seq, new BigDecimal("50.0000"), new BigDecimal("50.5000"), null));
        }
        // 1.3 差异 1.2% 3 单（>1% 且 ≤20% → 挂账）
        for (int i = 0; i < 3; i++) {
            results.add(insertItem(billId, ++seq, new BigDecimal("100.0000"), new BigDecimal("101.2000"), null));
        }
        // 1.4 差异 25% 2 单（>20% → 强制人工）
        for (int i = 0; i < 2; i++) {
            results.add(insertItem(billId, ++seq, new BigDecimal("100.0000"), new BigDecimal("125.0000"), null));
        }
        // 1.5 差异 30% 且判为承运商责任 2 单（裁定后按责任归属）
        for (int i = 0; i < 2; i++) {
            results.add(insertItem(billId, ++seq, new BigDecimal("100.0000"), new BigDecimal("130.0000"),
                    Liability.LOGISTICS));
        }

        // 2. 断言判定分布（与 06 §P5 的期望完全一致）
        assertThat(count(results, HandleResult.AUTO)).as("10 无差异 + 3 小额差异").isEqualTo(13);
        assertThat(count(results, HandleResult.PENDING)).isEqualTo(3);
        assertThat(count(results, HandleResult.MANUAL)).as(">20% 强制人工，不可自动通过").isEqualTo(2);
        assertThat(count(results, HandleResult.LIABILITY)).isEqualTo(2);
        // 2.1 强制人工的 2 单必须 requireManual
        assertThat(results.stream().filter(ReconcileDiffRule.Result::requireManual).count()).isEqualTo(2);

        // 3. 聚合对账单（金额与笔数由明细汇总，不手工填）
        aggregateBill(billId);

        // 4. 用 SQL 独立复算，断言"聚合结果 = 明细复算"（防止应用层算错）
        BillActual actual = readBill(billId);
        assertThat(actual.totalWaybills).isEqualTo(20);
        assertThat(actual.autoSettledCount).isEqualTo(13);
        assertThat(actual.manualCount).as("挂账 3 + 强制人工 2").isEqualTo(5);
        assertThat(actual.platformAmount).isEqualByComparingTo(sqlDecimal(
                "SELECT COALESCE(SUM(platform_fee),0) FROM fn_reconcile_item WHERE tenant_id=1 AND bill_id=" + billId));
        assertThat(actual.carrierAmount).isEqualByComparingTo(sqlDecimal(
                "SELECT COALESCE(SUM(carrier_fee),0) FROM fn_reconcile_item WHERE tenant_id=1 AND bill_id=" + billId));
        assertThat(actual.diffAmount).isEqualByComparingTo(sqlDecimal(
                "SELECT COALESCE(SUM(diff_amount),0) FROM fn_reconcile_item WHERE tenant_id=1 AND bill_id=" + billId));

        printReport("步骤1-2 差异判定与对账单聚合",
                "自动=" + actual.autoSettledCount + " 挂账/人工=" + actual.manualCount
                        + " 平台=" + actual.platformAmount + " 承运商=" + actual.carrierAmount
                        + " 差异=" + actual.diffAmount);
    }

    // ===================== 步骤 5：责任裁定 =====================

    @Test
    @DisplayName("步骤5：差异 30% 判为承运商责任 → 裁定后 handle_result=责任方承担、liability=物流")
    void step5_liabilityJudgement() throws SQLException {
        // 1. 造 2 单 30% 差异并裁定物流责任
        long billId = insertBill();
        insertItem(billId, 1, new BigDecimal("100.0000"), new BigDecimal("130.0000"), Liability.LOGISTICS);
        insertItem(billId, 2, new BigDecimal("200.0000"), new BigDecimal("260.0000"), Liability.LOGISTICS);

        // 2. 断言落库结论
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT handle_result, liability, COUNT(*) c FROM fn_reconcile_item "
                     + "WHERE tenant_id=1 AND bill_id=" + billId + " GROUP BY handle_result, liability")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("handle_result")).isEqualTo(HandleResult.LIABILITY.getLabel());
            assertThat(rs.getString("liability")).isEqualTo(Liability.LOGISTICS.getLabel());
            assertThat(rs.getInt("c")).isEqualTo(2);
        }

        // 3. 责任方承担的明细才可进结算（挂账/强制人工不可）
        assertThat(HandleResult.LIABILITY.isSettleable()).isTrue();
        assertThat(HandleResult.PENDING.isSettleable()).isFalse();

        printReport("步骤5 责任裁定", "2 单裁定为物流责任 → handle_result=责任方承担，可进结算");
    }

    // ===================== 步骤 6–7：兜底扣减与多退少补 =====================

    @Test
    @DisplayName("步骤6-7：揽收满 7 天触发兜底（挂待核销）→ 收到真回传多退少补并清标记")
    void step6And7_fallbackDeductionAndSettle() throws SQLException {
        // 1. 造一张揽收时间为「7 天前」的运单 + 对应冻结单
        BigDecimal estimatedFee = new BigDecimal("10.0000");
        long shipmentId = insertShipment("SHP-POC5-001", estimatedFee, LocalDateTime.now().minusDays(7));
        long freezeId = insertFreeze("FRZ-POC5-001", "SHP-POC5-001", estimatedFee, shipmentId);

        // 2. 判定是否触发兜底（用真实当前时间，不改系统时间）
        LocalDateTime pickedUpAt = readPickedUpAt(shipmentId);
        FallbackDeductionRule.Result fallback =
                FallbackDeductionRule.evaluate(pickedUpAt, LocalDateTime.now(), false);
        assertThat(fallback.shouldFallback()).as("揽收满 7 天未回传 → 必须触发").isTrue();

        // 3. 执行兜底扣减：按预估价扣减 + 挂「待核销」
        //    口径：DDL 中「待核销」对应 ac_fee_freeze.status='已兜底扣减'（语义即"先按预估价扣、等真回传核销"）
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE ac_fee_freeze
                        SET status = '已兜底扣减',
                            deducted_amount = estimated_amount,
                            fallback_deadline = ?,
                            deducted_at = NOW(3)
                      WHERE tenant_id = 1 AND id = ?
                     """)) {
            ps.setTimestamp(1, Timestamp.valueOf(fallback.deadline()));
            ps.setLong(2, freezeId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        assertThat(readFreezeStatus(freezeId)).isEqualTo("已兜底扣减");

        // 4. 收到真实回传（实费 8 元 < 预估价 10 元）→ 多退少补：应退 2 元
        FallbackDeductionRule.Adjustment adjustment =
                FallbackDeductionRule.settle(estimatedFee, new BigDecimal("8.0000"));
        assertThat(adjustment.action()).isEqualTo(FallbackDeductionRule.Action.REFUND);
        assertThat(adjustment.adjustAmount()).isEqualByComparingTo("-2.0000");

        // 5. 写资金流水（退款）并核销兜底标记
        insertLedger("LG-POC5-001", "REFUND", adjustment.adjustAmount(), "SHP-POC5-001");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE ac_fee_freeze
                        SET status = '已扣减', deducted_amount = ?, released_amount = released_amount + ?
                      WHERE tenant_id = 1 AND id = ?
                     """)) {
            ps.setBigDecimal(1, new BigDecimal("8.0000"));
            ps.setBigDecimal(2, adjustment.adjustAmount().abs());
            ps.setLong(3, freezeId);
            ps.executeUpdate();
        }

        // 6. 断言：状态已清「待核销」、扣减额=实费、退款额=2 元、流水方向正确
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT status, deducted_amount, released_amount FROM ac_fee_freeze "
                     + "WHERE tenant_id=1 AND id=" + freezeId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("已扣减");
            assertThat(rs.getBigDecimal("deducted_amount")).isEqualByComparingTo("8.0000");
            assertThat(rs.getBigDecimal("released_amount")).isEqualByComparingTo("2.0000");
        }
        assertThat(sqlDecimal("SELECT COALESCE(SUM(change_amount),0) FROM ac_ledger "
                + "WHERE tenant_id=1 AND biz_type='REFUND'")).isEqualByComparingTo("-2.0000");

        printReport("步骤6-7 兜底与多退少补",
                "兜底按预估价 10 元扣减 → 真回传 8 元 → 退款 2 元，核销完成（状态已扣减）");
    }

    // ===================== 步骤 8–9：垫付台账与限制阶段 =====================

    @Test
    @DisplayName("步骤8-9：实费超冻结额且余额不足 → 差额进垫付台账；累计>2000 或连续3单 → 触发限制")
    void step8And9_advancePaymentAndLimit() throws SQLException {
        // 1. 运单：实费 15 元、冻结 10 元、商家余额 4 元 → 平台垫付 1 元
        BigDecimal actualFee = new BigDecimal("15.0000");
        BigDecimal frozen = new BigDecimal("10.0000");
        BigDecimal balance = new BigDecimal("4.0000");
        BigDecimal advance = AdvancePaymentRule.advanceNeeded(actualFee, frozen, balance);
        assertThat(advance).as("垫付 = 实费 - 冻结 - 余额").isEqualByComparingTo("1.0000");

        // 2. 落垫付台账（状态=待追偿）
        insertAdvance("SHP-POC5-ADV-1", MERCHANT_A, advance, "待追偿");
        assertThat(sqlDecimal("SELECT COALESCE(SUM(amount),0) FROM fn_advance_payment WHERE tenant_id=1"))
                .isEqualByComparingTo("1.0000");

        // 3. 同一运单重复垫付 → 被 uk_advance 拒绝（不接受重复挂账）
        int duplicateError = 0;
        try {
            insertAdvance("SHP-POC5-ADV-1", MERCHANT_A, advance, "待追偿");
        } catch (SQLException ex) {
            duplicateError = ex.getErrorCode();
        }
        assertThat(duplicateError).as("uk_advance 应拒绝同一运单二次垫付").isEqualTo(1062);

        // 4. 再垫 2 单（金额小），凑成「连续 3 单」→ 触发限制
        insertAdvance("SHP-POC5-ADV-2", MERCHANT_A, new BigDecimal("0.5000"), "待追偿");
        insertAdvance("SHP-POC5-ADV-3", MERCHANT_A, new BigDecimal("0.5000"), "待追偿");
        BigDecimal cumulative = sqlDecimal("SELECT COALESCE(SUM(amount),0) FROM fn_advance_payment "
                + "WHERE tenant_id=1 AND merchant_id=" + MERCHANT_A);
        long count = sqlLong("SELECT COUNT(*) FROM fn_advance_payment WHERE tenant_id=1 AND merchant_id=" + MERCHANT_A);

        assertThat(AdvancePaymentRule.evaluateLimit(cumulative, (int) count).limited())
                .as("累计 %s 未超 2000，但连续 %d 单 → 触发限制阶段", cumulative, count).isTrue();

        // 5. 阈值边界：恰好 2000 不限制、2000.0001 限制
        assertThat(AdvancePaymentRule.evaluateLimit(new BigDecimal("2000.0000"), 1).limited()).isFalse();
        assertThat(AdvancePaymentRule.evaluateLimit(new BigDecimal("2000.0001"), 1).limited()).isTrue();

        // 6. 台账可复算：3 笔垫付（1.0 + 0.5 + 0.5）合计 2 元
        assertThat(cumulative).isEqualByComparingTo("2.0000");

        printReport("步骤8-9 垫付与限制",
                "单笔垫付=" + advance + "，累计=" + cumulative + "，连续=" + count
                        + " 单 → 触发限制阶段；重复垫付被 1062 拒绝");
    }

    // ===================== 步骤 10：月末批次可复算 =====================

    @Test
    @DisplayName("步骤10：月末批次金额 = Σ 当期已确认明细的承运商费用（可复算）")
    void step10_settleBatchRecomputable() throws SQLException {
        // 1. 造对账单明细：3 单自动平账 + 1 单挂账（不可结算）+ 1 单责任方承担（可结算）
        long billId = insertBill();
        insertItem(billId, 1, new BigDecimal("100.0000"), new BigDecimal("100.0000"), null);   // 自动平账
        insertItem(billId, 2, new BigDecimal("200.0000"), new BigDecimal("200.0000"), null);   // 自动平账
        insertItem(billId, 3, new BigDecimal("300.0000"), new BigDecimal("300.0000"), null);   // 自动平账
        insertItem(billId, 4, new BigDecimal("100.0000"), new BigDecimal("101.2000"), null);   // 挂账 → 不可结算
        insertItem(billId, 5, new BigDecimal("400.0000"), new BigDecimal("520.0000"), Liability.LOGISTICS); // 可结算

        // 2. 批次金额 = Σ 已确认（自动平账 + 责任方承担）明细的承运商费用
        BigDecimal expected = sqlDecimal("SELECT COALESCE(SUM(carrier_fee),0) FROM fn_reconcile_item "
                + "WHERE tenant_id=1 AND bill_id=" + billId
                + " AND handle_result IN ('自动平账','责任方承担')");
        long expectedCount = sqlLong("SELECT COUNT(*) FROM fn_reconcile_item WHERE tenant_id=1 AND bill_id=" + billId
                + " AND handle_result IN ('自动平账','责任方承担')");
        assertThat(expected).as("100+200+300+520 = 1120（挂账那单不计入）").isEqualByComparingTo("1120.0000");
        assertThat(expectedCount).isEqualTo(4);

        // 3. 生成结算批次
        insertBatch("STB2026100001", billId, expected, (int) expectedCount);

        // 4. 复算断言：批次金额 = 明细复算值；笔数一致
        assertThat(sqlDecimal("SELECT total_amount FROM fn_settle_batch WHERE tenant_id=1 AND bill_id=" + billId))
                .isEqualByComparingTo(expected);
        assertThat(sqlLong("SELECT waybill_count FROM fn_settle_batch WHERE tenant_id=1 AND bill_id=" + billId))
                .isEqualTo(expectedCount);

        // 5. 一张对账单只允许一个批次（uk_bill）
        int duplicateError = 0;
        try {
            insertBatch("STB2026100002", billId, expected, (int) expectedCount);
        } catch (SQLException ex) {
            duplicateError = ex.getErrorCode();
        }
        assertThat(duplicateError).as("uk_bill 应拒绝同对账单二次生成批次").isEqualTo(1062);

        printReport("步骤10 月末批次",
                "批次金额=" + expected + "（4 单已确认），挂账 1 单未计入；重复生成被 1062 拒绝");
    }

    // ===================== 夹具与工具 =====================

    /**
     * 插入对账单。
     *
     * @return 对账单 ID
     */
    private long insertBill() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO fn_reconcile_bill
                       (id, tenant_id, rcl_no, carrier_code, period, status, total_waybills,
                        platform_amount, carrier_amount, diff_amount, auto_settled_count, manual_count)
                     VALUES (?, 1, ?, ?, ?, '待核对', 0, 0, 0, 0, 0, 0)
                     """)) {
            long id = System.nanoTime();
            ps.setLong(1, id);
            ps.setString(2, RCL_NO + "-" + id % 100000);
            ps.setString(3, CARRIER);
            ps.setString(4, PERIOD);
            ps.executeUpdate();
            return id;
        }
    }

    /**
     * 用领域规则判定差异并落库明细。
     *
     * @param billId      对账单 ID
     * @param seq         明细序号
     * @param platformFee 平台记录费用
     * @param carrierFee  承运商费用
     * @param liability   裁定责任方（{@code null} 表示未裁定）
     * @return 判定结果
     */
    private ReconcileDiffRule.Result insertItem(long billId, int seq, BigDecimal platformFee,
                                               BigDecimal carrierFee, Liability liability) throws SQLException {
        // 1. 调领域规则（不在此处重写阈值逻辑）
        ReconcileDiffRule.Result result = ReconcileDiffRule.check(platformFee, carrierFee);
        if (liability != null) {
            result = ReconcileDiffRule.judge(result, liability, "Poc 裁定");
        }
        // 2. 落库
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO fn_reconcile_item
                       (id, tenant_id, bill_id, waybill_no, merchant_id, platform_fee, carrier_fee,
                        diff_amount, diff_rate, diff_type, handle_result, liability, remark)
                     VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setLong(1, System.nanoTime() + seq);
            ps.setLong(2, billId);
            ps.setString(3, "WB-POC5-" + billId % 10000 + "-" + seq);
            ps.setLong(4, MERCHANT_A);
            ps.setBigDecimal(5, platformFee);
            ps.setBigDecimal(6, carrierFee);
            ps.setBigDecimal(7, result.diffAmount());
            ps.setBigDecimal(8, result.diffRate() == null ? BigDecimal.ZERO : result.diffRate());
            ps.setString(9, result.diffAmount().compareTo(BigDecimal.ZERO) == 0 ? "无差异" : "重量差异");
            ps.setString(10, result.handleResult().getLabel());
            ps.setString(11, result.liability() == null ? null : result.liability().getLabel());
            ps.setString(12, result.message());
            ps.executeUpdate();
        }
        return result;
    }

    /** 按明细聚合对账单（金额与笔数全部由 SQL 汇总，避免应用层手工累加出错）。 */
    private void aggregateBill(long billId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE fn_reconcile_bill b
                        SET b.total_waybills     = (SELECT COUNT(*) FROM fn_reconcile_item i
                                                     WHERE i.tenant_id = b.tenant_id AND i.bill_id = b.id),
                            b.platform_amount    = (SELECT COALESCE(SUM(i.platform_fee),0) FROM fn_reconcile_item i
                                                     WHERE i.tenant_id = b.tenant_id AND i.bill_id = b.id),
                            b.carrier_amount     = (SELECT COALESCE(SUM(i.carrier_fee),0) FROM fn_reconcile_item i
                                                     WHERE i.tenant_id = b.tenant_id AND i.bill_id = b.id),
                            b.diff_amount        = (SELECT COALESCE(SUM(i.diff_amount),0) FROM fn_reconcile_item i
                                                     WHERE i.tenant_id = b.tenant_id AND i.bill_id = b.id),
                            b.auto_settled_count = (SELECT COUNT(*) FROM fn_reconcile_item i
                                                     WHERE i.tenant_id = b.tenant_id AND i.bill_id = b.id
                                                       AND i.handle_result = '自动平账'),
                            b.manual_count       = (SELECT COUNT(*) FROM fn_reconcile_item i
                                                     WHERE i.tenant_id = b.tenant_id AND i.bill_id = b.id
                                                       AND i.handle_result IN ('挂账待处理','人工处理'))
                      WHERE b.tenant_id = 1 AND b.id = ?
                     """)) {
            ps.setLong(1, billId);
            ps.executeUpdate();
        }
    }

    /** 对账单实际值。 */
    private record BillActual(int totalWaybills, int autoSettledCount, int manualCount,
                              BigDecimal platformAmount, BigDecimal carrierAmount, BigDecimal diffAmount) {
    }

    /** 读对账单汇总值。 */
    private BillActual readBill(long billId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT total_waybills, auto_settled_count, manual_count,
                            platform_amount, carrier_amount, diff_amount
                       FROM fn_reconcile_bill WHERE tenant_id = 1 AND id = ?
                     """)) {
            ps.setLong(1, billId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new BillActual(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6));
            }
        }
    }

    /** 插运单（含揽收时间与预估价）。 */
    private long insertShipment(String shpNo, BigDecimal estimatedFee, LocalDateTime pickedUpAt) throws SQLException {
        long id = Math.abs(System.nanoTime());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO lg_shipment
                       (id, tenant_id, shp_no, do_id, do_no, warehouse_id, merchant_id, carrier_code,
                        status, estimated_fee, picked_up_at)
                     VALUES (?, 1, ?, 1, ?, 1, ?, ?, '已揽收', ?, ?)
                     """)) {
            ps.setLong(1, id);
            ps.setString(2, shpNo);
            ps.setString(3, "DO-POC5-001");
            ps.setLong(4, MERCHANT_A);
            ps.setString(5, CARRIER);
            ps.setBigDecimal(6, estimatedFee);
            ps.setTimestamp(7, Timestamp.valueOf(pickedUpAt));
            ps.executeUpdate();
        }
        return id;
    }

    /** 读运单揽收时间。 */
    private LocalDateTime readPickedUpAt(long shipmentId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT picked_up_at FROM lg_shipment WHERE id = " + shipmentId)) {
            assertThat(rs.next()).isTrue();
            return rs.getTimestamp(1).toLocalDateTime();
        }
    }

    /** 插冻结单。 */
    private long insertFreeze(String freezeNo, String bizNo, BigDecimal estimatedAmount, long shipmentId)
            throws SQLException {
        long id = Math.abs(System.nanoTime()) + 1;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ac_fee_freeze
                       (id, tenant_id, freeze_no, merchant_id, biz_type, biz_no, shipment_id,
                        estimated_amount, buffer_rate, frozen_amount, status, fallback_deadline, frozen_at)
                     VALUES (?, 1, ?, ?, 'LOGISTICS', ?, ?, ?, 0.1000, ?, '已冻结', NULL, NOW(3))
                     """)) {
            ps.setLong(1, id);
            ps.setString(2, freezeNo);
            ps.setLong(3, MERCHANT_A);
            ps.setString(4, bizNo);
            ps.setLong(5, shipmentId);
            ps.setBigDecimal(6, estimatedAmount);
            ps.setBigDecimal(7, estimatedAmount.multiply(new BigDecimal("1.1000")));
            ps.executeUpdate();
        }
        return id;
    }

    /** 读冻结单状态。 */
    private String readFreezeStatus(long freezeId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM ac_fee_freeze WHERE id = " + freezeId)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    /** 插资金流水。 */
    private void insertLedger(String ledgerNo, String bizType, BigDecimal changeAmount, String refNo)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ac_ledger
                       (id, tenant_id, ledger_no, merchant_id, account_type, biz_type,
                        change_amount, balance_after, freeze_after, ref_type, ref_no, status, occurred_at)
                     VALUES (?, 1, ?, ?, 'BALANCE', ?, ?, 0, 0, 'SHIPMENT', ?, '成功', NOW(3))
                     """)) {
            ps.setLong(1, Math.abs(System.nanoTime()));
            ps.setString(2, ledgerNo);
            ps.setLong(3, MERCHANT_A);
            ps.setString(4, bizType);
            ps.setBigDecimal(5, changeAmount);
            ps.setString(6, refNo);
            ps.executeUpdate();
        }
    }

    /** 插垫付台账。 */
    private void insertAdvance(String waybillNo, long merchantId, BigDecimal amount, String status)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO fn_advance_payment
                       (id, tenant_id, merchant_id, waybill_no, amount, status, occurred_at, billing_period)
                     VALUES (?, 1, ?, ?, ?, ?, NOW(3), ?)
                     """)) {
            ps.setLong(1, Math.abs(System.nanoTime()));
            ps.setLong(2, merchantId);
            ps.setString(3, waybillNo);
            ps.setBigDecimal(4, amount);
            ps.setString(5, status);
            ps.setString(6, PERIOD);
            ps.executeUpdate();
        }
    }

    /** 插结算批次。 */
    private void insertBatch(String stbNo, long billId, BigDecimal totalAmount, int waybillCount)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO fn_settle_batch
                       (id, tenant_id, stb_no, bill_id, carrier_code, period, waybill_count,
                        total_amount, status)
                     VALUES (?, 1, ?, ?, ?, ?, ?, ?, '待下发')
                     """)) {
            ps.setLong(1, Math.abs(System.nanoTime()));
            ps.setString(2, stbNo);
            ps.setLong(3, billId);
            ps.setString(4, CARRIER);
            ps.setString(5, PERIOD);
            ps.setInt(6, waybillCount);
            ps.setBigDecimal(7, totalAmount);
            ps.executeUpdate();
        }
    }

    /** 统计某个结论的笔数。 */
    private long count(List<ReconcileDiffRule.Result> results, HandleResult handleResult) {
        return results.stream().filter(r -> r.handleResult() == handleResult).count();
    }

    /** 查金额。 */
    private BigDecimal sqlDecimal(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getBigDecimal(1);
        }
    }

    /** 查计数。 */
    private long sqlLong(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    /** Flyway 重置并初始化 Schema。 */
    private static void prepareSchema() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(!disposable).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    /** 建连接池。 */
    private static HikariDataSource pool(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setPoolName("poc5-pool");
        return new HikariDataSource(config);
    }

    /** 打印统一格式的实验报告（便于从构建日志里 grep）。 */
    private void printReport(String step, String detail) {
        System.out.printf("[POC5] %s | %s%n", step, detail);
    }
}
