package com.wms.platform.integration;

import com.wms.platform.common.datasource.DataSourceRoute;
import com.wms.platform.common.datasource.ReadOnly;
import com.wms.platform.common.context.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 读写分离集成测试（P5.3）。
 *
 * <p><b>怎么"决定性地"证明读真的走了从库</b>（不是"配了就算"）：
 * 主从复制是异步的，所以只要**制造一个"主库有、从库还没有"的瞬间**，就能看出路由：
 * <ol>
 *   <li>暂停从库的复制线程（{@code STOP REPLICA}）→ 从库从此落后主库；</li>
 *   <li>向**主库**写入一行标记（走主库连接）；</li>
 *   <li>用 {@link ReadOnly} 标注的方法去读 → **读不到**标记（说明读的是从库！）；</li>
 *   <li>用未标注的方法去读 → **读得到**标记（说明默认走主库）；</li>
 *   <li>恢复复制（{@code START REPLICA}）→ 稍等后 {@code @ReadOnly} 也能读到（从库追上了）。</li>
 * </ol>
 * 第 3 步与第 4 步的差异就是"读写确实被分流到不同数据库"的铁证。
 *
 * <p><b>同时验证安全默认</b>：未标注的方法绝不会跑到从库上（否则"写后立即读"会读到旧值）。
 */
@SpringBootTest
@org.springframework.context.annotation.Import(ReadWriteSplitIT.ProbeConfig.class)
@DisplayName("P5.3 读写分离：@ReadOnly 走从库、其余走主库（含复制延迟下的分流铁证）")
class ReadWriteSplitIT {

    /** 探针表名（临时建、用完删；只用于本用例）。 */
    private static final String PROBE_TABLE = "pf_rw_split_probe";

    /** 数据源（路由数据源）。 */
    @Autowired
    private DataSource dataSource;

    /** 主库直连（绕过路由，用于制造"主库有、从库没有"的状态）。 */
    @Autowired
    private ReadWriteSplitIT.ProbeService probeService;

    /**
     * 数据源：主库 + 从库（无从库则跳过本用例）。
     *
     * @param registry 属性注册器
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("poc.mysql.user", "root"));
            registry.add("spring.datasource.password", () -> System.getProperty("poc.mysql.password", ""));
        }
        String replica = System.getProperty("poc.mysql.replica.url", System.getenv("POC_MYSQL_REPLICA_URL"));
        if (replica != null && !replica.isBlank()) {
            registry.add("spring.datasource.replica.url", () -> replica);
            registry.add("spring.datasource.replica.username",
                    () -> System.getProperty("poc.mysql.replica.user", "root"));
            registry.add("spring.datasource.replica.password",
                    () -> System.getProperty("poc.mysql.replica.password", ""));
        }
        registry.add("spring.flyway.enabled", () -> true);
    }

    /**
     * 没有从库就跳过（CI 未必配从库）。
     */
    @BeforeAll
    static void requireReplica() {
        String replica = System.getProperty("poc.mysql.replica.url", System.getenv("POC_MYSQL_REPLICA_URL"));
        assumeTrue(replica != null && !replica.isBlank() && reachable(replica),
                "未配置/不可达从库，跳过读写分离用例（传 -Dpoc.mysql.replica.url=...）");
    }

    @Test
    @DisplayName("铁证：停复制→写主库→@ReadOnly 读不到、默认读得到；恢复复制后 @ReadOnly 能读到")
    void readOnlyGoesToReplicaWhileDefaultGoesToPrimary() {
        TenantContext.set(1L, 1L, Set.of(), null);

        // 0. 准备探针表（主库建，会复制到从库）
        probeService.createProbeTable();
        probeService.deleteProbeRows();
        sleep(1500);

        try {
            // 1. 暂停从库复制，并**等到真的停住**再写主库
            //    （第一版没等：STOP 发出后 SQL 线程可能还会把紧接着的这条写应用掉，
            //      于是断言变成偶发失败——这类"时序不严"会让好代码看起来是坏的）
            probeService.stopReplica();
            assertThat(probeService.awaitReplicaStopped(10_000))
                    .as("必须先确认复制真的停了（IO 与 SQL 线程都为 No），否则本用例的结论不成立")
                    .isTrue();
            String marker = "MARKER-" + System.nanoTime();
            // 2. 向主库写入标记（走主库连接：未标注的方法）
            probeService.insertViaPrimary(marker);

            // 3. @ReadOnly 读 → 必须**读不到**（因为读的是落后中的从库）
            int viaReplica = probeService.countViaReadOnly(marker);
            assertThat(viaReplica)
                    .as("带 @ReadOnly 的读应走从库；从库复制已暂停，因此读不到主库刚写的标记 → 说明确实在读从库")
                    .isZero();

            // 4. 未标注读 → 必须**读得到**（走主库）
            int viaPrimary = probeService.countDefault(marker);
            assertThat(viaPrimary)
                    .as("未标注的方法必须走主库（安全默认）")
                    .isEqualTo(1);

            // 5. 恢复复制 → 从库追上后 @ReadOnly 也能读到（证明上一步的 0 是"延迟"，不是"表不存在"）
            probeService.startReplica();
            boolean caughtUp = false;
            for (int i = 0; i < 20 && !caughtUp; i++) {
                sleep(500);
                caughtUp = probeService.countViaReadOnly(marker) == 1;
            }
            assertThat(caughtUp).as("恢复复制后，从库应在数秒内追上").isTrue();

            print("分流铁证", "@ReadOnly 读从库（停复制时读不到主库新写的数据）｜默认读主库（读得到）"
                    + "｜恢复复制后从库追上（读到）");
        } finally {
            // 无论成败都恢复复制并清理，避免影响后续用例/环境
            probeService.startReplicaQuietly();
            probeService.dropProbeTableQuietly();
        }
    }

    @Test
    @DisplayName("安全默认：@ReadOnly 之外的读写不会被路由到从库（从库只读，写进去会直接报错）")
    void defaultRouteIsPrimary() {
        TenantContext.set(1L, 1L, Set.of(), null);
        probeService.createProbeTable();
        try {
            // 未标注 → 走主库：写入必须成功（若被误路由到从库，super_read_only 会直接拒绝写入）
            String marker = "WRITE-" + System.nanoTime();
            probeService.insertViaPrimary(marker);
            assertThat(probeService.countDefault(marker)).isEqualTo(1);
            print("安全默认", "未标注的方法在主库执行写入成功（若误路由到只读从库会直接报错）");
        } finally {
            probeService.dropProbeTableQuietly();
        }
    }

    /**
     * 断言当前线程路由已归位（防止 ThreadLocal 泄漏）。
     */
    @Test
    @DisplayName("路由清理：注解方法执行后线程路由必须回到主库（否则线程池复用会串号）")
    void routeIsClearedAfterAnnotatedMethod() {
        TenantContext.set(1L, 1L, Set.of(), null);
        probeService.createProbeTable();
        try {
            // 先让从库追上新表并写入一行：否则读从库时会因"表还不存在"报 BadSqlGrammar
            // （第一版就踩了这个：把复制延迟误当成路由缺陷。等待到从库可见再断言路由）
            String marker = "ROUTE-" + System.nanoTime();
            probeService.insertViaPrimary(marker);
            for (int i = 0; i < 20 && probeService.countViaReadOnly(marker) == 0; i++) {
                sleep(500);
            }
            probeService.countViaReadOnly(marker);
            assertThat(DataSourceRoute.current())
                    .as("切面的 finally 必须清理 ThreadLocal，否则后续请求会被误导到从库")
                    .isEqualTo(DataSourceRoute.PRIMARY);
            print("路由清理", "注解方法返回后线程路由 = " + DataSourceRoute.current());
        } finally {
            probeService.dropProbeTableQuietly();
        }
    }

    /**
     * 探测可达性。
     *
     * @param jdbcUrl JDBC URL
     * @return 可达返回 true
     */
    private static boolean reachable(String jdbcUrl) {
        try {
            String hostPort = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2).split("/")[0];
            String[] parts = hostPort.split(":");
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 3306;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(parts[0], port), 1000);
                return true;
            }
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 等待。
     *
     * @param millis 毫秒
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 打印结论（用例输出即证据）。
     *
     * @param title  标题
     * @param detail 细节
     */
    private void print(String title, String detail) {
        System.out.println("[P5.3] " + title + " | " + detail);
    }

    /**
     * 只用在本用例的装配：把探针服务注册为 Bean。
     *
     * <p>为什么要 @TestConfiguration + @Import 而不是给嵌套类加 @Service：
     * 组件扫描**不会注册嵌套静态类**（实测报 No qualifying bean），
     * 而放到主扫描路径下又会污染所有其它 IT 的应用上下文。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class ProbeConfig {

        /**
         * 探针服务。
         *
         * @param dataSource 路由数据源
         * @param properties 数据源属性
         * @return 探针服务
         */
        @org.springframework.context.annotation.Bean
        ProbeService probeService(DataSource dataSource,
                                  org.springframework.boot.autoconfigure.jdbc.DataSourceProperties properties) {
            return new ProbeService(dataSource, properties);
        }
    }

    /**
     * 测试用探针服务：分别提供"走主库"和"走从库（@ReadOnly）"的读写入口。
     *
     * <p>为什么需要它：应用里现有的 @ReadOnly 方法读的都是业务表，无法制造
     * "主库有、从库没有"的受控状态；用一个专用探针表才能把路由行为**证明**出来。
     */
    public static class ProbeService {

        /** 路由数据源（未标注 = 主库）。 */
        private final JdbcTemplate primary;

        /** 独立的从库连接（用于对照/恢复复制等管理动作，绕开路由）。 */
        private final JdbcTemplate replica;

        /**
         * 构造。
         *
         * @param dataSource 路由数据源
         * @param properties 数据源属性（用于直连从库做管理动作）
         */
        public ProbeService(DataSource dataSource, org.springframework.boot.autoconfigure.jdbc.DataSourceProperties properties) {
            this.primary = new JdbcTemplate(dataSource);
            String replicaUrl = System.getProperty("poc.mysql.replica.url", System.getenv("POC_MYSQL_REPLICA_URL"));
            if (replicaUrl == null || replicaUrl.isBlank()) {
                this.replica = null;
            } else {
                com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
                ds.setJdbcUrl(replicaUrl);
                ds.setUsername(System.getProperty("poc.mysql.replica.user", "root"));
                ds.setPassword(System.getProperty("poc.mysql.replica.password", ""));
                ds.setMaximumPoolSize(2);
                this.replica = new JdbcTemplate(ds);
            }
        }

        /** 主库建探针表（会通过复制同步到从库）。 */
        public void createProbeTable() {
            primary.execute("CREATE TABLE IF NOT EXISTS " + PROBE_TABLE
                    + " (id BIGINT PRIMARY KEY AUTO_INCREMENT, marker VARCHAR(64) NOT NULL) ENGINE=InnoDB");
        }

        /** 清空探针表。 */
        public void deleteProbeRows() {
            primary.execute("DELETE FROM " + PROBE_TABLE);
        }

        /**
         * 走主库写入标记。
         *
         * @param marker 标记
         */
        public void insertViaPrimary(String marker) {
            primary.update("INSERT INTO " + PROBE_TABLE + " (marker) VALUES (?)", marker);
        }

        /**
         * 走主库计数（未标注 → 默认主库）。
         *
         * @param marker 标记
         * @return 条数
         */
        public int countDefault(String marker) {
            Integer n = primary.queryForObject("SELECT COUNT(*) FROM " + PROBE_TABLE + " WHERE marker = ?",
                    Integer.class, marker);
            return n == null ? 0 : n;
        }

        /**
         * 走从库计数（标注 @ReadOnly → 路由到从库）。
         *
         * @param marker 标记
         * @return 条数
         */
        @ReadOnly("P5.3 测试探针：读延迟不影响正确性，仅用于证明读被路由到从库")
        public int countViaReadOnly(String marker) {
            // 注意：这里用的是注入的路由数据源 —— 切面已把线程路由切到从库
            Integer n = primary.queryForObject("SELECT COUNT(*) FROM " + PROBE_TABLE + " WHERE marker = ?",
                    Integer.class, marker);
            return n == null ? 0 : n;
        }

        /** 暂停从库复制（直连从库执行管理命令）。 */
        public void stopReplica() {
            requireReplicaConnection().execute("STOP REPLICA");
        }

        /**
         * 等复制**真的**停住（IO 与 SQL 线程都为 No）。
         *
         * <p><b>为什么必须等</b>：{@code STOP REPLICA} 发出后，SQL 线程可能还会把手头的事务应用掉；
         * 若紧接着就往主库写标记，从库有可能"恰好"也应用了它——断言就会变成**偶发失败**。
         * 这类时序不严会让正确的代码看起来是坏的（第一版就踩了）。
         *
         * @param timeoutMs 超时毫秒
         * @return 已停止返回 true
         */
        public boolean awaitReplicaStopped(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                java.util.Map<String, Object> row = requireReplicaConnection()
                        .queryForMap("SHOW REPLICA STATUS");
                boolean ioRunning = "Yes".equalsIgnoreCase(String.valueOf(row.get("Replica_IO_Running")));
                boolean sqlRunning = "Yes".equalsIgnoreCase(String.valueOf(row.get("Replica_SQL_Running")));
                if (!ioRunning && !sqlRunning) {
                    return true;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        /** 恢复从库复制。 */
        public void startReplica() {
            requireReplicaConnection().execute("START REPLICA");
        }

        /** 静默恢复复制（清理用，不抛异常）。 */
        public void startReplicaQuietly() {
            try {
                startReplica();
            } catch (Exception ignored) {
                // 清理动作失败不影响用例结论
            }
        }

        /** 静默删除探针表。 */
        public void dropProbeTableQuietly() {
            try {
                primary.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
            } catch (Exception ignored) {
                // 清理动作失败不影响用例结论
            }
        }

        /**
         * 要求从库连接可用。
         *
         * @return 从库 JdbcTemplate
         */
        private JdbcTemplate requireReplicaConnection() {
            if (replica == null) {
                throw new IllegalStateException("未配置从库（-Dpoc.mysql.replica.url=...）");
            }
            return replica;
        }
    }
}
