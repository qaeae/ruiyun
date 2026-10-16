package com.wms.platform.common.datasource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 读写分离路由数据源（P5.3）。
 *
 * <p><b>路由规则</b>：{@link DataSourceRoute#current()} 为 {@code replica} 时走从库，否则走主库。
 * 由于 {@link ReadOnly} 是**显式标注**的，未标注的一切读写都落主库——这是刻意的安全默认
 * （理由见 {@link ReadOnly} 的文档）。
 *
 * <p><b>为什么用 {@code AbstractRoutingDataSource} 而不是自己 if/else</b>：
 * 它把"路由键 → 目标数据源"的查找、默认数据源回退、以及**与 Spring 事务的连接绑定**
 * 都处理好了；自己写很容易在"事务中切换数据源"上出错（事务期间连接已绑定线程，后切无效）。
 *
 * <p><b>没有配置从库时不会创建本类</b>（见 DataSourceConfig）：单库部署零额外复杂度。
 */
@Slf4j
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    /**
     * 构造。
     *
     * @param primary 主库（写 + 默认读）
     * @param replica 只读从库
     */
    public ReadWriteRoutingDataSource(DataSource primary, DataSource replica) {
        super.setTargetDataSources(Map.of(DataSourceRoute.PRIMARY, primary, DataSourceRoute.REPLICA, replica));
        // 默认主库：任何"没打招呼"的访问都走主库——宁可不用从库，也不能读到旧数据做错决定
        super.setDefaultTargetDataSource(primary);
        super.afterPropertiesSet();
        log.info("[数据源] 已启用读写分离：默认主库，仅 @ReadOnly 标注的方法走只读从库");
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceRoute.current();
    }
}
