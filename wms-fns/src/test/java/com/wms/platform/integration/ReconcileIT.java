package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.fns.api.ro.ReconcileCreateRo;
import com.wms.platform.modules.fns.api.vo.ReconcileItemVo;
import com.wms.platform.modules.fns.api.vo.ReconcileVo;
import com.wms.platform.modules.fns.app.ReconcileService;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对账链路集成测试（W4，03 API-FNS-01/02）。
 *
 * <p>阈值口径于 2026-10-14 由张三确认照 PoC-5 默认执行：
 * ≤1 元**或** ≤1% 自动平账；&gt;1% 且 ≤20% 挂账待处理；&gt;20% 强制人工；分母 = 平台费用。
 * 本测试在真实库上验证这套口径端到端落地（含平台侧漏计费、幂等、确认门槛）。
 *
 * <p>运行方式：{@code mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=ReconcileIT
 * -Dpoc.mysql.url=... -Dpoc.mysql.user=root -Dpoc.mysql.password=}
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest(classes = com.wms.platform.fns.FnsApplication.class)
@DisplayName("对账链路（真实库）")
class ReconcileIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 承运商。 */
    private static final String CARRIER = "SF";

    /** 账期。 */
    private static final String PERIOD = "202610";

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
                    .withDatabaseName("wms_w4").withUsername("poc").withPassword("poc");
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

    /** 对账服务。 */
    @Autowired
    private ReconcileService reconcileService;

    /** 每个用例重置财务数据并准备 4 张运单的平台侧费用单。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"fn_reconcile_item", "fn_reconcile_bill", "fn_fee_order"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
        TenantContext.set(TENANT, OPERATOR, Set.of(1L), null);
        // 平台侧物流运费费用单（对账的平台侧数据源）
        insertFeeOrder("WB-NO-DIFF", "100.0000");      // 完全一致
        insertFeeOrder("WB-SMALL-DIFF", "100.0000");   // 差异 0.5 元 → 自动平账
        insertFeeOrder("WB-MID-DIFF", "100.0000");     // 差异 5 元（5%） → 挂账待处理
        insertFeeOrder("WB-BIG-DIFF", "100.0000");     // 差异 30 元（30%） → 强制人工
        // WB-NO-FEE 故意不建费用单 → 平台侧漏计费
    }

    /** 清理上下文。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("三档分流 + 平台漏计费：差异分类、计数与状态都按确认口径落地")
    void tieringAndClassification() {
        // 1. 导入承运商对账文件（5 条：一致/0.5 元/5 元/30 元/平台无费用）
        String rclNo = reconcileService.createBill(new ReconcileCreateRo(CARRIER, PERIOD, List.of(
                new ReconcileCreateRo.Item("WB-NO-DIFF", 1L, new BigDecimal("100.0000")),
                new ReconcileCreateRo.Item("WB-SMALL-DIFF", 1L, new BigDecimal("100.5000")),
                new ReconcileCreateRo.Item("WB-MID-DIFF", 1L, new BigDecimal("105.0000")),
                new ReconcileCreateRo.Item("WB-BIG-DIFF", 1L, new BigDecimal("130.0000")),
                new ReconcileCreateRo.Item("WB-NO-FEE", 1L, new BigDecimal("80.0000")))));

        ReconcileVo detail = reconcileService.detail(rclNo);

        // 2. 条数与金额
        assertThat(detail.totalWaybills()).isEqualTo(5);
        assertThat(detail.carrierAmount()).isEqualByComparingTo("515.5000");
        assertThat(detail.platformAmount()).as("平台侧只有 4 条有费用单").isEqualByComparingTo("400.0000");

        // 3. 自动平账 1 条（0.5 元 ≤1 元）；其余 4 条待处理
        assertThat(detail.autoCount()).as("WB-NO-DIFF（完全一致）+ WB-SMALL-DIFF（0.5 元 ≤1 元）均自动平账")
                .isEqualTo(2);
        assertThat(detail.manualCount()).as("5 元挂账 + 30 元人工 + 平台漏计费 = 3 条待处理").isEqualTo(3);
        assertThat(detail.status()).isEqualTo(ReconcileBillRule.STATUS_DIFF_OPEN);

        // 4. 逐条核对处理结果与差异类型
        assertThat(item(detail, "WB-NO-DIFF").handleResult()).isEqualTo("自动平账");
        assertThat(item(detail, "WB-NO-DIFF").diffType()).isEqualTo(ReconcileBillRule.DIFF_TYPE_NONE);
        assertThat(item(detail, "WB-SMALL-DIFF").handleResult()).isEqualTo("自动平账");
        assertThat(item(detail, "WB-MID-DIFF").handleResult()).isEqualTo("挂账待处理");
        assertThat(item(detail, "WB-MID-DIFF").diffType()).isEqualTo(ReconcileBillRule.DIFF_TYPE_CARRIER_HIGHER);
        assertThat(item(detail, "WB-BIG-DIFF").handleResult()).as("30% > 20% 强制人工").isEqualTo("人工处理");
        assertThat(item(detail, "WB-NO-FEE").diffType()).isEqualTo(ReconcileBillRule.DIFF_TYPE_PLATFORM_MISSING);
        assertThat(item(detail, "WB-NO-FEE").handleResult()).isEqualTo("人工处理");
        assertThat(item(detail, "WB-NO-FEE").liability()).as("平台漏计费 → 责任方为平台").isEqualTo("平台");

        print("三档分流", "5 条：自动平账 2（一致 + 0.5元）/ 挂账 1（5元）/ 人工 1（30元>20%）/ 平台漏计费 1；状态=差异待处理");
    }

    @Test
    @DisplayName("确认门槛：有未处理差异时不能确认；逐条判定责任方后才能确认")
    void confirmRequiresAllDiffsHandled() {
        String rclNo = reconcileService.createBill(new ReconcileCreateRo(CARRIER, PERIOD, List.of(
                new ReconcileCreateRo.Item("WB-NO-DIFF", 1L, new BigDecimal("100.0000")),
                new ReconcileCreateRo.Item("WB-BIG-DIFF", 1L, new BigDecimal("130.0000")))));

        // 1. 有未处理差异 → 拒绝确认（确认即认账，之后会付款）
        assertThatThrownBy(() -> reconcileService.confirm(rclNo, OPERATOR))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DIFF_EXCEED_THRESHOLD_NEED_MANUAL))
                .hasMessageContaining("仍有 1 条差异未处理");

        // 2. 人工处理：判定为物流承担（承运商多计 30 元）
        ReconcileVo detail = reconcileService.detail(rclNo);
        Long itemId = item(detail, "WB-BIG-DIFF").id();
        long open = reconcileService.handleItem(rclNo, itemId, Liability.LOGISTICS, "承运商多计重量，已与对方确认");
        assertThat(open).as("处理后无待处理差异").isZero();
        assertThat(reconcileService.detail(rclNo).status()).isEqualTo(ReconcileBillRule.STATUS_PENDING_CONFIRM);

        // 3. 重复处理同一条 → 拒绝（防"改到能过为止"）
        assertThatThrownBy(() -> reconcileService.handleItem(rclNo, itemId, Liability.MERCHANT, "再改一次"))
                .isInstanceOf(BizException.class).hasMessageContaining("不可重复处理");

        // 4. 现在可以确认
        reconcileService.confirm(rclNo, OPERATOR);
        ReconcileVo confirmed = reconcileService.detail(rclNo);
        assertThat(confirmed.status()).isEqualTo(ReconcileBillRule.STATUS_CONFIRMED);
        assertThat(count("SELECT COUNT(*) FROM fn_reconcile_bill WHERE status = '已确认'")).isEqualTo(1);

        // 5. 已确认后：不可重复确认、不可再改差异、不可重复导入该账期
        assertThatThrownBy(() -> reconcileService.confirm(rclNo, OPERATOR))
                .hasMessageContaining("不可重复确认");
        assertThatThrownBy(() -> reconcileService.handleItem(rclNo, itemId, Liability.PLATFORM, "事后改判"))
                .hasMessageContaining("已确认，不可再处理差异");
        assertThatThrownBy(() -> reconcileService.createBill(new ReconcileCreateRo(CARRIER, PERIOD,
                List.of(new ReconcileCreateRo.Item("WB-NO-DIFF", 1L, new BigDecimal("100.0000"))))))
                .hasMessageContaining("已确认，不可重复导入");

        print("确认门槛", "有差异时拒绝确认 → 判定物流承担 → 可确认 → 确认后三重锁定（重复确认/改判/重导入）");
    }

    @Test
    @DisplayName("生成幂等：同（承运商+账期）重复导入返回同一张对账单（uk_rcl），不会产生第二张")
    void createBillIsIdempotent() {
        ReconcileCreateRo ro = new ReconcileCreateRo(CARRIER, PERIOD,
                List.of(new ReconcileCreateRo.Item("WB-NO-DIFF", 1L, new BigDecimal("100.0000"))));

        String first = reconcileService.createBill(ro);
        String second = reconcileService.createBill(ro);

        assertThat(second).as("重复导入返回同一单号").isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM fn_reconcile_bill")).as("库中只有一张对账单").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM fn_reconcile_item")).isEqualTo(1);

        print("生成幂等", "重复导入返回同一单号，库中仍只有 1 张账单 / 1 条明细");
    }

    @Test
    @DisplayName("人工处理必须给责任方与说明（追责依据不能省）")
    void handleRequiresLiabilityAndRemark() {
        String rclNo = reconcileService.createBill(new ReconcileCreateRo(CARRIER, PERIOD,
                List.of(new ReconcileCreateRo.Item("WB-BIG-DIFF", 1L, new BigDecimal("130.0000")))));
        Long itemId = item(reconcileService.detail(rclNo), "WB-BIG-DIFF").id();

        assertThatThrownBy(() -> reconcileService.handleItem(rclNo, itemId, null, "说明"))
                .isInstanceOf(BizException.class).hasMessageContaining("必须判定责任方");
        assertThatThrownBy(() -> reconcileService.handleItem(rclNo, itemId, Liability.LOGISTICS, "  "))
                .isInstanceOf(BizException.class).hasMessageContaining("必须填写说明");
        // 失败的两条校验不得落库：差异仍处于待处理
        assertThat(reconcileService.detail(rclNo).manualCount()).as("校验失败不落库").isEqualTo(1);
        assertThat(item(reconcileService.detail(rclNo), "WB-BIG-DIFF").liability()).isNull();

        // 补齐合法入参后成功（责任方 + 说明齐全）
        long open = reconcileService.handleItem(rclNo, itemId, Liability.LOGISTICS, "承运商多计重量");
        assertThat(open).isZero();
        assertThat(item(reconcileService.detail(rclNo), "WB-BIG-DIFF").handleResult()).isEqualTo("责任方承担");
        assertThat(item(reconcileService.detail(rclNo), "WB-BIG-DIFF").liability()).isEqualTo("物流");

        print("人工处理校验", "责任方与说明缺一不可，校验失败不落库（待处理仍为 1）");
    }

    @Test
    @DisplayName("责任方反查：同时接受枚举名与中文标签，非法值拒绝（猜错等于替业务做决定）")
    void liabilityParsing() {
        assertThat(Liability.of("LOGISTICS")).isEqualTo(Liability.LOGISTICS);
        assertThat(Liability.of("物流")).isEqualTo(Liability.LOGISTICS);
        assertThat(Liability.of("平台")).isEqualTo(Liability.PLATFORM);
        assertThatThrownBy(() -> Liability.of("供应商")).isInstanceOf(BizException.class)
                .hasMessageContaining("非法的责任方");
        assertThatThrownBy(() -> Liability.of(null)).isInstanceOf(BizException.class);

        print("责任方解析", "枚举名/中文标签均可，非法值拒绝");
    }

    // ===================== 工具 =====================

    /**
     * 造平台侧物流运费费用单。
     *
     * @param waybillNo 运单号
     * @param amount    金额
     */
    private void insertFeeOrder(String waybillNo, String amount) {
        jdbc.update("INSERT INTO fn_fee_order (id, tenant_id, fee_no, merchant_id, warehouse_id, fee_type, biz_type, "
                        + "biz_id, biz_no, amount, billing_period, status, deleted) "
                        + "VALUES (?, 1, ?, 1, 1, 'LOGISTICS', 'WAYBILL', ?, ?, ?, ?, '已计费', 0)",
                Math.abs(waybillNo.hashCode()), "FEE-" + waybillNo, Math.abs(waybillNo.hashCode()), waybillNo,
                new BigDecimal(amount), PERIOD);
    }

    /**
     * 取某运单的对账明细。
     *
     * @param detail    对账单详情
     * @param waybillNo 运单号
     * @return 明细
     */
    private ReconcileItemVo item(ReconcileVo detail, String waybillNo) {
        return detail.items().stream().filter(it -> waybillNo.equals(it.waybillNo())).findFirst()
                .orElseThrow(() -> new IllegalStateException("明细不存在：" + waybillNo));
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
        System.out.printf("[W4-FNS] %s | %s%n", step, detail);
    }
}
