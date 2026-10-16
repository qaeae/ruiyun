package com.wms.platform.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * 统一响应写出工具（拦截器/过滤器在进入 MVC 之前拒绝请求时使用）。
 *
 * <p>为什么单独抽出来：幂等拦截器与设备绑定拦截器都要"提前拒绝并返回统一响应结构"。
 * 若各写一份，就会出现两套 JSON 拼装与字符集设置——**同类错误的返回体不一致**，
 * 前端必须为两个接口写两套解析逻辑。集中一处即可保证结构完全一致。
 */
public final class ApiErrorWriter {

    /** JSON 序列化器（无状态，可复用）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiErrorWriter() {
    }

    /**
     * 直接写出统一响应体（HTTP 状态码 + {@code {code,message,data,traceId}}）。
     *
     * @param response HTTP 响应
     * @param status   HTTP 状态码
     * @param errorCode 业务错误码
     * @param message  中文消息（为空时使用错误码默认消息）
     * @throws IOException 写响应失败
     */
    public static void write(HttpServletResponse response, HttpStatus status, ErrorCode errorCode, String message)
            throws IOException {
        // 1. 设置协议层状态与内容类型（UTF-8 必须显式声明，否则中文消息乱码）
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 2. 写出统一结构（traceId 由 ApiResponse 从 MDC 自动带出）
        response.getWriter().write(MAPPER.writeValueAsString(ApiResponse.fail(errorCode, message)));
    }
}
