package com.wms.platform.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关 TraceId 过滤器（全链路追踪的起点）。
 *
 * <p>规则：调用方带了 {@code X-Trace-Id} 就沿用（跨系统串联），否则生成一个；
 * 同时写入**请求头（传给下游服务）**与**响应头（回给调用方/浏览器）**。
 *
 * <p>顺序放在最前：这样后续过滤器（包括鉴权 401 的提前拒绝）都能带上 traceId——
 * 否则"网关直接拒绝"的请求会没有 traceId，而这类请求恰恰最难排查。
 */
@Slf4j
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    /** 追踪头名称（与业务侧 {@code TraceIdFilter}/{@code TraceConst} 保持一致）。 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 沿用上游 traceId，没有则生成（32 位无横线，与业务侧规则一致）
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String finalTraceId = traceId;

        // 2. 请求头下传 + 响应头回传
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(TRACE_HEADER, finalTraceId))
                .build();
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(TRACE_HEADER, finalTraceId);

        // 3. 记录网关侧访问日志（P4）
        //
        // 为什么打 INFO 而不是 DEBUG：网关是**整条链路的入口**，这一行同时给出
        // 「请求 + 耗时 + 状态 + traceId」——排障时从响应头拿到 traceId 后，
        // 第一步就是在这里确认"请求到了网关、耗时多少、返回什么状态，再由网关下传"。
        // 放在 DEBUG 的话默认日志里搜不到，全链路的第一环就成了空白。
        // 请求量再大也只每请求一行，对网关是可接受的（这正是网关日志的常规做法）。
        long startedAt = System.currentTimeMillis();
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> log.info("[网关] {} {} traceId={} 耗时={}ms 状态={}",
                        exchange.getRequest().getMethod(),
                        exchange.getRequest().getURI().getPath(),
                        finalTraceId,
                        System.currentTimeMillis() - startedAt,
                        response.getStatusCode()));
    }
}
