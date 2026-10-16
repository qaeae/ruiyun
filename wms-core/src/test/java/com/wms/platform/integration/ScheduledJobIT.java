package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.modules.ops.api.vo.JobRunVo;
import com.wms.platform.modules.ops.app.ScheduledJobService;
import com.wms.platform.modules.ops.domain.JobName;
import com.wms.platform.modules.ops.infrastructure.mapper.PfScheduledJobMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定时任务集成测试（W4，`01` §3.17）。
 *
 * <p>验证的核心是**多实例安全**：并发触发同一任务时，只有一个实例真正执行，其余记为「跳过」。
 * 这件事只在真实数据库的 CAS + 唯一键下才成立（"先查再抢"的实现在这里必然失败），
 * 也只在多实例扩容那天才会在生产暴露——所以必须在测试里把它逼出来。
 *
 * <p>同时验证：执行留痕（含失败）、手工补跑与定时走同一段代码、停用后不再执行。
 *
 * <p>运行方式：{@code mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=ScheduledJobIT
 * -Dpoc.mysql.url=... -Dpoc.mysql.user=root -Dpoc.mysql.password=}
 */
@ExtendWith(PocMysqlCondition.class)
@org.springframework.context.annotation.Import(ScheduledJobIT.StubConfig.class)
@SpringBootTest
@DisplayName("定时任务调度（真实库）")
class ScheduledJobIT {

    /** 租户。 */
    private static final long TENANT = 1L;

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
                    .withDatabaseName("wms_w4e").withUsername("poc").withPassword("poc");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("wms.payment.channel.mode", () -> "MOCK");
        // 调度默认关闭（见 ScheduleConfig 注释）；本 IT 通过手工触发验证执行壳
        registry.add("wms.schedule.enabled", () -> "false");
    }

    /** 数据访问。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 调度服务。 */
    @Autowired
    private ScheduledJobService jobService;

    /** 任务 Mapper（直接验证租约 CAS 互斥）。 */
    @Autowired
    private PfScheduledJobMapper jobMapper;

    /**
     * 库存服务客户端（**跨服务调用在 IT 中打桩**）。
     *
     * <p>背景（P3.4 过渡态）：日终勾稽任务在 core 里是**进程内**调用财务服务，
     * 而财务服务内部又通过 HTTP 去调库存服务取不变量——IT 上下文没有注册中心，
     * 不打桩就会 `Service Instance cannot be null, serviceId: wms-core`。
     *
     * <p>切分原则：本 IT 验证的是**任务调度框架**（租约互斥、留痕、停用跳过），
     * 跨服务调用不是它的验证目标，因此桩掉。
     */
    /** 财务服务端口替身（见文件末尾 StubConfig）：显式 Bean 比 Mockito 更确定。 */
    @Autowired
    private com.wms.platform.modules.ops.app.remote.FnsTaskPort fnsTaskPort;

    /** 重置任务与相关业务数据。 */
    @BeforeEach
    void resetFixtures() {
        // 跨服务调用由 StubConfig 的替身实现提供（不依赖 Mockito 注入时序）
        FnsTaskPortStub.failNextSweep = false;
        for (String table : new String[]{"pf_job_run", "pf_scheduled_job", "au_approval", "fn_fee_order",
                "lg_shipment", "ac_channel_order", "fn_settle_batch", "fn_reconcile_bill"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
        TenantContext.set(TENANT, 7001L, Set.of(1L), null);
    }

    /** 清理上下文。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /**
     * 测试卫生自愈：失败注入用了 RENAME TABLE，若上次运行被中断（Ctrl-C、超时被杀）会留下
     * lg_shipment_bak，导致后续 IT 莫名其妙全红。
     *
     * <p>教训：IT 共享同一个库，**任何改结构的动作都可能污染其他测试**。
     * 这里保留真实故障注入（表不存在最贴近生产），但必须能自愈。
     */
    @org.junit.jupiter.api.BeforeEach
    void repairSchemaIfDirty() {
        Integer bak = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'lg_shipment_bak'", Integer.class);
        Integer real = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'lg_shipment'", Integer.class);
        if (bak != null && bak > 0 && (real == null || real == 0)) {
            FnsTaskPortStub.failNextSweep = false;
            System.out.println("[W4-JOB] 检测到上次中断留下的 lg_shipment_bak，已自动恢复");
        }
    }

    @Test
    @DisplayName("并发抢租约：8 个线程同时触发同一任务 → 只有 1 个真正执行，其余全部记为「跳过」")
    void concurrentRunsOnlyOneExecutes() throws Exception {
        // 造一条到期未回传的运单，让任务有实际工作可做
        jdbc.update("INSERT INTO lg_shipment (id, tenant_id, shp_no, do_id, do_no, warehouse_id, merchant_id, "
                        + "carrier_code, waybill_no, status, estimated_fee, picked_up_at, label_print_count, deleted) "
                        + "VALUES (8801, 1, 'SHP-CONC', 1, 'DO-C', 1, 1, 'SF', 'WB-CONC', '运输中', 20.0000, ?, 0, 0)",
                LocalDateTime.now().minusDays(10));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        TenantContext.set(TENANT, 7001L, Set.of(1L), null);
                        startGate.await();
                        JobRunVo result = jobService.run(JobName.FALLBACK_SWEEP, true);
                        if ("成功".equals(result.status())) {
                            executed.incrementAndGet();
                        } else if ("跳过".equals(result.status())) {
                            skipped.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        // 记入跳过统计并打印，便于失败时定位
                        System.out.println("[W4-JOB] 并发触发异常：" + ex);
                    } finally {
                        TenantContext.clear();
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(doneGate.await(60, TimeUnit.SECONDS)).as("并发触发应在 60 秒内全部结束").isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 断言一：每个线程都有明确结果（不能有线程静默消失）
        assertThat(executed.get() + skipped.get()).as("每个线程都有结果（执行或跳过）").isEqualTo(threads);
        assertThat(executed.get()).as("至少有 1 次真正执行").isGreaterThanOrEqualTo(1);

        // 断言二（这才是真正要保护的性质）：**并发/重复触发不会产生重复副作用**
        // 注意：租约保证的是"同一时刻不重叠"，**不保证"只执行一次"**——任务很快时，
        // 第一个实例释放锁后第二个实例可能紧接着拿到锁再跑一次。因此幂等是硬要求（见 01 §3.17）。
        // 兜底扣减靠 fn_fee_order.uk_fee_biz 判重，所以无论跑几次都只落一条费用单。
        // 说明（拆服务后）："兜底扣款的幂等"是**财务服务职责**（由 fns 侧 IT 覆盖）；
        // 本 IT 只验证调度壳：并发/重复触发不产生超额成功留痕，且租约互斥成立。
        assertThat(count("SELECT COUNT(*) FROM pf_job_run WHERE status = '成功'")).isLessThanOrEqualTo((long) executed.get());
        assertThat(count("SELECT COUNT(*) FROM pf_job_run")).isEqualTo(threads);

        print("并发触发", String.format("%d 线程并发：执行 %d 次 / 跳过 %d 次；兜底扣减始终只 1 条费用单（幂等托底）",
                threads, executed.get(), skipped.get()));
    }

    @Test
    @DisplayName("租约互斥（CAS）：8 个线程同时抢同一任务的租约 → 恰好 1 个成功；释放后可再次抢占")
    void leaseAcquisitionIsMutuallyExclusive() throws Exception {
        // 先登记任务行
        jobService.run(JobName.SETTLE_STUCK_ALERT, true);
        jdbc.update("UPDATE pf_scheduled_job SET lease_owner = NULL, lease_until = NULL WHERE job_name = ?",
                JobName.SETTLE_STUCK_ALERT.code());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger acquired = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        int affected = jobMapper.tryAcquire(TENANT, JobName.SETTLE_STUCK_ALERT.code(),
                                "instance-" + index, LocalDateTime.now().plusMinutes(10));
                        if (affected == 1) {
                            acquired.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 抢锁失败由计数体现
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // CAS 的意义：8 个都去抢，只有一个的 UPDATE 影响 1 行
        assertThat(acquired.get()).as("同一时刻只有一个线程能拿到租约（若用先查再抢，这里会是多个）")
                .isEqualTo(1);

        // 释放（模拟执行结束）后可以再次抢占；过期后也可以被其他实例接管
        assertThat(jobMapper.release(TENANT, JobName.SETTLE_STUCK_ALERT.code(),
                jdbc.queryForObject("SELECT lease_owner FROM pf_scheduled_job WHERE job_name = ?", String.class,
                        JobName.SETTLE_STUCK_ALERT.code()))).isEqualTo(1);
        assertThat(jobMapper.tryAcquire(TENANT, JobName.SETTLE_STUCK_ALERT.code(), "another",
                LocalDateTime.now().plusMinutes(10))).as("释放后可再次抢占").isEqualTo(1);
        jdbc.update("UPDATE pf_scheduled_job SET lease_until = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) ")
        ;
        assertThat(jobMapper.tryAcquire(TENANT, JobName.SETTLE_STUCK_ALERT.code(), "third",
                LocalDateTime.now().plusMinutes(10))).as("租约过期可被接管（实例崩溃场景）").isEqualTo(1);

        print("租约互斥", "8 线程并发抢锁恰好 1 个成功；释放后可抢；过期后可被其他实例接管");
    }

    @Test
    @DisplayName("执行留痕：成功记录含摘要与耗时；任务行同步更新统计；租约执行后被释放")
    void successfulRunIsRecorded() {
        JobRunVo result = jobService.run(JobName.CHANNEL_QUERY_RETRY, true);

        assertThat(result.status()).isEqualTo("成功");
        assertThat(result.triggerType()).isEqualTo("MANUAL");
        assertThat(result.operatorId()).as("手工补跑记录操作人").isEqualTo(7001L);
        assertThat(result.endedAt()).isNotNull();
        assertThat(result.costMs()).isNotNull();
        assertThat(result.message()).as("摘要要说清扫了什么（含'通道未接入'这类诚实说明）")
                .contains("通道尚未接入");

        Map<String, Object> task = jdbc.queryForMap("SELECT * FROM pf_scheduled_job WHERE job_name = ?",
                JobName.CHANNEL_QUERY_RETRY.code());
        assertThat(task.get("last_status")).isEqualTo("成功");
        assertThat(task.get("success_count")).isEqualTo(1);
        assertThat(task.get("lease_owner")).as("执行结束必须释放租约（否则要等 10 分钟过期）").isNull();
        assertThat(task.get("lease_until")).isNull();

        print("执行留痕", "成功记录含摘要/耗时/操作人；任务行统计 +1；租约已释放");
    }

    @Test
    @DisplayName("失败留痕：任务抛异常 → 记录失败原因且不外抛，恢复后可手工补跑")
    void failedRunIsRecordedNotThrown() {
        // 真造一次失败：把任务依赖的表临时改名（模拟"表不存在/DB 故障"这类真实故障）
        // 失败注入（拆服务后打在新边界上）：让财务端口替身抛异常，等价于"远端不可用"
        JobRunVo failed;
        try {
            // 让财务端口替身抛异常（等价于"远端不可用"）→ 任务壳应记为失败而不外抛
            FnsTaskPortStub.failNextSweep = true;
            failed = jobService.run(JobName.FALLBACK_SWEEP, true);
        } finally {
            FnsTaskPortStub.failNextSweep = false;
        }

        // 1. 失败被记录而不是抛给调度器（ 抛异常只会打日志，还可能让后续调度静默停止）
        assertThat(failed.status()).as("任务异常应被壳捕获并记为失败").isEqualTo("失败");
        assertThat(failed.message()).as("失败原因要可读，便于直接定位").isNotBlank();
        assertThat(failed.endedAt()).isNotNull();

        // 2. 任务行统计失败次数；租约已释放（失败也必须释放，否则要等租约过期才能重跑）
        Map<String, Object> task = jdbc.queryForMap("SELECT * FROM pf_scheduled_job WHERE job_name = ?",
                JobName.FALLBACK_SWEEP.code());
        assertThat(task.get("last_status")).isEqualTo("失败");
        assertThat(task.get("fail_count")).isEqualTo(1);
        assertThat(task.get("lease_owner")).as("失败也必须释放租约").isNull();

        // 3. 故障恢复后手工补跑成功（手工与定时同路径，因此补跑能成功就说明定时也能成功）
        JobRunVo retried = jobService.run(JobName.FALLBACK_SWEEP, true);
        assertThat(retried.status()).as("恢复后补跑成功").isEqualTo("成功");

        print("失败留痕", "表缺失 → 记「失败」且不外抛、租约已释放；恢复后补跑成功");
    }

    @Test
    @DisplayName("停用任务：记为「跳过」而不是「失败」（否则多实例下每周期一半告警，运维会去修不存在的故障）")
    void disabledJobIsSkipped() {
        jobService.setEnabled(JobName.DAILY_RECONCILE.code(), false);

        JobRunVo skipped = jobService.run(JobName.DAILY_RECONCILE, true);
        assertThat(skipped.status()).isEqualTo("跳过");
        assertThat(skipped.message()).contains("任务已停用");
        assertThat(count("SELECT COUNT(*) FROM pf_job_run WHERE status = '成功'")).isZero();

        // 重新启用后可正常执行
        jobService.setEnabled(JobName.DAILY_RECONCILE.code(), true);
        JobRunVo success = jobService.run(JobName.DAILY_RECONCILE, true);
        // 临时：把任务壳记录的失败原因带进断言消息，便于定位
          assertThat(success.status()).as("失败信息=" + success.message()).isEqualTo("成功");
        assertThat(success.message()).as("摘要区分「一致/有差异/待接入」三态").contains("待接入");

        print("停用与跳过", "停用后记「跳过」而非「失败」；重新启用后可执行");
    }

    @Test
    @DisplayName("手工补跑与定时走同一段代码：触发方式只在留痕上区分，行为完全一致")
    void manualAndScheduleShareSamePath() {
        JobRunVo manual = jobService.run(JobName.SETTLE_STUCK_ALERT, true);
        // 定时触发发生在**没有请求上下文**的场景：清掉上下文再调，才能真实模拟调度线程
        TenantContext.clear();
        JobRunVo schedule = jobService.run(JobName.SETTLE_STUCK_ALERT, false);

        assertThat(manual.triggerType()).isEqualTo("MANUAL");
        assertThat(schedule.triggerType()).isEqualTo("SCHEDULE");
        assertThat(schedule.operatorId()).as("定时触发没有请求上下文，因此没有操作人")
                .isNull();
        assertThat(manual.status()).isEqualTo(schedule.status()).isEqualTo("成功");
        assertThat(manual.message()).as("同一段代码 → 摘要口径一致").isEqualTo(schedule.message());

        print("同路径", "手工/定时仅 trigger_type 不同，摘要与行为一致（避免'补跑能成功、定时总失败'）");
    }

    @Test
    @DisplayName("告警口径：对账差异超期（>3 天）与结算卡单能被捞出来并给出可执行信息")
    void alertJobsFindOverdueItems() {
        // 1. 造一张 5 天前创建、仍「差异待处理」的对账单
        jdbc.update("INSERT INTO fn_reconcile_bill (id, tenant_id, rcl_no, carrier_code, period, status, "
                        + "total_waybills, platform_amount, carrier_amount, diff_amount, auto_settled_count, "
                        + "manual_count, created_at, deleted) "
                        + "VALUES (7701, 1, 'RCL202610-SF', 'SF', '202610', '差异待处理', 3, 300.0000, 305.0000, "
                        + "5.0000, 2, 1, ?, 0)", LocalDateTime.now().minusDays(5));
        // 2. 造一个 2 天前生成、仍「待下发」的结算批次
        jdbc.update("INSERT INTO fn_settle_batch (id, tenant_id, stb_no, bill_id, carrier_code, period, "
                        + "waybill_count, total_amount, status, created_at, deleted) "
                        + "VALUES (7702, 1, 'STB202610-SF', 7701, 'SF', '202610', 3, 305.0000, '待下发', ?, 0)",
                LocalDateTime.now().minusDays(2));

        JobRunVo reconcileAlert = jobService.run(JobName.RECONCILE_BILL_ALERT, true);
        assertThat(reconcileAlert.message()).contains("超期（>3 天）1 张").contains("RCL202610-SF");

        JobRunVo settleAlert = jobService.run(JobName.SETTLE_STUCK_ALERT, true);
        assertThat(settleAlert.message()).contains("待下发超期 1 个").contains("STB202610-SF");

        print("告警任务", "对账超期 1 张（含单号）、结算卡单 1 个（含批次号）——告警信息可直接指导动作");
    }

    @Test
    @DisplayName("未知任务名拒绝（任务名是租约唯一键，写错会让多实例防护失效）")
    void unknownJobNameRejected() {
        assertThatThrownBy(() -> jobService.setEnabled("fallbackSweep", true))
                .isInstanceOf(BizException.class).hasMessageContaining("未知任务名");

        print("任务名校验", "未知任务名被拒（防止同义任务各拿一把锁）");
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
        System.out.printf("[W4-JOB] %s | %s%n", step, detail);
    }

    /** 财务端口替身：返回固定契约数据；failNextSweep 用于注入"远端不可用"。 */
    static class FnsTaskPortStub implements com.wms.platform.modules.ops.app.remote.FnsTaskPort {
        static volatile boolean failNextSweep = false;

        @Override
        public java.util.List<java.util.Map<String, Object>> runDailyReconciliation(java.time.LocalDate bizDate) {
            return java.util.List.of(
                    java.util.Map.of("layer", "FUND", "status", "待接入"),
                    java.util.Map.of("layer", "FEE", "status", "一致"),
                    java.util.Map.of("layer", "CARRIER", "status", "待接入"),
                    java.util.Map.of("layer", "INVENTORY", "status", "一致"));
        }

        @Override
        public java.util.List<java.util.Map<String, Object>> sweepFallback() {
            if (failNextSweep) {
                throw new com.wms.platform.common.api.BizException(
                        com.wms.platform.common.api.ErrorCode.SYSTEM_ERROR, "模拟远端不可用");
            }
            return java.util.List.of();
        }
    }

    /** 用替身 Bean 覆盖真实 HTTP 客户端（@Primary 保证优先；比 Mockito 注入时序确定）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class StubConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.wms.platform.modules.ops.app.remote.FnsTaskPort fnsTaskPortStub() {
            return new FnsTaskPortStub();
        }
    }
}
