package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.StockOpCommand;
import com.wms.platform.modules.outbound.api.ro.CancelOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.CreateOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.DispatchOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.HandoverOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.HoldOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.LoadOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.MeasureOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PackOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PageOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PartialShipOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PickCompleteOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.ShortageOutOrderRo;
import com.wms.platform.modules.outbound.api.vo.OutOrderDetailVo;
import com.wms.platform.modules.outbound.api.vo.OutOrderPageVo;
import com.wms.platform.modules.outbound.api.vo.PartialShipVo;
import com.wms.platform.modules.outbound.app.OutboundOrderService;
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
 * 出库域集成测试（W6 / M7），跑在**真实 MySQL + 真实 Spring 上下文**上。
 *
 * <p><b>为什么这批用例只能落库验证</b>：出库是"库存账 + 库位账 + 资金冻结 + 装运单 + 状态流水"
 * 五处同时变化的长链路，纯单测只能验证状态机与规则。真正容易错的都是跨表一致性：
 * 预占释放多少（缺货后不能重复释放）、库位 {@code allocated} 是否归位、取消后是否留下
 * "货被占住但单据已取消"的孤儿预占、部分发货的余量预占有没有迁移过去——这些只有真库能证伪。
 *
 * <p>运行方式（同其它 IT：有外部 MySQL 或 Docker 才跑，否则跳过）：
 * <pre>
 * mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=OutboundFlowIT \
 *     -Dpoc.mysql.url=jdbc:mysql://127.0.0.1:3306/wms_it_out -Dpoc.mysql.user=root -Dpoc.mysql.password=***
 * </pre>
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest
@DisplayName("出库域（真实库）")
class OutboundFlowIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** 仓库。 */
    private static final long WAREHOUSE = 1L;

    /** 商家。 */
    private static final long MERCHANT = 1L;

    /** 商品。 */
    private static final long SKU = 1001L;

    /** 商品条码。 */
    private static final String SKU_CODE = "SKU-OUT-001";

    /** 拣货库位。 */
    private static final long LOCATION = 2001L;

    /** 库位编码。 */
    private static final String LOCATION_CODE = "A-01-02-03";

    /** 账户 ID。 */
    private static final long ACCOUNT = 3001L;

    /** Testcontainers 容器（外部模式为 null）。 */
    private static MySQLContainer<?> container;

    /**
     * 注入数据源（外部 MySQL 优先，其次 Docker）。
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
                    .withDatabaseName("wms_w6").withUsername("poc").withPassword("poc");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        registry.add("spring.flyway.enabled", () -> true);
    }

    /** 数据访问（夹具与断言用）。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 被测服务（出库）。 */
    @Autowired
    private OutboundOrderService outboundOrderService;

    /** 库存服务（备货夹具）。 */
    @Autowired
    private InventoryService inventoryService;

    /** 每个用例重置出库、库存、资金与主数据。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"pf_biz_status_log", "out_order_item", "out_order", "lg_shipment_proof",
                "lg_shipment", "ac_ledger", "ac_fee_freeze", "ac_merchant_account", "iv_movement", "iv_location",
                "iv_sku", "md_location", "md_zone", "md_sku", "pf_idempotent"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
        // 1. 主数据：库区 / 库位 / SKU（重量 2kg，便于称重偏差用例）
        jdbc.update("INSERT INTO md_zone (id, tenant_id, warehouse_id, zone_code, zone_type) "
                + "VALUES (4001, 1, ?, 'Z-A', '存储区')", WAREHOUSE);
        jdbc.update("INSERT INTO md_location (id, tenant_id, warehouse_id, zone_id, location_code, is_pickable, status) "
                + "VALUES (?, 1, ?, 4001, ?, 1, '启用')", LOCATION, WAREHOUSE, LOCATION_CODE);
        jdbc.update("INSERT INTO md_sku (id, tenant_id, merchant_id, sku_code, name, unit, weight_kg, status) "
                + "VALUES (?, 1, ?, ?, '出库测试商品', '件', 2.0000, '启用')", SKU, MERCHANT, SKU_CODE);
        // 2. 资金账户：余额 1000（冻结用例会单独改小）
        jdbc.update("INSERT INTO ac_merchant_account (id, tenant_id, merchant_id, balance, freeze_amount, "
                + "deposit_amount, credit_limit, version, deleted) VALUES (?, 1, ?, 1000.0000, 0, 0, 0, 0, 0)",
                ACCOUNT, MERCHANT);
        // 3. 库存备货 100 到库位 A（出库必须有实物）
        inventoryService.receive(StockOpCommand.of(WAREHOUSE, MERCHANT, SKU, new BigDecimal("100"), "ASN", 0L,
                "ASN-FIXTURE", "RECEIVE:FIXTURE", LOCATION));
    }

    @Test
    @DisplayName("正向全链路：建单→派发→拣货→称重→打包→交接→装车，五处账一致")
    void fullFlowShouldShipAndKeepAllBooksBalanced() {
        // 1. 建单（冻结 100×1.1 = 110）
        OutOrderDetailVo created = createOrder(new BigDecimal("10"), new BigDecimal("100.0000"), "SO-FULL-1");
        assertThat(created.status()).isEqualTo("待接单");
        String doNo = created.doNo();
        assertThat(freezeCount(doNo)).as("建单必须冻结").isEqualTo(1);
        assertThat(freezeStatus(doNo)).isEqualTo("已冻结");
        assertThat(shipmentStatus(doNo)).as("建单联动生成待取号装运单").isEqualTo("待取号");
        assertThat(reserved()).as("预占 10").isEqualByComparingTo("10");
        assertThat(available()).isEqualByComparingTo("90");

        // 2. 派发 → 拣货 → 称重 → 打包 → 交接 → 装车
        OutOrderDetailVo dispatching = outboundOrderService.dispatch(doNo,
                new DispatchOutOrderRo(List.of(new DispatchOutOrderRo.ItemRo(1, LOCATION_CODE))), "IT-DISPATCH");
        assertThat(dispatching.status()).isEqualTo("拣货中");
        assertThat(allocated()).as("派发后库位已分配 10").isEqualByComparingTo("10");

        assertThat(outboundOrderService.pickComplete(doNo,
                new PickCompleteOutOrderRo(List.of(new PickCompleteOutOrderRo.ItemRo(1, new BigDecimal("10")))),
                "IT-PICK").status()).isEqualTo("已复核");
        assertThat(allocated()).as("拣货后 allocated 归零，SKU 三态不变").isEqualByComparingTo("0");
        assertThat(reserved()).as("拣货不动预占").isEqualByComparingTo("10");

        assertThat(outboundOrderService.measure(doNo,
                new MeasureOutOrderRo(new BigDecimal("2.0000"), new BigDecimal("0.1000"), 1), "IT-MEASURE")
                .status()).isEqualTo("已复核");
        assertThat(outboundOrderService.pack(doNo, new PackOutOrderRo("WB-IT-1", 1), "IT-PACK").status())
                .isEqualTo("已打包");
        assertThat(shipmentStatus(doNo)).as("打包绑定运单后装运单转已取号").isEqualTo("已取号");
        assertThat(outboundOrderService.handover(doNo, new HandoverOutOrderRo("交接给装卸 1 号"), "IT-HANDOVER")
                .status()).isEqualTo("待装车");
        OutOrderDetailVo shipped = outboundOrderService.load(doNo, new LoadOutOrderRo("装车完成"), "IT-LOAD");
        assertThat(shipped.status()).isEqualTo("已出库");
        assertThat(shipped.finishedAt()).isNotNull();

        // 3. 五处账：SKU 三态、库位实物、预占清理、状态流水 7 行、冻结仍在（待对账扣减）
        assertThat(physical()).as("实物 100 − 10").isEqualByComparingTo("90");
        assertThat(reserved()).as("出库后预占清理干净").isEqualByComparingTo("0");
        assertThat(available()).isEqualByComparingTo("90");
        assertThat(inventoryService.querySku(WAREHOUSE, MERCHANT, SKU).locationQtySum())
                .as("库位账 = SKU 账（不变量 2）").isEqualByComparingTo("90");
        assertThat(statusLogCount(doNo)).as("建单/派发/拣货/称重/打包/交接/装车 = 7 步").isEqualTo(7);
        assertThat(freezeStatus(doNo)).as("出库不释放冻结（按实结算）").isEqualTo("已冻结");
        assertThat(trackExists(doNo, "LOAD")).as("装车轨迹必须带事件名").isTrue();

        print("正向全链路", "DO=" + doNo + " 终态已出库，实物 90/可用 90/预占 0，轨迹 7 行");
    }

    @Test
    @DisplayName("幂等：同键同体重放返回首次结果，不重复预占/冻结")
    void sameIdemKeyReplaysWithoutDoubleApply() {
        CreateOutOrderRo ro = createRo(new BigDecimal("5"), new BigDecimal("50.0000"), "SO-IDEM-1");
        OutOrderDetailVo first = outboundOrderService.create(ro, "IT-IDEM-KEY");
        OutOrderDetailVo replay = outboundOrderService.create(ro, "IT-IDEM-KEY");

        assertThat(replay.doNo()).as("重放必须返回同一单号").isEqualTo(first.doNo());
        assertThat(orderCount()).as("只落一张单").isEqualTo(1);
        assertThat(freezeCount(first.doNo())).as("只冻一次").isEqualTo(1);
        assertThat(reserved()).isEqualByComparingTo("5");

        // 同键异体 → 409 + 10002
        CreateOutOrderRo changed = createRo(new BigDecimal("6"), new BigDecimal("50.0000"), "SO-IDEM-1");
        assertThatThrownBy(() -> outboundOrderService.create(changed, "IT-IDEM-KEY"))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENT_MISMATCH);
                    assertThat(ex.getHttpStatus().value()).isEqualTo(409);
                });
        print("幂等", "同体重放返回同一单号且只冻一次；异体 409+10002");
    }

    @Test
    @DisplayName("余额不足：20001 且整单回滚（不留孤儿预占与冻结）")
    void insufficientBalanceShouldRollbackWholeOrder() {
        jdbc.update("UPDATE ac_merchant_account SET balance = 10.0000 WHERE id = " + ACCOUNT);
        assertThatThrownBy(() -> createOrder(new BigDecimal("5"), new BigDecimal("100.0000"), "SO-NOMONEY-1"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE_BLOCK_ORDER));

        assertThat(orderCount()).as("建单失败不得留下单据").isEqualTo(0);
        assertThat(reserved()).as("预占必须回滚（否则货被永久占住）").isEqualByComparingTo("0");
        assertThat(available()).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ac_fee_freeze WHERE tenant_id = 1", Long.class))
                .isEqualTo(0L);
        print("余额不足", "20001；单据/预占/冻结均为 0 残留");
    }

    @Test
    @DisplayName("缺货与恢复：差额释放 → 剩余预占准确 → 恢复后重新占用")
    void shortageAndResumeShouldMoveReservedCorrectly() {
        OutOrderDetailVo created = createOrder(new BigDecimal("10"), new BigDecimal("10.0000"), "SO-SHORT-1");
        String doNo = created.doNo();
        outboundOrderService.dispatch(doNo,
                new DispatchOutOrderRo(List.of(new DispatchOutOrderRo.ItemRo(1, LOCATION_CODE))), "IT-S-DISPATCH");
        OutOrderDetailVo shortage = outboundOrderService.shortage(doNo,
                new ShortageOutOrderRo(List.of(new ShortageOutOrderRo.ItemRo(1, new BigDecimal("4"))), "货未到齐"),
                "IT-SHORTAGE");
        assertThat(shortage.status()).isEqualTo("缺货待处理");
        assertThat(reserved()).as("预占 10 − 缺货 4 = 6").isEqualByComparingTo("6");
        assertThat(available()).as("可用回到 94").isEqualByComparingTo("94");

        OutOrderDetailVo resumed = outboundOrderService.resumePicking(doNo, "IT-RESUME");
        assertThat(resumed.status()).isEqualTo("拣货中");
        assertThat(reserved()).as("恢复后重新占用回 10").isEqualByComparingTo("10");
        assertThat(available()).isEqualByComparingTo("90");
        assertThat(inventoryService.querySku(WAREHOUSE, MERCHANT, SKU).locationQtySum()).isEqualByComparingTo("100");
        print("缺货/恢复", "缺货释放 4 → 预占 6；恢复后预占 10、可用 90");
    }

    @Test
    @DisplayName("取消回滚矩阵：待接单 / 拣货中 / 已复核三个时点，库存与冻结都回到原状")
    void cancelShouldRollbackPerMatrix() {
        // 1. 待接单取消：释放预占（无分配动作）
        String doPending = createOrder(new BigDecimal("3"), new BigDecimal("30.0000"), "SO-C1").doNo();
        OutOrderDetailVo cancelledPending = outboundOrderService.cancel(doPending,
                new CancelOutOrderRo("商家撤单"), "IT-C1");
        assertThat(cancelledPending.status()).isEqualTo("已取消");
        assertThat(cancelledPending.cancelStage()).isEqualTo("待接单");
        assertThat(reserved()).isEqualByComparingTo("0");
        assertThat(freezeStatus(doPending)).as("冻结全额释放").isEqualTo("已释放");
        assertThat(shipmentStatus(doPending)).as("面单作废").isEqualTo("已作废");

        // 2. 拣货中取消：库位 allocated 归位
        String doPicking = createOrder(new BigDecimal("4"), new BigDecimal("40.0000"), "SO-C2").doNo();
        outboundOrderService.dispatch(doPicking,
                new DispatchOutOrderRo(List.of(new DispatchOutOrderRo.ItemRo(1, LOCATION_CODE))), "IT-C2-D");
        assertThat(allocated()).isEqualByComparingTo("4");
        outboundOrderService.cancel(doPicking, new CancelOutOrderRo("客户改地址"), "IT-C2");
        assertThat(allocated()).as("取消后分配必须归位").isEqualByComparingTo("0");
        assertThat(reserved()).isEqualByComparingTo("0");

        // 3. 已复核取消：拣货已发生（allocated 已释放），只需释放预占
        String doChecked = createOrder(new BigDecimal("6"), new BigDecimal("60.0000"), "SO-C3").doNo();
        outboundOrderService.dispatch(doChecked,
                new DispatchOutOrderRo(List.of(new DispatchOutOrderRo.ItemRo(1, LOCATION_CODE))), "IT-C3-D");
        outboundOrderService.pickComplete(doChecked,
                new PickCompleteOutOrderRo(List.of(new PickCompleteOutOrderRo.ItemRo(1, new BigDecimal("6")))),
                "IT-C3-P");
        outboundOrderService.cancel(doChecked, new CancelOutOrderRo("质检不合格"), "IT-C3");
        assertThat(reserved()).isEqualByComparingTo("0");
        assertThat(allocated()).isEqualByComparingTo("0");
        assertThat(physical()).as("未装车，实物不变").isEqualByComparingTo("100");

        // 4. 已出库取消 → 20002（不可逆点）
        String doShipped = createOrder(new BigDecimal("2"), new BigDecimal("20.0000"), "SO-C4").doNo();
        dispatchPickMeasurePackHandover(doShipped, new BigDecimal("2"), "IT-C4");
        outboundOrderService.load(doShipped, new LoadOutOrderRo("装车"), "IT-C4-LOAD");
        assertThatThrownBy(() -> outboundOrderService.cancel(doShipped, new CancelOutOrderRo("想取消"), "IT-C4-C"))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_STATUS_TRANSITION);
                    assertThat(ex.getHttpStatus().value()).isEqualTo(409);
                });
        print("取消矩阵", "待接单/拣货中/已复核三时点回滚正确；已出库取消被拒 20002");
    }

    @Test
    @DisplayName("前置与负向：未交接装车 20005、扫错面单 20004、称重偏差 20003")
    void preconditionsShouldBlockIllegalActions() {
        // 1. 称重偏差超 20% → 20003（应收 2kg，实称 3kg）
        String doDeviation = createOrder(new BigDecimal("1"), new BigDecimal("10.0000"), "SO-E1").doNo();
        outboundOrderService.dispatch(doDeviation,
                new DispatchOutOrderRo(List.of(new DispatchOutOrderRo.ItemRo(1, LOCATION_CODE))), "IT-E1-D");
        outboundOrderService.pickComplete(doDeviation,
                new PickCompleteOutOrderRo(List.of(new PickCompleteOutOrderRo.ItemRo(1, new BigDecimal("1")))),
                "IT-E1-P");
        assertThatThrownBy(() -> outboundOrderService.measure(doDeviation,
                new MeasureOutOrderRo(new BigDecimal("3.0000"), null, 1), "IT-E1-M"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.WEIGHT_DEVIATION_EXCEEDED));

        // 2. 打包：未称重 → 参数错误
        assertThatThrownBy(() -> outboundOrderService.pack(doDeviation, new PackOutOrderRo("WB-E1", 1), "IT-E1-K"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("称重");
        // 3. 称重通过后正常打包（占用运单号 WB-E1）
        outboundOrderService.measure(doDeviation,
                new MeasureOutOrderRo(new BigDecimal("2.0000"), null, 1), "IT-E1-M2");
        assertThat(outboundOrderService.pack(doDeviation, new PackOutOrderRo("WB-E1", 1), "IT-E1-K2").status())
                .isEqualTo("已打包");
        // 4. 扫错面单 → 20004：另一张单拿已被占用的运单号打包（uk_waybill 兜底，翻译成"面单与单据不匹配"）
        //    注意：已打包的单再打包会被状态机先拦成 20002（PACK 只允许从"已复核"进入），
        //    因此"扫错面单"必须用另一张已复核的单来构造，这也更贴近现场（拿错了别人的面单）。
        String doWrongLabel = createOrder(new BigDecimal("1"), new BigDecimal("10.0000"), "SO-E2").doNo();
        dispatchPickMeasure(doWrongLabel, new BigDecimal("1"), "IT-E2", new BigDecimal("2.0000"));
        assertThatThrownBy(() -> outboundOrderService.pack(doWrongLabel, new PackOutOrderRo("WB-E1", 1), "IT-E2-K"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.LABEL_NOT_MATCH_ORDER));
        assertThat(outboundOrderService.detail(doWrongLabel).status())
                .as("打包失败必须整笔回滚（状态仍为已复核）").isEqualTo("已复核");

        // 5. 未交接直接装车 → 20005
        //    说明：正常流程下"待装车"必然已扫码交接（HANDOVER 会置 handover_confirmed=1），
        //    所以这条是**防御性校验**：只在"有人绕过应用改了状态"、"交接开关事后被打开"或
        //    "历史数据缺交接标记"时生效。这里直接改库构造该场景（与库存 IT 制造账差同一手法），
        //    否则这条防线永远不会被验证到。
        jdbc.update("UPDATE out_order SET status = '待装车' WHERE tenant_id = 1 AND do_no = '" + doWrongLabel + "'");
        assertThatThrownBy(() -> outboundOrderService.load(doWrongLabel, new LoadOutOrderRo("抢装"), "IT-E2-L"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.HANDOVER_REQUIRED));
        assertThat(physical()).as("被拦下时实物不变（失败动作完全回滚）").isEqualByComparingTo("100");
        print("前置/负向", "偏差 20003、扫错面单 20004、未交接装车 20005，且库存未变");
    }

    @Test
    @DisplayName("部分发货：原单结清出账、余量生成新单并迁移预占")
    void partialShipShouldCloseOriginAndCarryRemainder() {
        OutOrderDetailVo created = createOrder(new BigDecimal("10"), new BigDecimal("100.0000"), "SO-PART-1");
        String doNo = created.doNo();
        dispatchPickMeasurePackHandover(doNo, new BigDecimal("10"), "IT-P");

        PartialShipVo result = outboundOrderService.partialShip(doNo, new PartialShipOutOrderRo(
                List.of(new PartialShipOutOrderRo.ItemRo(1, new BigDecimal("6"))), "客户只要 6 件"), "IT-PART");
        assertThat(result.shippedQty()).isEqualByComparingTo("6");
        assertThat(result.remainderQty()).isEqualByComparingTo("4");
        assertThat(result.newDoNo()).isNotEqualTo(doNo);

        // 1. 原单已出库、余量单待接单
        assertThat(outboundOrderService.detail(doNo).status()).isEqualTo("已出库");
        OutOrderDetailVo newOrder = outboundOrderService.detail(result.newDoNo());
        assertThat(newOrder.status()).isEqualTo("待接单");
        assertThat(newOrder.items()).singleElement()
                .satisfies(item -> assertThat(item.qty()).isEqualByComparingTo("4"));

        // 2. 库存：实物 −6、预占剩下 4（属于新单）、库位实物 −6、两级账仍平
        assertThat(physical()).as("实物 100 − 6").isEqualByComparingTo("94");
        assertThat(reserved()).as("余量 4 的预占已迁移到新单").isEqualByComparingTo("4");
        assertThat(inventoryService.querySku(WAREHOUSE, MERCHANT, SKU).locationQtySum()).isEqualByComparingTo("94");

        // 3. 新单取消 → 4 件预占能被正确释放（若没迁移，这里会释放不到而永久占住）
        outboundOrderService.cancel(result.newDoNo(), new CancelOutOrderRo("余量也不发了"), "IT-PART-C");
        assertThat(reserved()).as("余量预占必须可释放").isEqualByComparingTo("0");
        assertThat(available()).isEqualByComparingTo("94");
        print("部分发货", "原单出库 6、余量 4 迁移至新单并可释放；实物 94");
    }

    @Test
    @DisplayName("查询与拦截：分页/详情/轨迹可用；拦截后释放预占与冻结")
    void queryAndHoldShouldWork() {
        String doNo = createOrder(new BigDecimal("5"), new BigDecimal("50.0000"), "SO-Q1").doNo();
        OutOrderPageVo page = outboundOrderService.page(new PageOutOrderRo(1, 20, WAREHOUSE, MERCHANT, null, null));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.list()).singleElement()
                .satisfies(row -> assertThat(row.doNo()).isEqualTo(doNo));
        assertThat(outboundOrderService.statusLogs(doNo)).hasSize(1);
        assertThat(outboundOrderService.detail(doNo).receiver().phoneMasked())
                .as("收件人只回掩码").contains("*");

        OutOrderDetailVo held = outboundOrderService.hold(doNo, new HoldOutOrderRo("疑似禁入品"), "IT-HOLD");
        assertThat(held.status()).isEqualTo("已拦截");
        assertThat(reserved()).as("拦截必须回滚预占").isEqualByComparingTo("0");
        assertThat(freezeStatus(doNo)).as("拦截必须释放冻结").isEqualTo("已释放");
        assertThat(held.remark()).contains("疑似禁入品");
        print("查询/拦截", "分页与轨迹可用；拦截后预占与冻结均回滚");
    }

    // ===================== 夹具与断言工具 =====================

    /**
     * 建单（标准夹具）。
     *
     * @param qty              应发数量
     * @param estimatedFreight 预估运费
     * @param sourceOrderNo    上游单号
     * @return 出库单详情
     */
    private OutOrderDetailVo createOrder(BigDecimal qty, BigDecimal estimatedFreight, String sourceOrderNo) {
        return outboundOrderService.create(createRo(qty, estimatedFreight, sourceOrderNo), "IT-" + sourceOrderNo);
    }

    /**
     * 组装建单入参。
     *
     * @param qty              应发数量
     * @param estimatedFreight 预估运费
     * @param sourceOrderNo    上游单号
     * @return 入参
     */
    private CreateOutOrderRo createRo(BigDecimal qty, BigDecimal estimatedFreight, String sourceOrderNo) {
        return new CreateOutOrderRo(WAREHOUSE, MERCHANT, "API", sourceOrderNo, "SF", "顺丰", estimatedFreight,
                new BigDecimal("2.0000"), new BigDecimal("0.1000"), "张三", "13800000000", "上海市浦东新区张江路 1 号",
                "上海市", "上海市", "浦东新区", "IT 用例", List.of(new CreateOutOrderRo.ItemRo(SKU_CODE, qty, "件")));
    }

    /**
     * 走到"待装车"（派发 → 拣货 → 称重 → 打包 → 交接）。
     *
     * @param doNo   出库单号
     * @param qty    数量
     * @param prefix 幂等键前缀
     */
    private void dispatchPickMeasurePackHandover(String doNo, BigDecimal qty, String prefix) {
        outboundOrderService.dispatch(doNo,
                new DispatchOutOrderRo(List.of(new DispatchOutOrderRo.ItemRo(1, LOCATION_CODE))), prefix + "-D");
        outboundOrderService.pickComplete(doNo,
                new PickCompleteOutOrderRo(List.of(new PickCompleteOutOrderRo.ItemRo(1, qty))), prefix + "-P");
        outboundOrderService.measure(doNo,
                new MeasureOutOrderRo(new BigDecimal("2.0000"), new BigDecimal("0.1000"), 1), prefix + "-M");
        outboundOrderService.pack(doNo, new PackOutOrderRo("WB-" + doNo, 1), prefix + "-K");
        outboundOrderService.handover(doNo, new HandoverOutOrderRo("交接"), prefix + "-H");
    }

    /**
     * 走到"已复核"（派发 → 拣货 → 称重），用于需要单独构造打包前状态的用例。
     *
     * @param doNo         出库单号
     * @param qty          数量
     * @param prefix       幂等键前缀
     * @param actualWeight 实称重量（用于构造偏差用例）
     */
    private void dispatchPickMeasure(String doNo, BigDecimal qty, String prefix, BigDecimal actualWeight) {
        outboundOrderService.dispatch(doNo,
                new DispatchOutOrderRo(List.of(new DispatchOutOrderRo.ItemRo(1, LOCATION_CODE))), prefix + "-D");
        outboundOrderService.pickComplete(doNo,
                new PickCompleteOutOrderRo(List.of(new PickCompleteOutOrderRo.ItemRo(1, qty))), prefix + "-P");
        outboundOrderService.measure(doNo,
                new MeasureOutOrderRo(actualWeight, new BigDecimal("0.1000"), 1), prefix + "-M");
    }

    /**
     * SKU 实物。
     *
     * @return 实物数量
     */
    private BigDecimal physical() {
        return decimal("SELECT physical_qty FROM iv_sku WHERE tenant_id = 1 AND sku_id = " + SKU);
    }

    /**
     * SKU 可用。
     *
     * @return 可用数量
     */
    private BigDecimal available() {
        return decimal("SELECT available_qty FROM iv_sku WHERE tenant_id = 1 AND sku_id = " + SKU);
    }

    /**
     * SKU 预占。
     *
     * @return 预占数量
     */
    private BigDecimal reserved() {
        return decimal("SELECT reserved_qty FROM iv_sku WHERE tenant_id = 1 AND sku_id = " + SKU);
    }

    /**
     * 库位已分配量。
     *
     * @return allocated 数量（无记录返回 0）
     */
    private BigDecimal allocated() {
        BigDecimal value = jdbc.queryForObject("SELECT COALESCE(SUM(allocated_qty), 0) FROM iv_location "
                + "WHERE tenant_id = 1 AND location_id = " + LOCATION, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 单据数。
     *
     * @return 条数
     */
    private long orderCount() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM out_order WHERE tenant_id = 1", Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 冻结单条数。
     *
     * @param doNo 出库单号
     * @return 条数
     */
    private long freezeCount(String doNo) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM ac_fee_freeze WHERE tenant_id = 1 AND biz_no = '"
                + doNo + "'", Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 冻结单状态。
     *
     * @param doNo 出库单号
     * @return 状态
     */
    private String freezeStatus(String doNo) {
        return jdbc.queryForObject("SELECT status FROM ac_fee_freeze WHERE tenant_id = 1 AND biz_no = '" + doNo + "'",
                String.class);
    }

    /**
     * 装运单状态。
     *
     * @param doNo 出库单号
     * @return 状态
     */
    private String shipmentStatus(String doNo) {
        return jdbc.queryForObject("SELECT status FROM lg_shipment WHERE tenant_id = 1 AND do_no = '" + doNo + "'",
                String.class);
    }

    /**
     * 状态流水条数。
     *
     * @param doNo 出库单号
     * @return 条数
     */
    private long statusLogCount(String doNo) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM pf_biz_status_log l JOIN out_order o "
                + "ON o.id = l.biz_id WHERE l.tenant_id = 1 AND o.do_no = '" + doNo + "'", Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 是否存在某个事件的轨迹。
     *
     * @param doNo  出库单号
     * @param event 事件名
     * @return 存在返回 {@code true}
     */
    private boolean trackExists(String doNo, String event) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM pf_biz_status_log l JOIN out_order o "
                + "ON o.id = l.biz_id WHERE l.tenant_id = 1 AND o.do_no = '" + doNo + "' AND l.event = '" + event + "'",
                Long.class);
        return value != null && value > 0;
    }

    /**
     * 查数量/金额。
     *
     * @param sql SQL
     * @return 值
     */
    private BigDecimal decimal(String sql) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 打印实验报告行。
     *
     * @param step   步骤
     * @param detail 明细
     */
    private void print(String step, String detail) {
        System.out.printf("[W6-OUT] %s | %s%n", step, detail);
    }
}
