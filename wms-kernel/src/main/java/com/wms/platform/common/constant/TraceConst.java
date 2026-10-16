package com.wms.platform.common.constant;

/**
 * 日志追踪常量（规范条目 B6）。
 *
 * <p>MDC 键名被 {@code TraceIdFilter}（写入）与 {@code ApiResponse}（读取）共同使用，
 * 属跨类共享常量，必须集中在此，禁止在两侧各写一份字符串字面量。
 */
public interface TraceConst {

    /** SLF4J MDC 中 traceId 的键名，需与 logback 日志模板中的 {@code %X{traceId}} 保持一致。 */
    String MDC_TRACE_ID = "traceId";
}
