package com.wms.platform.common.trace;

import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.constant.TraceConst;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * 全链路 TraceId：所有响应都带 {@code traceId}（{@code 03-接口定义.md} §一）。
 *
 * <ul>
 *   <li>优先取调用方传入的 {@code X-Trace-Id}（跨系统串联），缺失则本地生成 16 位十六进制；</li>
 *   <li>写入 MDC，日志模板统一输出，业务代码无需关心；</li>
 *   <li>回写响应头，便于前端/联调方直接拿 traceId 找日志。</li>
 * </ul>
 *
 * <p>置于过滤器链最前（{@link Ordered#HIGHEST_PRECEDENCE}），保证异常处理也能拿到 traceId。
 * 头名与 MDC 键名取自常量类（规范 B6：禁止散落魔法值）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 本地生成的 traceId 字节数（8 字节 → 16 位十六进制）。 */
    private static final int TRACE_ID_BYTES = 8;

    /** 十六进制字符表。 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 随机源（SecureRandom 线程安全）。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HeaderConst.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = newTraceId();
        }
        MDC.put(TraceConst.MDC_TRACE_ID, traceId);
        response.setHeader(HeaderConst.TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程复用场景必须清理，否则会把上一个请求的 traceId 带到下一个请求
            MDC.remove("traceId");
        }
    }

    private String newTraceId() {
        byte[] bytes = new byte[TRACE_ID_BYTES];
        RANDOM.nextBytes(bytes);
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX[v >>> 4];
            chars[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(chars);
    }
}
