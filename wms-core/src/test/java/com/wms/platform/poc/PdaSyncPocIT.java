package com.wms.platform.poc;

import com.wms.platform.common.device.offline.OfflineSyncQueue;
import com.wms.platform.common.constant.HeaderConst;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PoC-3 · PDA 离线补传与冲突（{@code 06-PoC执行清单.md} §P3）。
 *
 * <p><b>与其他 PoC 的关键区别</b>：本用例**走真实 HTTP 链路**（MockMvc → 设备绑定拦截器 →
 * Controller → 应用服务 → MyBatis → MySQL），而不是直接用 JDBC 造数验规则。原因是 P3 要证伪的风险
 * 恰恰在链路上："补传丢了/重复扣了/冲突把服务端状态覆盖了"——这些都发生在拦截器与事务边界之间。
 *
 * <p><b>客户端部分怎么测</b>：本机没有 PDA、也无法开飞行模式，因此用
 * {@link OfflineSyncQueue}（客户端参考实现，放 `wms-common` 供 W5 移植）承载"断网入队 /
 * App 重启 / 哪些结果出队"的语义，再由本用例把队列产生的报文真的发到服务端。
 * **仍无法验证的**：真实弱网/飞行模式下的网络行为、PDA 硬件输入、本地 SQLite 落盘——
 * 已在 {@code 10-PoC-P3执行报告.md} §未覆盖项 声明。
 *
 * <p><b>运行方式</b>（有外部 MySQL 或 Docker 才会执行，否则跳过）：
 * <pre>
 * mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=PdaSyncPocIT \
 *     -Dpoc.mysql.url=jdbc:mysql://127.0.0.1:3399/wms_poc4 -Dpoc.mysql.user=root -Dpoc.mysql.password=
 * </pre>
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("PoC-3 PDA 离线补传与冲突")
class PdaSyncPocIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 仓库。 */
    private static final long WAREHOUSE = 1L;

    /** 商家。 */
    private static final long MERCHANT = 1L;

    /** 已登记 PDA 设备号。 */
    private static final String DEVICE = "PDA-A-007";

    /** 出库单号（拣货场景）。 */
    private static final String DO_PICK = "DO20261014000001";

    /** 出库单号（装车场景）。 */
    private static final String DO_LOAD = "DO20261014000002";

    /** 入库通知单号（上架场景）。 */
    private static final String ASN = "ASN20261014000001";

    /** 商品（W1 简化：PDA 扫内部条码 = sku_id）。 */
    private static final long SKU_ID = 1001L;

    /** 库位（W1 简化：PDA 扫内部条码 = location_id）。 */
    private static final long LOCATION_ID = 2001L;

    /** Testcontainers 容器（外部模式为 null）。 */
    private static MySQLContainer<?> container;

    /** 数据源地址（供断言失败时打印）。 */
    private static String resolvedUrl;

    /**
     * 注入数据源属性（外部 MySQL 优先，其次 Testcontainers）。
     *
     * @param registry 属性注册器
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            resolvedUrl = url;
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("poc.mysql.user", "root"));
            registry.add("spring.datasource.password", () -> System.getProperty("poc.mysql.password", ""));
        } else {
            container = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("wms_poc3").withUsername("poc").withPassword("poc");
            container.start();
            resolvedUrl = container.getJdbcUrl();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        // Flyway 在上下文启动时执行迁移（V1/V2/V3），保证与生产同一套 DDL
        registry.add("spring.flyway.enabled", () -> true);
    }

    /** 数据访问。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** MockMvc（真实 HTTP 链路）。 */
    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void printTarget() {
        // 说明：resolvedUrl 在 @DynamicPropertySource 里赋值，而它执行于上下文创建阶段（晚于 @BeforeAll），
        // 所以这里只打印来源信息，避免打印 null 造成误判
        String source = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        System.out.printf("[POC3] 目标库来源 = %s（Flyway 将执行 V1/V2/V3）%n",
                source == null || source.isBlank() ? "Testcontainers" : "-Dpoc.mysql.url");
    }

    /** 每个用例重置本 PoC 涉及的表。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"pf_pda_conflict", "iv_movement", "iv_location", "iv_sku",
                "pf_idempotent", "md_device", "md_location", "md_sku", "out_order", "in_asn"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
        // 已登记且启用的 PDA（设备绑定拦截器的前提）
        jdbc.update("INSERT INTO md_device (id, tenant_id, device_code, device_type, name, warehouse_id, status, deleted) "
                        + "VALUES (9001, 1, ?, 'PDA', '拣货区PDA', ?, '启用', 0)",
                DEVICE, WAREHOUSE);
        // 主数据：W3 起"条码 → 内部 ID"必须走 md_sku / md_location（不再用"是否已有库存账"做近似判定，
        // 那个近似会把"新库位首次上架"误判成错扫）。本 PoC 报文里的编码即内部 ID 的字符串形式。
        jdbc.update("INSERT INTO md_sku (id, tenant_id, merchant_id, sku_code, name, unit, status, deleted) "
                        + "VALUES (?, 1, ?, ?, 'PoC 测试商品', '件', '启用', 0)",
                SKU_ID, MERCHANT, String.valueOf(SKU_ID));
        jdbc.update("INSERT INTO md_location (id, tenant_id, warehouse_id, zone_id, location_code, is_pickable, "
                        + "status, deleted) VALUES (?, 1, ?, 9002, ?, 1, '启用', 0)",
                LOCATION_ID, WAREHOUSE, String.valueOf(LOCATION_ID));
    }

    // ===================== 步骤 1-2：断网 20 次 → 恢复后补传 =====================

    @Test
    @DisplayName("步骤1-2：断网扫码 20 次（App 重启也不丢）→ 恢复后一次性补传，20 条全部受理且数据一致")
    void step1And2_offlineQueueThenBatchUpload() throws Exception {
        // 1. 造 1 张拣货中的 DO、1 张待装车的 DO、1 张待上架的 ASN，以及对应库存
        insertDo(DO_PICK, "拣货中");
        insertDo(DO_LOAD, "待装车");
        insertAsn(ASN, "待上架");
        insertSkuStock(new BigDecimal("100.0000"), new BigDecimal("80.0000"), new BigDecimal("20.0000"));
        insertLocationStock(new BigDecimal("100.0000"), new BigDecimal("20.0000"));

        // 2. 断网状态下连做 20 次扫码（拣货 12 + 装车 4 + 上架 4），并模拟中途 App 被杀重启
        OfflineSyncQueue queue = new OfflineSyncQueue();
        queue.setOffline(true);
        List<String> payloadItems = new ArrayList<>();
        int seq = 0;
        for (int i = 0; i < 12; i++) {
            queue.enqueue("T-PICK-1", "PICK", ++seq, DO_PICK, String.valueOf(SKU_ID), String.valueOf(LOCATION_ID),
                    new BigDecimal("1.0000"), LocalDateTime.now());
        }
        for (int i = 0; i < 4; i++) {
            queue.enqueue("T-LOAD-1", "LOAD", ++seq, DO_LOAD, String.valueOf(SKU_ID), null,
                    new BigDecimal("1.0000"), LocalDateTime.now());
        }
        for (int i = 0; i < 4; i++) {
            queue.enqueue("T-PUTAWAY-1", "PUTAWAY", ++seq, ASN, String.valueOf(SKU_ID), String.valueOf(LOCATION_ID),
                    new BigDecimal("1.0000"), LocalDateTime.now());
        }
        // 2.1 App 重启：快照 → 恢复（断网 2 小时零丢失的客户端侧验证点）
        queue = OfflineSyncQueue.restore(queue.snapshot());
        assertThat(queue.size()).as("重启后队列条数不变").isEqualTo(20);

        // 3. 恢复网络，一次性补传
        for (OfflineSyncQueue.QueuedScan scan : queue.pendingForUpload()) {
            payloadItems.add(toItemJson(scan.taskId(), scan.action(), scan.seq(), scan.bizNo(),
                    scan.skuCode(), scan.locationCode(), scan.qty()));
        }
        String body = "{\"deviceCode\":\"" + DEVICE + "\",\"operatorId\":1001,\"batchSeq\":1,\"items\":["
                + String.join(",", payloadItems) + "]}";

        String response = mockMvc.perform(post("/api/v1/pda/sync/batch")
                        .header(HeaderConst.DEVICE_CODE, DEVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(20))
                .andExpect(jsonPath("$.data.acceptedCount").value(20))
                .andReturn().getResponse().getContentAsString();

        // 4. 服务端数据与"纸质记录"一致
        assertThat(count("iv_movement")).as("20 条作业 → 20 条库存流水").isEqualTo(20);
        assertThat(count("pf_pda_conflict")).as("全部受理，无冲突待办").isEqualTo(0);
        // 4.1 拣货 12 次：库位已分配量 20 → 8
        assertThat(decimal("SELECT allocated_qty FROM iv_location WHERE location_id = " + LOCATION_ID))
                .isEqualByComparingTo("8.0000");
        // 4.2 装车 4 次（实物与预占同减）+ 上架 4 次（实物与可用同增），初始 physical=100/available=80/reserved=20：
        //     装车后 96/80/16 → 上架后 100/84/16；全程满足 physical = available + reserved
        assertThat(decimal("SELECT physical_qty FROM iv_sku WHERE sku_id = " + SKU_ID))
                .as("100 - 4（装车）+ 4（上架）").isEqualByComparingTo("100.0000");
        assertThat(decimal("SELECT reserved_qty FROM iv_sku WHERE sku_id = " + SKU_ID))
                .as("20 - 4（装车）").isEqualByComparingTo("16.0000");
        assertThat(decimal("SELECT available_qty FROM iv_sku WHERE sku_id = " + SKU_ID))
                .as("80 + 4（上架）").isEqualByComparingTo("84.0000");
        // 4.3 三态不变量（与 01 §4 的校验 SQL 同口径）
        assertThat(decimal("SELECT physical_qty - available_qty - reserved_qty FROM iv_sku WHERE sku_id = " + SKU_ID))
                .as("physical = available + reserved").isEqualByComparingTo("0");
        // 4.4 两级账一致（01 §4 不变量 2）——W3 起由库存域统一保证：
        //     装车出库（LOAD）会同时扣减库位实物，此前 PDA 侧自己写的 SQL 只扣 SKU 级，会留下差异
        BigDecimal skuPhysical = decimal("SELECT physical_qty FROM iv_sku WHERE sku_id = " + SKU_ID);
        BigDecimal locationPhysical = decimal("SELECT physical_qty FROM iv_location WHERE location_id = " + LOCATION_ID);
        assertThat(locationPhysical).as("库位实物合计 = SKU 实物（100 - 4 + 4）").isEqualByComparingTo(skuPhysical);

        printReport("步骤1-2 断网补传", "20 条全部 accepted，库存流水 20 条，三态与库位分配量一致；响应长度="
                + response.length());
    }

    // ===================== 步骤 3：重复补传 3 次 =====================

    @Test
    @DisplayName("步骤3：同一批重复补传 3 次 → 全部 duplicated，库存流水零重复（uk_movement_idem 生效）")
    void step3_replayThreeTimesIsIdempotent() throws Exception {
        // 1. 造数据
        insertDo(DO_PICK, "拣货中");
        insertLocationStock(new BigDecimal("100.0000"), new BigDecimal("20.0000"));
        String body = batchBody(List.of(
                item("T-PICK-1", "PICK", 1, DO_PICK, String.valueOf(SKU_ID), String.valueOf(LOCATION_ID), "2.0000")));

        // 2. 首次补传：accepted
        String first = mockMvc.perform(post("/api/v1/pda/sync/batch")
                        .header(HeaderConst.DEVICE_CODE, DEVICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(1))
                .andReturn().getResponse().getContentAsString();
        long movementAfterFirst = count("iv_movement");
        BigDecimal allocatedAfterFirst = decimal("SELECT allocated_qty FROM iv_location WHERE location_id = " + LOCATION_ID);

        // 3. 重复补传 3 次：全部 duplicated，且不产生新的流水、不再扣库存
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(post("/api/v1/pda/sync/batch")
                            .header(HeaderConst.DEVICE_CODE, DEVICE)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.duplicatedCount").value(1))
                    .andExpect(jsonPath("$.data.acceptedCount").value(0))
                    .andExpect(jsonPath("$.data.results[0].status").value("duplicated"));
        }

        assertThat(count("iv_movement")).as("重复补传不得新增库存流水").isEqualTo(movementAfterFirst);
        assertThat(decimal("SELECT allocated_qty FROM iv_location WHERE location_id = " + LOCATION_ID))
                .as("重复补传不得重复扣减").isEqualByComparingTo(allocatedAfterFirst);
        assertThat(first).contains("accepted");

        printReport("步骤3 重复补传", "重复 3 次全部 duplicated；iv_movement 始终 "
                + movementAfterFirst + " 条，库位分配量 " + allocatedAfterFirst + " 未变");
    }

    // ===================== 步骤 4：冲突不覆盖服务端状态 =====================

    @Test
    @DisplayName("步骤4：服务端已推进到「已出库」后补传拣货 → conflict，状态不被覆盖且可在 /pda/conflicts 查到")
    void step4_conflictShouldNotOverwriteServerStatus() throws Exception {
        // 1. 服务端已把该单推进到「已出库」（模拟别的路径先完成）
        insertDo(DO_PICK, "已出库");
        insertLocationStock(new BigDecimal("100.0000"), new BigDecimal("20.0000"));
        String body = batchBody(List.of(
                item("T-PICK-1", "PICK", 1, DO_PICK, String.valueOf(SKU_ID), String.valueOf(LOCATION_ID), "1.0000")));

        // 2. 补传拣货 → conflict
        mockMvc.perform(post("/api/v1/pda/sync/batch")
                        .header(HeaderConst.DEVICE_CODE, DEVICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.conflictCount").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("conflict"))
                .andExpect(jsonPath("$.data.results[0].serverStatus").value("已出库"));

        // 3. **核心断言：服务端状态未被覆盖**
        assertThat(jdbc.queryForObject("SELECT status FROM out_order WHERE do_no = ?", String.class, DO_PICK))
                .as("冲突绝不能覆盖服务端状态").isEqualTo("已出库");
        // 3.1 也不得产生库存流水与扣减
        assertThat(count("iv_movement")).isZero();
        assertThat(decimal("SELECT allocated_qty FROM iv_location WHERE location_id = " + LOCATION_ID))
                .isEqualByComparingTo("20.0000");

        // 4. 冲突在 GET /pda/conflicts 中可见
        mockMvc.perform(get("/api/v1/pda/conflicts").header(HeaderConst.DEVICE_CODE, DEVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].conflictType").value("状态已流转"))
                .andExpect(jsonPath("$.data[0].serverStatus").value("已出库"))
                .andExpect(jsonPath("$.data[0].targetStatus").value("已复核"))
                .andExpect(jsonPath("$.data[0].status").value("待人工处理"))
                .andExpect(jsonPath("$.data[0].deviceCode").value(DEVICE));

        // 5. 重复补传同一冲突：人工待办不刷屏（uk_pda_idem）
        mockMvc.perform(post("/api/v1/pda/sync/batch")
                .header(HeaderConst.DEVICE_CODE, DEVICE).contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(count("pf_pda_conflict")).as("同一补传只生成一条待办").isEqualTo(1);

        printReport("步骤4 冲突处理", "返回 conflict、DO 状态保持「已出库」、流水 0 条；/pda/conflicts 可见 1 条待办");
    }

    // ===================== 步骤 5：错扫 =====================

    @Test
    @DisplayName("步骤5：扫一个不存在的库位 → rejected（保留本地提示重扫），且不建人工待办")
    void step5_unknownLocationShouldBeRejected() throws Exception {
        // 1. 只建 SKU 账，不建该库位账 → 扫到的库位"不存在"
        insertDo(DO_PICK, "拣货中");
        jdbc.update("INSERT INTO iv_sku (id, tenant_id, warehouse_id, merchant_id, sku_id, physical_qty, "
                        + "available_qty, reserved_qty, version) VALUES (7001, 1, 1, 1, ?, 100, 80, 20, 0)",
                SKU_ID);
        String body = batchBody(List.of(
                item("T-PICK-1", "PICK", 1, DO_PICK, String.valueOf(SKU_ID), "999999", "1.0000")));

        // 2. 补传 → rejected
        MvcResult result = mockMvc.perform(post("/api/v1/pda/sync/batch")
                        .header(HeaderConst.DEVICE_CODE, DEVICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejectedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("rejected"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("重扫");
        assertThat(count("iv_movement")).isZero();
        assertThat(count("pf_pda_conflict")).as("错扫不该淹没人工待办列表").isZero();

        // 3. 本地队列必须保留该条（否则现场作业真丢了）
        OfflineSyncQueue queue = new OfflineSyncQueue();
        queue.enqueue("T-PICK-1", "PICK", 1, DO_PICK, String.valueOf(SKU_ID), "999999", BigDecimal.ONE,
                LocalDateTime.now());
        queue.applyResults(List.of(new OfflineSyncQueue.ItemOutcome("T-PICK-1", 1, "rejected", null, "错扫")));
        assertThat(queue.size()).isEqualTo(1);

        printReport("步骤5 错扫", "返回 rejected、流水 0 条、待办 0 条；客户端队列保留 1 条待重扫");
    }

    // ===================== 步骤 6：断网 2 小时零丢失 =====================

    @Test
    @DisplayName("步骤6：断网 2 小时（客户端时钟偏差 + App 重启）→ 补传后数据零丢失、零重复")
    void step6_twoHourOfflineZeroLoss() throws Exception {
        // 1. 造数据
        insertDo(DO_PICK, "拣货中");
        insertLocationStock(new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        OfflineSyncQueue queue = new OfflineSyncQueue();
        queue.setOffline(true);

        // 2. 模拟"断网 2 小时"：扫码时间戳落后服务端 2 小时（客户端时钟偏差的真实形态）
        LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);
        for (int i = 1; i <= 20; i++) {
            queue.enqueue("T-PICK-1", "PICK", i, DO_PICK, String.valueOf(SKU_ID), String.valueOf(LOCATION_ID),
                    new BigDecimal("2.0000"), twoHoursAgo.plusMinutes(i));
        }
        // 2.1 期间 App 被杀两次（快照/恢复两次）
        queue = OfflineSyncQueue.restore(queue.snapshot());
        queue = OfflineSyncQueue.restore(queue.snapshot());
        assertThat(queue.size()).isEqualTo(20);

        // 3. 网络恢复：补传
        List<String> items = new ArrayList<>();
        for (OfflineSyncQueue.QueuedScan scan : queue.pendingForUpload()) {
            items.add(toItemJson(scan.taskId(), scan.action(), scan.seq(), scan.bizNo(), scan.skuCode(),
                    scan.locationCode(), scan.qty()));
        }
        mockMvc.perform(post("/api/v1/pda/sync/batch")
                        .header(HeaderConst.DEVICE_CODE, DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content(batchBodyRaw(items)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(20));

        // 4. 零丢失：20 条流水；库位分配量 50 - 40 = 10
        assertThat(count("iv_movement")).isEqualTo(20);
        assertThat(decimal("SELECT allocated_qty FROM iv_location WHERE location_id = " + LOCATION_ID))
                .isEqualByComparingTo("10.0000");

        // 5. 再用同一批补传一次（模拟网络抖动导致的重传）→ 零重复
        mockMvc.perform(post("/api/v1/pda/sync/batch")
                        .header(HeaderConst.DEVICE_CODE, DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content(batchBodyRaw(items)))
                .andExpect(jsonPath("$.data.duplicatedCount").value(20));
        assertThat(count("iv_movement")).as("重传不产生新流水").isEqualTo(20);

        printReport("步骤6 断网 2 小时", "20 条零丢失、重传零重复；客户端时钟偏差 2 小时不影响判定（服务端用受理时间）");
    }

    // ===================== 设备绑定（与 08 的闭环） =====================

    @Test
    @DisplayName("设备绑定：未登记设备补传 → 403 + 10006（拦截器在建 PdaSyncController 之前就拒绝）")
    void unregisteredDeviceShouldBeForbidden() throws Exception {
        insertDo(DO_PICK, "拣货中");
        String body = batchBody(List.of(
                item("T1", "PICK", 1, DO_PICK, String.valueOf(SKU_ID), String.valueOf(LOCATION_ID), "1.0000")));

        mockMvc.perform(post("/api/v1/pda/sync/batch")
                        .header(HeaderConst.DEVICE_CODE, "PDA-NOT-REGISTERED")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10006));

        assertThat(count("iv_movement")).isZero();
        printReport("设备绑定", "未登记设备被拒 403 + 10006，未产生任何库存流水");
    }

    @Test
    @DisplayName("防伪造：请求体 deviceCode 与请求头不一致 → 拒绝（否则审计里的设备号失去意义）")
    void mismatchedDeviceCodeShouldBeRejected() throws Exception {
        insertDo(DO_PICK, "拣货中");
        String body = "{\"deviceCode\":\"PDA-OTHER\",\"operatorId\":1001,\"batchSeq\":1,\"items\":["
                + item("T1", "PICK", 1, DO_PICK, String.valueOf(SKU_ID), String.valueOf(LOCATION_ID), "1.0000") + "]}";

        mockMvc.perform(post("/api/v1/pda/sync/batch")
                        .header(HeaderConst.DEVICE_CODE, DEVICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("与请求头不一致")));

        // 业务失败按 03 §1.3 约定返回 HTTP 200 + code≠0（不是 4xx）
        printReport("防伪造", "请求体设备号与请求头不一致被拒（HTTP 200 + code 10001）");
    }

    // ===================== 夹具与工具 =====================

    /**
     * 备出库单。
     *
     * @param doNo   单号
     * @param status 状态
     */
    private void insertDo(String doNo, String status) {
        jdbc.update("INSERT INTO out_order (id, tenant_id, do_no, warehouse_id, merchant_id, source_type, status, deleted) "
                        + "VALUES (?, 1, ?, ?, ?, 'API', ?, 0)",
                Math.abs(doNo.hashCode()), doNo, WAREHOUSE, MERCHANT, status);
    }

    /**
     * 备入库通知单。
     *
     * @param asnNo  单号
     * @param status 状态
     */
    private void insertAsn(String asnNo, String status) {
        jdbc.update("INSERT INTO in_asn (id, tenant_id, asn_no, warehouse_id, merchant_id, status, deleted) "
                        + "VALUES (?, 1, ?, ?, ?, ?, 0)",
                Math.abs(asnNo.hashCode()), asnNo, WAREHOUSE, MERCHANT, status);
    }

    /**
     * 备 SKU 三态账（需满足 ck_iv_sku_balance：physical = available + reserved）。
     *
     * @param physical  实物
     * @param available 可用
     * @param reserved  预占
     */
    private void insertSkuStock(BigDecimal physical, BigDecimal available, BigDecimal reserved) {
        jdbc.update("INSERT INTO iv_sku (id, tenant_id, warehouse_id, merchant_id, sku_id, physical_qty, "
                        + "available_qty, reserved_qty, version) VALUES (7001, 1, ?, ?, ?, ?, ?, ?, 0)",
                WAREHOUSE, MERCHANT, SKU_ID, physical, available, reserved);
    }

    /**
     * 备库位账。
     *
     * @param physical  实物
     * @param allocated 已分配待拣
     */
    private void insertLocationStock(BigDecimal physical, BigDecimal allocated) {
        jdbc.update("INSERT INTO iv_location (id, tenant_id, warehouse_id, location_id, merchant_id, sku_id, "
                        + "batch_no, physical_qty, allocated_qty, version) VALUES (8001, 1, ?, ?, ?, ?, '', ?, ?, 0)",
                WAREHOUSE, LOCATION_ID, MERCHANT, SKU_ID, physical, allocated);
    }

    /**
     * 组装单条补传 JSON。
     *
     * @param taskId       任务号
     * @param action       动作
     * @param seq          序号
     * @param bizNo        单号
     * @param skuCode      商品条码
     * @param locationCode 库位编码
     * @param qty          数量
     * @return JSON
     */
    private String item(String taskId, String action, int seq, String bizNo, String skuCode, String locationCode,
                        String qty) {
        return "{\"taskId\":\"" + taskId + "\",\"action\":\"" + action + "\",\"seq\":" + seq
                + ",\"bizNo\":\"" + bizNo + "\",\"skuCode\":\"" + skuCode + "\",\"locationCode\":\""
                + (locationCode == null ? "" : locationCode) + "\",\"qty\":\"" + qty
                + "\",\"scannedAt\":\"2026-10-14 09:31:02\"}";
    }

    /**
     * 组装单条补传 JSON（客户端队列版，保留真实扫码时间）。
     *
     * @param taskId       任务号
     * @param action       动作
     * @param seq          序号
     * @param bizNo        单号
     * @param skuCode      商品条码
     * @param locationCode 库位编码
     * @param qty          数量
     * @return JSON
     */
    private String toItemJson(String taskId, String action, int seq, String bizNo, String skuCode, String locationCode,
                              BigDecimal qty) {
        return "{\"taskId\":\"" + taskId + "\",\"action\":\"" + action + "\",\"seq\":" + seq
                + ",\"bizNo\":\"" + bizNo + "\",\"skuCode\":\"" + (skuCode == null ? "" : skuCode)
                + "\",\"locationCode\":\"" + (locationCode == null ? "" : locationCode)
                + "\",\"qty\":\"" + qty.toPlainString() + "\",\"scannedAt\":\""
                + LocalDateTime.now().minusHours(2) + "\"}";
    }

    /**
     * 组装批量补传请求体。
     *
     * @param items 明细 JSON 列表
     * @return 请求体
     */
    private String batchBody(List<String> items) {
        return batchBodyRaw(items);
    }

    /**
     * 组装批量补传请求体（原始明细）。
     *
     * @param items 明细 JSON 列表
     * @return 请求体
     */
    private String batchBodyRaw(List<String> items) {
        return "{\"deviceCode\":\"" + DEVICE + "\",\"operatorId\":1001,\"batchSeq\":1,\"items\":["
                + String.join(",", items) + "]}";
    }

    /**
     * 计数。
     *
     * @param table 表名
     * @return 行数
     */
    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = " + TENANT, Long.class);
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
     * 打印统一格式的实验报告（便于从构建日志 grep）。
     *
     * @param step   步骤
     * @param detail 明细
     */
    private void printReport(String step, String detail) {
        System.out.printf("[POC3] %s | %s%n", step, detail);
    }
}
