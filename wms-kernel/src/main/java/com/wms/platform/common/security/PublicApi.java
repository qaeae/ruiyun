package com.wms.platform.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公开接口标记（**必须写明原因**）。
 *
 * <p>与 {@link RequiresPermission} 配套：ArchUnit 规则要求每个写接口要么有权限标注、
 * 要么有本标注并写明为什么可以公开。强制填 {@link #value()} 是刻意的——
 * "因为测试方便""因为前端还没做登录"这类理由写出来之后，评审时自然会被问住。
 *
 * <p>已知的合理公开场景：健康探针、外部回调（用签名而非会话认证）、
 * 设备补传（用设备绑定 + 幂等键认证）、登录接口本身。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicApi {

    /**
     * 公开原因（必填）。
     *
     * @return 原因
     */
    String value();
}
