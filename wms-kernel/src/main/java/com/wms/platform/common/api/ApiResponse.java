package com.wms.platform.common.api;

import com.wms.platform.common.constant.TraceConst;
import org.slf4j.MDC;

/**
 * 统一响应体（唯一来源：{@code 03-接口定义.md} §1.2）。
 *
 * <pre>
 * { "code": 0, "message": "ok", "data": {}, "traceId": "b7f1c2e4a9d34f0c" }
 * </pre>
 *
 * <p>所有接口（含外部回调）必须返回本结构；{@code traceId} 由 {@code TraceIdFilter} 注入 MDC 后自动带出。
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public ApiResponse() {
        this.traceId = currentTraceId();
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = currentTraceId();
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message == null ? errorCode.getMessage() : message, null);
    }

    public boolean isSuccess() {
        return code == ErrorCode.OK.getCode();
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TraceConst.MDC_TRACE_ID);
        return traceId == null ? "" : traceId;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
