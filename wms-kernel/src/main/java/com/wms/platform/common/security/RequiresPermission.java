package com.wms.platform.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 需要的权限码（在 Controller 方法或类上标注）。
 *
 * <p><b>为什么用"标注即校验"而不是在每个方法里写 if</b>：权限判断散落在业务方法里时，
 * 漏一个就是一个越权入口，而漏掉的往往正是"最不该漏"的那个（例如库存调整、账单冲正）。
 * 标注 + 拦截器把校验变成**声明式**的，于是可以再用一条 ArchUnit 规则
 * （{@code 所有写接口必须标注 @RequiresPermission 或 @PublicApi}）把"漏标"变成 CI 阻断项。
 *
 * <p>权限码格式：{@code 域:资源:动作}，如 {@code stock:adjust}、{@code master:sku:create}；
 * 常量集中定义在 {@code PermissionCodes}，禁止手写字符串（写错等于没有权限）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /**
     * 需要的权限码（多个为"任一满足"）。
     *
     * @return 权限码数组
     */
    String[] value();
}
