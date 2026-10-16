package com.wms.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 智能化仓库平台 · 模块化单体启动类。
 *
 * <p>架构取舍（{@code 项目一方案.md} §9、{@code 02} §5.2 M1）：
 * <ul>
 *   <li><b>模块化单体起步</b>：一期 12 周 + 单仓试点的规模下，微服务的分布式代价（服务发现、跨服务事务、
 *       链路排障）远大于收益；先以包边界 + ArchUnit 守护隔离度，确有需要再拆网关/物流/调度；</li>
 *   <li><b>共库强一致</b>：订单、库存、费用、资金在同一库，靠本地事务保证强一致，**不引入分布式事务**；</li>
 *   <li>模块扫描根包为 {@code com.wms.platform}，因此 {@code wms-common} 中的 {@code @Mapper}
 *       （如 IdempotentMapper）会被自动发现，无需额外 {@code @MapperScan}。</li>
 * </ul>
 */
@SpringBootApplication
@EnableTransactionManagement
public class WmsPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(WmsPlatformApplication.class, args);
    }
}
