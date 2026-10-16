package com.wms.platform.common.datasource;

/**
 * 数据源路由上下文（ThreadLocal，P5.3）。
 *
 * <p>用 ThreadLocal 而不是参数传递：路由决策是**横切关注点**，
 * 若沿调用链传参，业务方法签名会被数据源细节污染（且极易漏传导致"以为走从库其实走主库"）。
 *
 * <p><b>必须成对使用</b>：设置后要保证清理（见 {@link ReadOnlyRouteAspect} 的 finally），
 * 否则线程池线程复用时会把"读路由"泄漏给下一个请求——表现为"偶发地写入从库"，
 * 而 super_read_only 会直接报错（或更糟：路由错乱导致读到旧数据）。
 */
public final class DataSourceRoute {

    /** 主库（默认）。 */
    public static final String PRIMARY = "primary";

    /** 只读从库。 */
    public static final String REPLICA = "replica";

    /** 当前线程路由（null = 主库）。 */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * 设置线程只读路由。
     */
    public static void toReplica() {
        CURRENT.set(REPLICA);
    }

    /**
     * 清理（回到默认主库）。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 当前路由。
     *
     * @return 路由键；未设置返回 {@link #PRIMARY}
     */
    public static String current() {
        String route = CURRENT.get();
        return route == null ? PRIMARY : route;
    }

    private DataSourceRoute() {
    }
}
