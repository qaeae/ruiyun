package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.au.app.ApprovalService;
import com.wms.platform.common.approval.ApprovalBizType;
import com.wms.platform.modules.au.domain.ApprovalRule;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.StockOpCommand;
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
 * 双人复核集成测试（W4，03 API-AUTH-04/05、`02` M2-05 复核矩阵）。
 *
 * <p>验证的是**复核闭环**：提交 → 自批被拒 → 他人复核通过 → **副作用真的执行了** →
 * 重复复核被拒 → 驳回路径无副作用 → 内容被改动则阻断执行。
 *
 * <p>为什么必须连真实库：`uk_approval_biz`（一事一单）、CAS 状态更新（并发复核）、
 * 以及"通过后由执行器改库存"这三件事都只有在真实事务与唯一键下才成立。
 *
 * <p>运行方式：{@code mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=ApprovalIT
 * -Dpoc.mysql.url=... -Dpoc.mysql.user=root -Dpoc.mysql.password=}
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest
@DisplayName("双人复核（真实库）")
class ApprovalIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 仓库。 */
    private static final long WAREHOUSE = 1L;

    /** 商家。 */
    private static final long MERCHANT = 1L;

    /** 商品。 */
    private static final long SKU = 3001L;

    /** 库位。 */
    private static final long LOCATION = 4001L;

    /** 发起人（仓管）。 */
    private static final long APPLICANT = 5001L;

    /** 复核人（主管）。 */
    private static final long REVIEWER = 5002L;

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
                    .withDatabaseName("wms_w4d").withUsername("poc").withPassword("poc");
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

    /** 复核服务。 */
    @Autowired
    private ApprovalService approvalService;

    /** 库存服务（造初始库存 + 校验调整结果）。 */
    @Autowired
    private InventoryService inventoryService;

    /** 每个用例重置数据并造 100 件初始库存。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"au_approval", "iv_movement", "iv_location", "iv_sku",
                "pf_idempotent"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
        TenantContext.set(TENANT, APPLICANT, Set.of(WAREHOUSE), null);
        inventoryService.receive(new StockOpCommand(WAREHOUSE, MERCHANT, SKU, new BigDecimal("100.0000"),
                "ASN", 1L, "ASN-1", "SEED-APPROVAL", LOCATION, APPLICANT, null));
    }

    /** 清理上下文。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("闭环：提交 → 自批被拒(80002) → 他人复核 → 库存真的被调整 → 重复复核被拒")
    void fullApprovalFlow() {
        // 1. 发起人提交"库存调整 −5"（内容快照就是执行参数）
        String payload = "{\"warehouseId\":1,\"merchantId\":1,\"skuId\":3001,\"locationId\":4001,\"qty\":-5}";
        String approvalNo = approvalService.submit(ApprovalBizType.STOCK_ADJUST, 9901L, "DO-ADJ-1", WAREHOUSE,
                MERCHANT, new BigDecimal("5"), payload, "盘亏 5 件，已核对现场");
        assertThat(approvalNo).startsWith("AP");
        assertThat(physical()).as("提交阶段不得改库存（只是申请）").isEqualByComparingTo("100.0000");

        // 2. 发起人自批 → 拒绝
        Long id = idOf(approvalNo);
        assertThatThrownBy(() -> approvalService.approve(id, APPLICANT))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVER_MUST_DIFFER));
        assertThat(statusOf(id)).as("自批失败不得改变状态").isEqualTo(ApprovalRule.STATUS_PENDING);
        assertThat(physical()).isEqualByComparingTo("100.0000");

        // 3. 他人复核通过 → 副作用执行（库存 100 → 95）
        approvalService.approve(id, REVIEWER);
        assertThat(statusOf(id)).isEqualTo(ApprovalRule.STATUS_APPROVED);
        assertThat(executedOf(id)).as("副作用已执行").isEqualTo(1);
        assertThat(physical()).as("库存确实被调整了（复核不是走过场）").isEqualByComparingTo("95.0000");
        assertThat(inventoryService.checkInvariants()).as("调整后不变量仍成立")
                .allSatisfy(result -> assertThat(result.passed()).isTrue());

        // 4. 重复复核 → 拒绝（且不会二次扣减）
        assertThatThrownBy(() -> approvalService.approve(id, REVIEWER))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVAL_NOT_PENDING));
        assertThat(physical()).as("库存只被调整一次").isEqualByComparingTo("95.0000");

        print("复核闭环", "提交(100) → 自批被拒 → 他人复核 → 库存 95 且已执行标记；重复复核被拒且不二次扣减");
    }

    @Test
    @DisplayName("一事一单：同一业务对象重复提交返回同一单号（uk_approval_biz）；已处理的不可再提交")
    void oneApprovalPerBizObject() {
        String payload = "{\"warehouseId\":1,\"merchantId\":1,\"skuId\":3001,\"locationId\":4001,\"qty\":-1}";
        String first = approvalService.submit(ApprovalBizType.STOCK_ADJUST, 9902L, "DO-ADJ-2", WAREHOUSE, MERCHANT,
                new BigDecimal("1"), payload, "第一次申请");
        String second = approvalService.submit(ApprovalBizType.STOCK_ADJUST, 9902L, "DO-ADJ-2", WAREHOUSE, MERCHANT,
                new BigDecimal("1"), payload, "重复提交");
        assertThat(second).as("重复提交返回同一单号").isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM au_approval")).isEqualTo(1);

        // 复核通过后再提交 → 拒绝（同一件事不能有两份结论）
        approvalService.approve(idOf(first), REVIEWER);
        assertThatThrownBy(() -> approvalService.submit(ApprovalBizType.STOCK_ADJUST, 9902L, "DO-ADJ-2", WAREHOUSE,
                MERCHANT, new BigDecimal("1"), payload, "又想调一次"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVAL_ALREADY_EXISTS));

        print("一事一单", "重复提交返回同单号；已通过的不可再提交（避免两份结论）");
    }

    @Test
    @DisplayName("内容被改动 → 阻断执行（80006）：批的是 −5，执行前被改成 −50 也改不动库存")
    void tamperedPayloadIsBlocked() {
        String payload = "{\"warehouseId\":1,\"merchantId\":1,\"skuId\":3001,\"locationId\":4001,\"qty\":-5}";
        String approvalNo = approvalService.submit(ApprovalBizType.STOCK_ADJUST, 9903L, "DO-ADJ-3", WAREHOUSE,
                MERCHANT, new BigDecimal("5"), payload, "盘亏 5 件");
        Long id = idOf(approvalNo);

        // 模拟"审批后有人偷改内容"：直接改库（真实攻击面是绕过接口改数据）
        jdbc.update("UPDATE au_approval SET payload_json = ? WHERE id = ?",
                "{\"warehouseId\":1,\"merchantId\":1,\"skuId\":3001,\"locationId\":4001,\"qty\":-50}", id);

        // 复核 → 指纹不匹配，阻断执行，且状态回滚（不出现"已通过但没执行"）
        assertThatThrownBy(() -> approvalService.approve(id, REVIEWER))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVAL_PAYLOAD_CHANGED))
                .hasMessageContaining("已阻断执行");
        assertThat(statusOf(id)).as("整事务回滚：状态不得停在「已通过」")
                .isEqualTo(ApprovalRule.STATUS_PENDING);
        assertThat(physical()).as("库存未被动过").isEqualByComparingTo("100.0000");

        print("防改内容", "改动内容后复核 → 80006 阻断，状态回滚到待复核，库存未变");
    }

    @Test
    @DisplayName("驳回：须填原因、无副作用；撤回仅发起人本人")
    void rejectAndWithdraw() {
        String payload = "{\"warehouseId\":1,\"merchantId\":1,\"skuId\":3001,\"locationId\":4001,\"qty\":-5}";
        String approvalNo = approvalService.submit(ApprovalBizType.STOCK_ADJUST, 9904L, "DO-ADJ-4", WAREHOUSE,
                MERCHANT, new BigDecimal("5"), payload, "盘亏 5 件");
        Long id = idOf(approvalNo);

        // 1. 不填原因 → 拒绝
        assertThatThrownBy(() -> approvalService.reject(id, "  ", REVIEWER))
                .isInstanceOf(BizException.class).hasMessageContaining("必须填写原因");

        // 2. 撤回：非发起人不可
        assertThatThrownBy(() -> approvalService.withdraw(id, REVIEWER))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));

        // 3. 正常驳回 → 终态、无副作用
        approvalService.reject(id, "与现场交接记录不符，请重盘", REVIEWER);
        assertThat(statusOf(id)).isEqualTo(ApprovalRule.STATUS_REJECTED);
        assertThat(executedOf(id)).isZero();
        assertThat(physical()).as("驳回不产生任何副作用").isEqualByComparingTo("100.0000");

        print("驳回与撤回", "驳回必填原因、无副作用；非发起人撤回被拒");
    }

    @Test
    @DisplayName("未接入执行器的业务类型不允许复核通过（80007）：避免「批了但什么都没发生」")
    void missingHandlerIsRejected() {
        String approvalNo = approvalService.submit(ApprovalBizType.DEPOSIT_REFUND, 9905L, "DEP-1", WAREHOUSE,
                MERCHANT, new BigDecimal("500"), "{\"amount\":500}", "退押金");
        Long id = idOf(approvalNo);

        assertThatThrownBy(() -> approvalService.approve(id, REVIEWER))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVAL_NO_HANDLER))
                .hasMessageContaining("尚未接入执行处理器");
        assertThat(statusOf(id)).as("无执行器 → 状态回滚，不放行").isEqualTo(ApprovalRule.STATUS_PENDING);

        print("无执行器", "押金退还尚未接入处理器 → 拒绝通过（防止空批单）");
    }

    // ===================== 工具 =====================

    /**
     * 按单号取 ID。
     *
     * @param approvalNo 单号
     * @return ID
     */
    private Long idOf(String approvalNo) {
        return jdbc.queryForObject("SELECT id FROM au_approval WHERE approval_no = ?", Long.class, approvalNo);
    }

    /**
     * 取状态。
     *
     * @param id ID
     * @return 状态
     */
    private String statusOf(Long id) {
        return jdbc.queryForObject("SELECT status FROM au_approval WHERE id = ?", String.class, id);
    }

    /**
     * 取执行标记。
     *
     * @param id ID
     * @return 0/1
     */
    private Integer executedOf(Long id) {
        return jdbc.queryForObject("SELECT executed FROM au_approval WHERE id = ?", Integer.class, id);
    }

    /**
     * 当前 SKU 实物库存。
     *
     * @return 数量
     */
    private BigDecimal physical() {
        List<BigDecimal> rows = jdbc.queryForList("SELECT physical_qty FROM iv_sku WHERE tenant_id = 1 "
                + "AND sku_id = " + SKU, BigDecimal.class);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
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
        System.out.printf("[W4-APPROVAL] %s | %s%n", step, detail);
    }
}
