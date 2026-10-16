package com.wms.platform.common.datasource;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link ReadOnly} 注解的切面：在方法执行期间把数据源路由切到只读从库（P5.3）。
 *
 * <p><b>顺序很关键</b>：本切面必须在**事务切面之前**生效（order 小于事务默认 order），
 * 否则事务已经用主库连接开启了，再切路由也没用——连接已经绑定到线程的
 * {@code TransactionSynchronizationManager} 上了。这是个典型的"看起来生效、其实无效"的坑。
 *
 * <p><b>始终注册</b>：未配置从库时路由键映射到主库，{@code @ReadOnly} 安全退化为读主库
 * （以前没配从库就不注册切面的写法需要两个类用同一条件前缀，一旦不一致就会出现
 *   路由装好了但切面没注册——所有读静默落主库且无报错。现在这个隐患被彻底消除）。
 */
@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Component
public class ReadOnlyRouteAspect {

    /**
     * 装配自检（P5.3 踩坑后加）。
     *
     * <p><b>为什么要有这一行</b>：本类曾被 {@code @ConditionalOnProperty} 的**前缀写错**
     * （写成 {@code wms.datasource.replica}，而配置在 {@code spring.datasource.replica}）导致
     * **切面从未注册**——路由数据源装好了、注解也标了，但通知不执行，读全在主库上，
     * 而且**没有任何报错**（表现为"读写分离配了但不起作用"）。
     * 这条日志让"切面是否真的注册了"一眼可见。
     */
    @jakarta.annotation.PostConstruct
    void logRegistered() {
        log.info("[读写分离] @ReadOnly 切面已注册（order={}，先于事务切面执行）", Ordered.HIGHEST_PRECEDENCE + 10);
    }

    /**
     * 环绕通知：进入时切从库，退出时清理。
     *
     * @param point 连接点
     * @return 方法返回值
     * @throws Throwable 原方法异常
     */
    @Around("@annotation(com.wms.platform.common.datasource.ReadOnly)")
    public Object routeToReplica(ProceedingJoinPoint point) throws Throwable {
        // 1. 切换到从库（必须在事务开启之前，因此本切面 order 高于事务切面）
        DataSourceRoute.toReplica();
        // 2. 记录路由决策：排障时能确认"这条查询到底走了哪个库"
        //    （DEBUG 级：生产不会刷屏；排查读写分离时打开）
        if (log.isDebugEnabled()) {
            log.debug("[读写分离] {} → 路由到从库", point.getSignature().toShortString());
        }
        try {
            // 3. 执行原方法
            return point.proceed();
        } finally {
            // 4. 必须清理：线程池复用会串号，导致后续写请求被误导到从库（super_read_only 直接报错）
            DataSourceRoute.clear();
        }
    }
}
