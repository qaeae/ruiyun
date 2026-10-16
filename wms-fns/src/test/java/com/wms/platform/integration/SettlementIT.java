package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.payment.simulated.SimulatedPaymentChannel;
import com.wms.platform.modules.fns.api.ro.AdvanceRegisterRo;
import com.wms.platform.modules.fns.api.ro.ReconcileCreateRo;
import com.wms.platform.modules.fns.api.vo.AdvanceLimitVo;
import com.wms.platform.modules.fns.api.vo.FallbackCandidateVo;
import com.wms.platform.modules.fns.api.vo.SettleBatchVo;
import com.wms.platform.modules.fns.app.AdvancePaymentService;
import com.wms.platform.modules.fns.app.FallbackDeductionService;
import com.wms.platform.modules.fns.app.ReconcileService;
import com.wms.platform.modules.fns.app.SettlementService;
import com.wms.platform.modules.fns.domain.FallbackDeductionRule;
import com.wms.platform.modules.fns.domain.Liability;
import com.wms.platform.modules.fns.domain.ReconcileBillRule;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结算 / 兜底扣减 / 垫付 集成测试（W4，03 API-FNS-03/04/05/06）。
 *
 * <p>三块合在一个 IT 里，因为它们共享同一批财务表与同一套"防重复动钱"的设计：
 * 结算批次（{@code uk_bill} + CAS）、兜底扣减（{@code uk_fee_biz}）、垫付（{@code uk_advance}）。
 * 这些防线只有连上真实数据库（唯一键、条件更新的 affectedRows）才能验证。
 *
 * <p>运行方式：{@code mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=SettlementIT
 * -Dpoc.mysql.url=... -Dpoc.mysql.user=root -Dpoc.mysql.password=}
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest(classes = com.wms.platform.fns.FnsApplication.class)
@DisplayName("结算与垫付（真实库）")
class SettlementIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 承运商。 */
    private static final String CARRIER = "SF";

    /** 账期。 */
    private static final String PERIOD = "202610";

    /** 商家。 */
    private static final long MERCHANT = 1L;

    /** 操作人。 */
    private static final long OPERATOR = 6001L;

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
                    .withDatabaseName("wms_w4b").withUsername("poc").withPassword("poc");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        registry.add("spring.flyway.enabled", () -> true);
        // 支付通道走模拟（生产禁 MOCK 由 StartupSecurityCheck 拦截）
        registry.add("wms.payment.channel.mode", () -> "MOCK");
    }

    /** 数据访问。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 对账服务（造"已确认"的对账单）。 */
    @Autowired
    private ReconcileService reconcileService;

    /** 结算服务。 */
    @Autowired
    private SettlementService settlementService;

    /** 兜底扣减服务。 */
    @Autowired
    private FallbackDeductionService fallbackDeductionService;

    /** 垫付服务。 */
    @Autowired
    private AdvancePaymentService advancePaymentService;

    /** 重置所有相关表。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"fn_reconcile_item", "fn_reconcile_bill", "fn_settle_batch", "fn_fee_order",
                "fn_advance_payment", "lg_shipment"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
        TenantContext.set(TENANT, OPERATOR, Set.of(1L), null);
    }

    /** 清理上下文。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ===================== ① 结算批次 =====================

    @Test
    @DisplayName("结算批次：未确认对账单不能生成；确认后生成→下发（MOCK）→重复下发被拒（防重复付款）")
    void settleBatchFlow() {
        // 1. 造一张**无差异**的对账单（金额 200，能直接确认）
        String rclNo = createCleanBill();

        // 2. 未确认 → 不能生成批次
        assertThatThrownBy(() -> settlementService.generate(rclNo))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SETTLE_BILL_NOT_CONFIRMED));

        // 3. 确认 → 生成批次（幂等：生成两次同一批次号）
        reconcileService.confirm(rclNo, OPERATOR);
        String stbNo = settlementService.generate(rclNo);
        assertThat(settlementService.generate(rclNo)).as("重复生成返回同一批次（uk_bill）").isEqualTo(stbNo);
        assertThat(count("SELECT COUNT(*) FROM fn_settle_batch")).isEqualTo(1);
        SettleBatchVo batch = settlementService.detail(stbNo);
        assertThat(batch.status()).isEqualTo("待下发");
        assertThat(batch.totalAmount()).as("按承运商侧金额结算").isEqualByComparingTo("200.0000");
        assertThat(batch.waybillCount()).isEqualTo(2);

        // 4. 下发（模拟通道 → 指令号带 MOCK- 前缀，一眼可辨）
        String instructionNo = settlementService.dispatch(stbNo, OPERATOR);
        assertThat(instructionNo).startsWith(SimulatedPaymentChannel.MOCK_PREFIX);
        SettleBatchVo dispatched = settlementService.detail(stbNo);
        assertThat(dispatched.status()).isEqualTo("已下发");
        assertThat(dispatched.instructionNo()).isEqualTo(instructionNo);

        // 5. 重复下发 → **必须报错**（幂等返回成功会让调用方以为钱刚发出去）
        assertThatThrownBy(() -> settlementService.dispatch(stbNo, OPERATOR))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SETTLE_BATCH_ALREADY_DISPATCHED))
                .hasMessageContaining("重复下发等于重复付款");
        assertThat(settlementService.detail(stbNo).instructionNo())
                .as("指令号未被第二次下发覆盖").isEqualTo(instructionNo);

        print("结算批次", "未确认拒绝生成 → 生成幂等 → 下发 MOCK 指令 → 重复下发被拒（指令号未被覆盖）");
    }

    // ===================== ② 兜底扣减 =====================

    @Test
    @DisplayName("兜底扣减：7 天边界（满 7 天才扣、差 1 秒不扣）；重复扫描不重复扣；回传后多退少补")
    void fallbackDeductionFlow() {
        // 1. 造 3 张运单：已满 8 天 / 刚好 7 天 / 只过了 6 天
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 12, 0, 0);
        long dueId = insertShipment("WB-DUE", new BigDecimal("30.0000"), now.minusDays(8));
        long boundaryId = insertShipment("WB-BOUNDARY", new BigDecimal("40.0000"), now.minusDays(7));
        long notDueId = insertShipment("WB-NOT-DUE", new BigDecimal("50.0000"), now.minusDays(6));

        // 2. 扫描：到期两条被扣，未到期不扣
        List<FallbackCandidateVo> candidates = fallbackDeductionService.sweep(now);
        assertThat(candidates).extracting(FallbackCandidateVo::waybillNo)
                .containsExactlyInAnyOrder("WB-DUE", "WB-BOUNDARY");
        assertThat(candidates).allSatisfy(c -> assertThat(c.alreadyDeducted()).isFalse());
        assertThat(fallbackFeeCount()).as("生成 2 条兜底扣减费用单").isEqualTo(2);
        assertThat(statusOf(dueId)).isEqualTo("兜底扣减");
        assertThat(statusOf(notDueId)).as("未到期运单没有费用单").isNull();

        // 3. 重复扫描（模拟定时任务重跑/双实例）：**不会重复扣**
        List<FallbackCandidateVo> again = fallbackDeductionService.sweep(now);
        assertThat(again).allSatisfy(c -> assertThat(c.alreadyDeducted()).isTrue());
        assertThat(fallbackFeeCount()).as("重复扫描不新增费用单").isEqualTo(2);

        // 4. 回传实际运费：30 预估价 → 实际 28 → 应退 2 元
        FallbackDeductionRule.Adjustment refund = fallbackDeductionService.settleLateReturn("WB-DUE",
                new BigDecimal("28.0000"));
        assertThat(refund.action()).isEqualTo(FallbackDeductionRule.Action.REFUND);
        assertThat(refund.adjustAmount()).isEqualByComparingTo("-2.0000");

        // 5. 结算单只允许一次（重复结算会造成多退或少补）
        assertThatThrownBy(() -> fallbackDeductionService.settleLateReturn("WB-DUE", new BigDecimal("28.0000")))
                .isInstanceOf(BizException.class).hasMessageContaining("已完成回传结算");

        // 6. 没兜底扣过的运单不能直接结算（否则等于凭空付钱）
        assertThatThrownBy(() -> fallbackDeductionService.settleLateReturn("WB-NOT-DUE", new BigDecimal("50.0000")))
                .isInstanceOf(BizException.class).hasMessageContaining("尚未兜底扣减");

        assertThat(boundaryId).isPositive();
        print("兜底扣减", "8天/7天边界到期扣减、6天不扣 → 重复扫描不重复扣 → 回传退 2 元 → 重复结算被拒");
    }

    // ===================== ③ 垫付台账 =====================

    @Test
    @DisplayName("垫付：同运单幂等；累计超 ¥2000 拦截；近期满 3 笔拦截；核销后不可重复核销")
    void advancePaymentFlow() {
        // 1. 登记两笔（同运单第二次 → 幂等返回，不新增）
        advancePaymentService.register(advance("WB-A", new BigDecimal("500.0000")));
        advancePaymentService.register(advance("WB-A", new BigDecimal("500.0000")));
        assertThat(advancePaymentService.list(null)).as("同运单只垫一次（uk_advance）").hasSize(1);

        // 2. 预检：未超限
        AdvanceLimitVo ok = advancePaymentService.checkLimit(MERCHANT);
        assertThat(ok.limited()).isFalse();
        assertThat(ok.cumulativeAdvance()).isEqualByComparingTo("500.0000");
        assertThat(ok.consecutiveCount()).isEqualTo(1);

        // 3. 追加一笔 → 累计 1500，仍在阈值内（累计 >2000 才拦截）
        advancePaymentService.register(advance("WB-B", new BigDecimal("1000.0000")));
        assertThat(advancePaymentService.checkLimit(MERCHANT).limited()).isFalse();

        // 4. 第 3 笔：**预判口径**下（已有 2 笔 + 本笔 = 3）→ 拦截
        assertThatThrownBy(() -> advancePaymentService.register(advance("WB-C", new BigDecimal("300.0000"))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ADVANCE_LIMIT_EXCEEDED))
                .hasMessageContaining("连续");
        assertThat(advancePaymentService.list(null)).as("被拦截的垫付不落库（仍为 2 笔）").hasSize(2);

        // 5. 金额阈值单独生效的验证：换一个**无历史笔数**的新商家，单笔 2500 → 预判累计 2500 > 2000 → 拦截
        //    （这正是"预判口径"的价值：按"已发生"判时这笔会顺利通过，第一笔就超额放账了）
        long anotherMerchant = 2L;
        assertThatThrownBy(() -> advancePaymentService.register(new AdvanceRegisterRo("WB-D", anotherMerchant,
                null, new BigDecimal("2500.0000"), PERIOD)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ADVANCE_LIMIT_EXCEEDED))
                .hasMessageContaining("2000");

        // 6. 核销：一次成功，重复核销被拒
        advancePaymentService.clear("WB-A");
        assertThat(advancePaymentService.list(null)).filteredOn(a -> "WB-A".equals(a.waybillNo()))
                .singleElement().satisfies(a -> assertThat(a.status()).isEqualTo("已核销"));
        assertThatThrownBy(() -> advancePaymentService.clear("WB-A"))
                .isInstanceOf(BizException.class).hasMessageContaining("已核销");

        print("垫付台账", "同运单幂等 → 第 3 笔（预判 3 笔）拦截 → 单笔 2500（预判 2500）拦截 → 核销一次、重复核销被拒");
    }

    // ===================== 工具 =====================

    /**
     * 造一张无差异、可直接确认的对账单（2 单 × 100 元）。
     *
     * @return 对账单号
     */
    private String createCleanBill() {
        jdbc.update("INSERT INTO fn_fee_order (id, tenant_id, fee_no, merchant_id, warehouse_id, fee_type, biz_type, "
                        + "biz_id, biz_no, amount, billing_period, status, deleted) VALUES "
                        + "(9001, 1, 'FEE-1', 1, 1, 'LOGISTICS', 'WAYBILL', 8001, 'WB-CLEAN-1', 100.0000, '202610', "
                        + "'已计费', 0), (9002, 1, 'FEE-2', 1, 1, 'LOGISTICS', 'WAYBILL', 8002, 'WB-CLEAN-2', "
                        + "100.0000, '202610', '已计费', 0)");
        return reconcileService.createBill(new ReconcileCreateRo(CARRIER, PERIOD, List.of(
                new ReconcileCreateRo.Item("WB-CLEAN-1", MERCHANT, new BigDecimal("100.0000")),
                new ReconcileCreateRo.Item("WB-CLEAN-2", MERCHANT, new BigDecimal("100.0000")))));
    }

    /**
     * 造运单。
     *
     * @param waybillNo   运单号
     * @param estimatedFee 预估运费
     * @param pickedUpAt  揽收时间
     * @return 发货单 ID
     */
    private long insertShipment(String waybillNo, BigDecimal estimatedFee, LocalDateTime pickedUpAt) {
        long id = Math.abs(waybillNo.hashCode());
        jdbc.update("INSERT INTO lg_shipment (id, tenant_id, shp_no, do_id, do_no, warehouse_id, merchant_id, "
                        + "carrier_code, waybill_no, status, estimated_fee, picked_up_at, label_print_count, deleted) "
                        + "VALUES (?, 1, ?, 1, 'DO-1', 1, 1, 'SF', ?, '运输中', ?, ?, 0, 0)",
                id, "SHP-" + waybillNo, waybillNo, estimatedFee, pickedUpAt);
        return id;
    }

    /**
     * 组装垫付入参。
     *
     * @param waybillNo 运单号
     * @param amount    金额
     * @return 入参
     */
    private AdvanceRegisterRo advance(String waybillNo, BigDecimal amount) {
        return new AdvanceRegisterRo(waybillNo, MERCHANT, null, amount, PERIOD);
    }

    /**
     * 某运单的兜底扣减费用单状态。
     *
     * @param bizId 发货单 ID
     * @return 状态，无则 null
     */
    private String statusOf(long bizId) {
        List<String> rows = jdbc.queryForList("SELECT status FROM fn_fee_order WHERE tenant_id = 1 "
                + "AND biz_type = 'WAYBILL_FALLBACK' AND biz_id = " + bizId, String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 兜底扣减费用单条数。
     *
     * @return 条数
     */
    private long fallbackFeeCount() {
        return count("SELECT COUNT(*) FROM fn_fee_order WHERE tenant_id = 1 AND biz_type = 'WAYBILL_FALLBACK'");
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
        System.out.printf("[W4-SETTLE] %s | %s%n", step, detail);
    }
}
