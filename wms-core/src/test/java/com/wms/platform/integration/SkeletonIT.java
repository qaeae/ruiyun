package com.wms.platform.integration;

import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.idempotent.IdempotentService;
import com.wms.platform.common.idempotent.RequestHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.wms.platform.poc.PocMysqlCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 骨架集成自检（需要 Docker；默认跳过）。
 *
 * <p>运行方式：{@code mvn -pl wms-core verify -Dit.skip=false}
 * （要求本机 Docker 可用；CI 上作为独立 job 运行）。
 *
 * <p>验证三件事，这三件恰好是"本地单测覆盖不到、但上线必炸"的部分：
 * <ol>
  *   <li>Flyway 能把 {@code 01} 的 35 张表**真的建出来**（DDL 与代码一致：V1 基线 28 + V2 {@code md_device} + V3 {@code pf_pda_conflict} + V4 主数据 5 张）；</li>
 *   <li>应用能连上真实 MySQL 完成完整上下文启动（连不上就不该发布）；</li>
 *   <li>幂等服务在真实库上完成 PROCEED → REPLAY → MISMATCH 三态裁决。</li>
 * </ol>
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkeletonIT {

    private static final int EXPECTED_TABLE_COUNT = 51;

    /** Testcontainers 容器（外部模式为 null）。 */
    private static MySQLContainer<?> container;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // 1. 外部一次性 MySQL 优先（本机验证用，不碰在用实例）
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("poc.mysql.user", "root"));
            registry.add("spring.datasource.password", () -> System.getProperty("poc.mysql.password", ""));
        } else {
            // 2. 其次 Testcontainers（与 production 一致：utf8mb4 + 东八区）
            container = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("wms_platform").withUsername("wms").withPassword("wms")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                            "--default-time-zone=+08:00");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        // 集成测试必须校验迁移脚本，不允许跳过
        registry.add("spring.flyway.validate-on-migrate", () -> true);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdempotentService idempotentService;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 自清理：本用例在**持久库**（外部 MySQL 模式）上复用固定幂等键，
     * 上一轮遗留的记录会让第一次 { begin} 直接返回 REPLAY。
     * Testcontainers 模式每次都是全新库，恰好掩盖了这个问题——
     * 2026-10-14 让该用例真正跑起来后才暴露。
     */
    @BeforeEach
    void cleanIdempotentRecords() {
        jdbcTemplate.update("DELETE FROM pf_idempotent WHERE tenant_id = 1 AND idem_key LIKE 'IT-%'");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Flyway 建表数与 01 的 DDL 一致（44 张 = V1 基线 28 + V2 md_device + V3 pf_pda_conflict + V4 主数据 5 张 + V5 箱规换算 + V6 RBAC 6 张 + V7 盘点 2 张）")
    void flywayShouldCreateAllTables() {
        // 排除 Flyway 自己的元数据表 flyway_schema_history：
        // 本机实测（MySQL 8.4.9 + Flyway V1/V2）库内共 30 张表，其中 29 张是业务表。
        // 早期版本断言的是"库内总表数 = 28"，一旦真跑起来必然多 1（少算了 md_device、或多算了 flyway 元数据表），
        // 这里改为显式排除元数据表，使断言表达真实意图。
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                Integer.class);
        assertThat(count).isEqualTo(EXPECTED_TABLE_COUNT);
        // 关键表存在性抽查：库存三态 / 冻结单 / 幂等 / 结算批次 / 设备档案
        for (String table : new String[]{"iv_sku", "iv_location", "iv_movement", "ac_fee_freeze", "md_device", "pf_pda_conflict",
                "md_merchant", "md_warehouse", "md_zone", "md_location", "md_sku", "md_sku_unit_conv", "au_user", "au_role", "au_permission", "au_user_role", "au_role_permission", "au_user_warehouse", "iv_stocktake", "iv_stocktake_item", "au_approval", "pf_scheduled_job", "pf_job_run", "ac_channel_order",
                "ac_ledger", "pf_idempotent", "fn_settle_batch", "fn_advance_payment", "fn_rate_card", "fn_rate_item", "lg_shipment_proof", "out_order", "lg_shipment"}) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                    Integer.class, table);
            assertThat(exists).as("表 %s 应存在", table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("真实库上三态库存不变量与 CHECK 约束生效（负向用例）")
    void inventoryConstraintsShouldRejectInvalidData() {
        // SKU 三态失衡：physical(10) != available(5) + reserved(4) → 必须被 ck_iv_sku_balance 拒绝
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO iv_sku(id, tenant_id, warehouse_id, merchant_id, sku_id, "
                                + "physical_qty, available_qty, reserved_qty) VALUES (1, 1, 1, 1, 1, 10, 5, 4)"))
                .hasMessageContaining("ck_iv_sku_balance");

        // 账户余额为负 → ck_acct_nonneg
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO ac_merchant_account(id, tenant_id, merchant_id, balance) VALUES (2, 1, 2, -1)"))
                .hasMessageContaining("ck_acct_nonneg");
    }

    @Test
    @DisplayName("幂等裁决在真实库上工作：PROCEED → REPLAY → MISMATCH")
    void idempotentDecisionOnRealDatabase() {
        TenantContext.set(1L, 1001L, Set.of(1L), "PC-TEST");
        String key = "IT-DO-CREATE-0001";
        String hash = RequestHasher.hash("{\"skuCode\":\"SKU-001\",\"qty\":\"2.0000\"}");

        assertThat(idempotentService.begin(key, "OUT_ORDER_CREATE", hash))
                .isEqualTo(IdempotentService.Decision.PROCEED);

        // 并发重入 / 未完成：同键同体在"处理中"状态应判定为 IN_PROGRESS，而不是重复执行
        assertThat(idempotentService.begin(key, "OUT_ORDER_CREATE", hash))
                .isEqualTo(IdempotentService.Decision.IN_PROGRESS);

        idempotentService.complete(key, "{\"doNo\":\"DO20261019000001\"}");
        assertThat(idempotentService.begin(key, "OUT_ORDER_CREATE", hash))
                .isEqualTo(IdempotentService.Decision.REPLAY);
        // 注意：assertThat(Optional).contains(x) 是"值相等"（OptionalAssert 语义），不是子串匹配；
        // 且 pf_idempotent.response_json 是 MySQL JSON 列，取回时会被规范化（":" 后带空格），
        // 故这里对字符串做子串断言。两处坑都是 2026-10-14 让该用例真正跑起来后才暴露的。
        assertThat(idempotentService.cachedResponse(key).orElseThrow()).contains("DO20261019000001");

        // 同键不同体：必须显式报冲突（409 + 10002），不得静默返回旧结果
        String otherHash = RequestHasher.hash("{\"skuCode\":\"SKU-001\",\"qty\":\"9.0000\"}");
        assertThat(idempotentService.begin(key, "OUT_ORDER_CREATE", otherHash))
                .isEqualTo(IdempotentService.Decision.MISMATCH);
    }

    @Test
    @DisplayName("HTTP 层：健康检查可用且带 traceId")
    void healthEndpointOverHttp() {
        String body = restTemplate.getForObject("/api/v1/health", String.class);
        assertThat(body).contains("\"code\":0").contains("\"status\":\"UP\"").contains("traceId");
    }
}
