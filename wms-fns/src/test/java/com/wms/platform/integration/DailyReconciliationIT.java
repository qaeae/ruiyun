package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.fns.api.dto.ChannelReconcileFile;
import com.wms.platform.modules.fns.api.vo.DailyReconciliationVo;
import com.wms.platform.modules.fns.app.DailyReconciliationService;
import com.wms.platform.modules.fns.app.remote.InventoryInvariantClient;
import com.wms.platform.modules.fns.domain.DailyReconcileRule;
import com.wms.platform.poc.PocMysqlCondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日终勾稽四层集成测试（W4，03 API-FNS-07）。
 *
 * <p>验证的是**四种状态的区分是否正确**——这是本功能最容易被做错的地方：
 * <ul>
 *   <li>「一致」：差额 0；</li>
 *   <li>「有差异」：差额 ≠ 0 或超阈值单数 &gt; 0；</li>
 *   <li>「待接入」：缺通道/承运商侧数据，**根本没法对**（不能报成"有差异"，否则天天假告警）；</li>
 *   <li>「已处理」：人工确认后重跑**不被覆盖**（否则处理痕迹失去意义）。</li>
 * </ul>
 *
 * <p>运行方式：{@code mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=DailyReconciliationIT
 * -Dpoc.mysql.url=... -Dpoc.mysql.user=root -Dpoc.mysql.password=}
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest(classes = com.wms.platform.fns.FnsApplication.class)
@DisplayName("日终勾稽四层（真实库）")
class DailyReconciliationIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 业务日期。 */
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 13);

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
                    .withDatabaseName("wms_w4c").withUsername("poc").withPassword("poc");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("wms.payment.channel.mode", () -> "MOCK");
    }

    /** 数据访问。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 日终勾稽服务。 */
    @Autowired
    private DailyReconciliationService service;

    /** 库存服务客户端（跨服务调用在 IT 中打桩）。 */
    @MockitoBean
    private InventoryInvariantClient inventoryInvariantClient;

    /** 清理数据并造"平台侧有账"的基线。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"fn_daily_reconciliation", "fn_fee_order", "ac_merchant_account",
                "ac_ledger", "iv_sku", "iv_location"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
        TenantContext.set(TENANT, OPERATOR, Set.of(1L), null);
        // 资金层平台侧：商家 1 = 1000（余额 600 + 冻结 300 + 押金 100）
        jdbc.update("INSERT INTO ac_merchant_account (id, tenant_id, merchant_id, balance, freeze_amount, "
                + "deposit_amount, version, deleted) VALUES (7001, 1, 1, 600.0000, 300.0000, 100.0000, 0, 0)");
        // 业务层/三方层：两张运单各 100 元，费用单状态「已确认」
        insertFeeOrder(8001, "WB-1", "100.0000");
        insertFeeOrder(8002, "WB-2", "100.0000");
    }

    /** 清理上下文。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("四层齐跑：库存层一致；资金/业务/三方层在无对方数据时为「待接入」而非「有差异」")
    void layersWithoutCounterpartyDataAreWaitingNotDiff() {
        List<DailyReconciliationVo> results = service.run(BIZ_DATE, null);

        assertThat(results).hasSize(4);
        // 1. 库存层：无账无货 → 不变量全成立 → 一致（它不需要对方数据）
        DailyReconciliationVo inventory = layer(results, "INVENTORY");
        assertThat(inventory.status()).isEqualTo(DailyReconcileRule.STATUS_MATCH);
        assertThat(inventory.diffCount()).isZero();

        // 2. 资金层三方层：没有通道/承运商文件 → **待接入**（关键：不能报成有差异）
        assertThat(layer(results, "FUND").status()).isEqualTo(DailyReconcileRule.STATUS_WAITING_CHANNEL);
        assertThat(layer(results, "CARRIER").status()).isEqualTo(DailyReconcileRule.STATUS_WAITING_CHANNEL);

        // 3. 业务层：对方侧（资金流水）取自我们自己的表，无需通道文件 → 可以真对
        //    平台侧 200 元已确认费用 vs 资金流水扣减 0 → 有差异（如实反映"该扣的还没扣"）
        DailyReconciliationVo fee = layer(results, "FEE");
        assertThat(fee.status()).isEqualTo(DailyReconcileRule.STATUS_DIFF);
        assertThat(fee.platformAmount()).isEqualByComparingTo("200.0000");
        assertThat(fee.channelAmount()).isEqualByComparingTo("0.0000");
        assertThat(fee.diffAmount()).isEqualByComparingTo("200.0000");

        print("待接入 vs 有差异", "库存层一致；资金/三方层待接入（无对方数据）；业务层如实报有差异（200 未扣减）");
    }

    @Test
    @DisplayName("提供对方数据后：资金层一致、三方层一致；金额不符则如实报差异")
    void layersBecomeMatchedWhenCounterpartyDataProvided() {
        // 1. 资金层对方账与平台一致（1000）；三方层承运商账单与平台一致（各 100）
        ChannelReconcileFile matched = new ChannelReconcileFile(
                Map.of(1L, new BigDecimal("1000.0000")),
                Map.of("WB-1", new BigDecimal("100.0000"), "WB-2", new BigDecimal("100.0000")));
        List<DailyReconciliationVo> ok = service.run(BIZ_DATE, matched);

        assertThat(layer(ok, "FUND").status()).as("资金层逐商家一致").isEqualTo(DailyReconcileRule.STATUS_MATCH);
        assertThat(layer(ok, "CARRIER").status()).as("三方层逐单一致").isEqualTo(DailyReconcileRule.STATUS_MATCH);

        // 2. 换一份**不一致**的对方账：资金层少 50，三方层 WB-1 多收 30（30% > 20% 阈值）
        //    重跑同一天（幂等：uk_daily_layer，不产生第二条记录）
        ChannelReconcileFile mismatch = new ChannelReconcileFile(
                Map.of(1L, new BigDecimal("950.0000")),
                Map.of("WB-1", new BigDecimal("130.0000"), "WB-2", new BigDecimal("100.0000")));
        List<DailyReconciliationVo> bad = service.run(BIZ_DATE, mismatch);

        DailyReconciliationVo fund = layer(bad, "FUND");
        assertThat(fund.status()).isEqualTo(DailyReconcileRule.STATUS_DIFF);
        assertThat(fund.diffAmount()).isEqualByComparingTo("50.0000");
        assertThat(fund.detailJson()).contains("商家 1");

        DailyReconciliationVo carrier = layer(bad, "CARRIER");
        assertThat(carrier.status()).isEqualTo(DailyReconcileRule.STATUS_DIFF);
        assertThat(carrier.diffCount()).as("WB-1 差异 30 元 > 20% → 计入差异").isEqualTo(1);
        assertThat(carrier.detailJson()).contains("WB-1");

        assertThat(count("SELECT COUNT(*) FROM fn_daily_reconciliation")).as("同一天同层只有一条记录")
                .isEqualTo(4);

        print("一致与差异", "对方账一致 → 资金/三方层一致；对方账不符（少 50 / 多收 30% ）→ 如实报差异且不重复建记录");
    }

    @Test
    @DisplayName("库存层不变量被破坏时如实报差异（复用库存域的校验，不重复实现）")
    void inventoryLayerDetectsInvariantViolation() {
        // 造出违反不变量 2 的数据：SKU 账面 100，但库位明细只有 97（差 3）
        jdbc.update("INSERT INTO iv_sku (id, tenant_id, warehouse_id, merchant_id, sku_id, physical_qty, "
                + "available_qty, reserved_qty, deleted) VALUES (9001, 1, 1, 1, 3001, 100.0000, 100.0000, 0, 0)");
        jdbc.update("INSERT INTO iv_location (id, tenant_id, warehouse_id, merchant_id, sku_id, location_id, "
                + "physical_qty, allocated_qty, deleted) VALUES (9002, 1, 1, 1, 3001, 4001, 97.0000, 0, 0)");

        // 桩：让**远端**报告 1 条违反。
        // 说明（拆服务后的正确切分）：本 IT 验证的是"fns 收到违反后如何反应"，
        // 而"远端能否真的从不变量里发现问题"由**库存服务自己的 IT** 负责——
        // 在财务侧的 IT 里连数据库去造违反、再断言远端能发现，等于把对方的逻辑测了两遍。
        org.mockito.Mockito.when(inventoryInvariantClient.describeViolations())
                .thenReturn(java.util.List.of("不变量2：库位合计=SKU实物（差异 3 条）"));

        List<DailyReconciliationVo> results = service.run(BIZ_DATE, null);
        DailyReconciliationVo inventory = layer(results, "INVENTORY");

        assertThat(inventory.status()).as("远端报违反 → 库存层有差异").isEqualTo(DailyReconcileRule.STATUS_DIFF);
        assertThat(inventory.diffCount()).as("至少 1 项不变量不成立").isGreaterThanOrEqualTo(1);
        assertThat(inventory.detailJson()).contains("不变量2");

        print("库存层", "远端报 1 条违反 → 库存层如实报差异（明细沿用远端口径）");
    }

    @Test
    @DisplayName("差异标记已处理：须填说明；处理后再重跑**不被覆盖**（人工结论要留住）")
    void handledLayerIsNotOverwrittenByRerun() {
        // 1. 先跑出业务层差异（平台 200 vs 扣减 0）
        List<DailyReconciliationVo> first = service.run(BIZ_DATE, null);
        DailyReconciliationVo fee = layer(first, "FEE");
        assertThat(fee.status()).isEqualTo(DailyReconcileRule.STATUS_DIFF);

        // 2. 不填说明 → 拒绝（否则无法追溯当时为什么认为没问题）
        assertThatThrownBy(() -> service.handle(fee.id(), "  "))
                .isInstanceOf(BizException.class).hasMessageContaining("必须填写说明");

        // 3. 标记已处理
        service.handle(fee.id(), "资金流水域未落地，本月费用尚未执行扣减，已与财务确认");
        assertThat(layer(service.query(BIZ_DATE), "FEE").status()).isEqualTo(DailyReconcileRule.STATUS_HANDLED);

        // 4. 重复标记 → 拒绝
        assertThatThrownBy(() -> service.handle(fee.id(), "再确认一次"))
                .isInstanceOf(BizException.class).hasMessageContaining("已处理");

        // 5. **重跑不覆盖已处理结论**（否则人工劳动白费、处理痕迹失去意义）
        service.run(BIZ_DATE, null);
        DailyReconciliationVo afterRerun = layer(service.query(BIZ_DATE), "FEE");
        assertThat(afterRerun.status()).as("已处理的层不被重跑覆盖").isEqualTo(DailyReconcileRule.STATUS_HANDLED);

        print("已处理", "未填说明拒绝 → 标记已处理 → 重复标记拒绝 → 重跑不覆盖人工结论");
    }

    @Test
    @DisplayName("非法层名与空日期报错（口径校验）")
    void inputValidation() {
        assertThatThrownBy(() -> service.run(null, null))
                .isInstanceOf(BizException.class).hasMessageContaining("业务日期不能为空");
        assertThatThrownBy(() -> com.wms.platform.modules.fns.domain.DailyReconcileLayer.of("随便什么层"))
                .isInstanceOf(BizException.class).hasMessageContaining("非法的勾稽层");

        print("入参校验", "空日期与非法层名均拒绝");
    }

    // ===================== 工具 =====================

    /**
     * 按层取结果。
     *
     * @param results 结果列表
     * @param layer   层枚举名
     * @return 该层结果
     */
    private DailyReconciliationVo layer(List<DailyReconciliationVo> results, String layer) {
        return results.stream().filter(vo -> layer.equals(vo.layer())).findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少层：" + layer));
    }

    /**
     * 造费用单（当日、已确认）。
     *
     * @param bizId     业务 ID
     * @param waybillNo 运单号
     * @param amount    金额
     */
    private void insertFeeOrder(long bizId, String waybillNo, String amount) {
        jdbc.update("INSERT INTO fn_fee_order (id, tenant_id, fee_no, merchant_id, warehouse_id, fee_type, biz_type, "
                        + "biz_id, biz_no, amount, billing_period, status, billed_at, deleted) "
                        + "VALUES (?, 1, ?, 1, 1, 'LOGISTICS', 'WAYBILL', ?, ?, ?, '202610', '已确认', ?, 0)",
                bizId, "FEE-" + bizId, bizId, waybillNo, new BigDecimal(amount),
                LocalDateTime.of(2026, 10, 13, 10, 0));
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
        System.out.printf("[W4-DAILY] %s | %s%n", step, detail);
    }
}
