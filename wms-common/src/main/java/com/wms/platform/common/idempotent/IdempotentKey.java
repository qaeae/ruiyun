package com.wms.platform.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记"必须幂等"的写接口（{@code 03-接口定义.md} §1.3）。
 *
 * <p>规则：
 * <ol>
 *   <li>调用方必须带 {@code Idempotency-Key} 头（≤96 字符），缺失返回 400 + {@code 10001}；</li>
 *   <li>服务端以 {@code (tenantId, idemKey)} 查 {@code pf_idempotent}：命中且成功 → **直接返回首次响应**；</li>
 *   <li>{@code requestHash} 不一致 → 409 + {@code 10002}；并发重入 → 429 + {@code 10003}；</li>
 *   <li>数据库唯一键是**最后防线**：命中 uk 冲突时按幂等成功处理（03 §1.3 第 3 条）。</li>
 * </ol>
 *
 * <p>{@link #value()} 用于登记业务类型，写入 {@code pf_idempotent.biz_type}，便于排障与统计。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IdempotentKey {

    /** 业务类型，如 {@code OUT_ORDER_CREATE}、{@code ACCT_DEDUCT}。 */
    String value() default "";
}
