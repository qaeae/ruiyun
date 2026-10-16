package com.wms.platform.config;

import com.wms.platform.common.datasource.ReadWriteRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 数据源装配（P5.3 读写分离）。
 *
 * <p><b>两种形态，按是否配置从库自动选择</b>：
 * <ul>
 *   <li><b>未配置从库（默认）</b>：什么都不做，Spring Boot 用 {@code spring.datasource.*} 自动装配单库——
 *       与过去完全一致，本地/CI 零额外复杂度；</li>
 *   <li><b>配置了 {@code spring.datasource.replica.url}</b>：装配 {@link ReadWriteRoutingDataSource}，
 *       主库照旧用 {@code spring.datasource.*}，从库单独一份配置。</li>
 * </ul>
 *
 * <p><b>为什么主库复用 {@code spring.datasource.*}</b>：迁移成本最低——既有部署、IT 的
 * `@DynamicPropertySource` 都是按 `spring.datasource.*` 传参的，读写分离不该逼着所有环境改配置项名。
 *
 * <p><b>从库连接池的必要设置</b>：
 * <ul>
 *   <li>{@code readOnly=true}：让驱动与连接池层面就声明只读（多一层保险，误写会被拒）；</li>
 *   <li>连接数按"读多"给（默认 20）：只读查询通常数量大、单个耗时长。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ReplicaDataSourceConfig {

    /**
     * 读写分离路由数据源。
     *
     * <p><b>一律装配，按"从库 URL 是否为空"决定退化还是分流</b>。
     *
     * <p><b>为什么不写 {@code @ConditionalOnProperty}（真实踩坑，全量测试抓到）</b>：
     * yml 里是 {@code url: ${DB_REPLICA_URL:}}（默认**空串**）时，属性"存在但为空"，
     * {@code @ConditionalOnProperty} 判定**条件满足** → 用**空 URL** 建从库池 →
     * 未配从库的环境（CI/其它 IT）里所有 {@code @ReadOnly} 查询直接连接失败。
     * 改为按值判空后，"空串"与"未配置"同等对待，语义唯一。
     *
     * <p>顺带消除另一个隐患：{@code @ReadOnly} 切面不再需要"有条件地注册"——
     * 未配从库时路由键指向主库即可（此前"配置类与切面必须用同一条件前缀"的一致性问题，
     * 正是导致切面漏注册、读全落主库却无报错的根因）。
     *
     * @param properties      主库属性（来自 spring.datasource.*）
     * @param replicaUrl      从库 URL（空 = 未配置，@ReadOnly 退化为读主库）
     * @param replicaUser     从库用户
     * @param replicaPwd      从库口令
     * @param replicaPoolSize 从库连接池大小
     * @return 路由数据源（标记 @Primary，让 MyBatis/JdbcTemplate 都用它）
     */
    @Bean
    @Primary
    public DataSource routingDataSource(DataSourceProperties properties,
                                        @Value("${spring.datasource.replica.url:}") String replicaUrl,
                                        @Value("${spring.datasource.replica.username:}") String replicaUser,
                                        @Value("${spring.datasource.replica.password:}") String replicaPwd,
                                        @Value("${spring.datasource.replica.pool-size:20}") int replicaPoolSize) {
        // 1. 主库（复用 spring.datasource.*，读与写都在这里）
        HikariDataSource primary = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        primary.setPoolName("wms-primary");
        log.info("[数据源] 主库（写 + 默认读）url={}", mask(properties.getUrl()));

        // 2. 未配置从库 → 两个路由键都指向主库：@ReadOnly 安全退化，不报错也不读到脏数据
        if (!StringUtils.hasText(replicaUrl)) {
            log.info("[数据源] 未配置从库（spring.datasource.replica.url 为空）："
                    + "@ReadOnly 退化为读主库，行为与单库一致");
            return new ReadWriteRoutingDataSource(primary, primary);
        }

        // 3. 配了从库 → 只读连接池（readOnly=true 让"误写"在驱动层就被拒）
        HikariDataSource replica = new HikariDataSource();
        replica.setJdbcUrl(replicaUrl);
        replica.setUsername(replicaUser);
        replica.setPassword(replicaPwd);
        replica.setDriverClassName(properties.getDriverClassName() == null
                ? "com.mysql.cj.jdbc.Driver" : properties.getDriverClassName());
        replica.setMaximumPoolSize(replicaPoolSize);
        replica.setReadOnly(true);
        replica.setPoolName("wms-replica");
        log.info("[数据源] 只读从库 url={} poolSize={} readOnly=true（读写分离已启用）",
                mask(replicaUrl), replicaPoolSize);

        // 4. 路由（默认主库；仅 @ReadOnly 标注的方法走从库）
        return new ReadWriteRoutingDataSource(primary, replica);
    }

    /**
     * 隐藏 URL 中的口令（日志安全：不能把带口令的 JDBC URL 打进日志）。
     *
     * @param url JDBC URL
     * @return 脱敏后的 URL
     */
    private String mask(String url) {
        if (url == null) {
            return "null";
        }
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }
}
