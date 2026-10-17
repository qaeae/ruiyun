package com.wms.platform.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：统一把异常转成 {@link ApiResponse}，保证"任何出口都是统一结构 + 带 traceId"。
 *
 * <p>不处理的异常（如 {@link Error}）交由容器处理；{@link Exception} 兜底为 500 + {@link ErrorCode#SYSTEM_ERROR}，
 * 且**不回显内部细节**（避免把栈信息暴露给调用方），细节只进日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        // 业务异常属于预期分支：只记参数摘要，不打印栈，避免日志噪音
        log.warn("业务异常 code={} message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.fail(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(BindException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR, detail));
    }

    /**
     * 路径不存在（Spring Boot 3 用 {@code NoResourceFoundException} 表达 404）。
     *
     * <p><b>为什么必须单独处理（这是 P4 验证时抓到的真实缺陷）</b>：
     * 不处理时它落到兜底分支，被当作「系统错误 19999」返回——
     * 调用方以为服务崩了，错误率告警也会被误导，而真实原因只是**接口路径写错**。
     * 明确返回 404 + 可读信息，排障成本天差地别。
     *
     * @param ex 异常
     * @return 404 + 统一响应体
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        log.warn("接口不存在: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.PARAM_ERROR, "接口不存在，请检查请求路径"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {
        log.warn("缺少请求头: {}", ex.getHeaderName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_ERROR, "缺少请求头: " + ex.getHeaderName()));
    }

    /**
     * 请求体无法解析（JSON 语法错/字段类型不匹配/时间格式不符）。
     *
     * <p><b>必须映射成 400 而不是兜底的 500（2026-10-14 执行 PoC-3 时补）</b>：
     * 500 会让客户端（尤其 PDA 离线队列）判定为"服务端故障"并**无限重试**，
     * 而实际原因是报文本身有问题——这类"永远重试不成功"的队列会静默拖垮一个仓库的作业。
     * 400 + 参数错误码则提示客户端应当修正报文。
     *
     * @param ex 报文解析异常
     * @return 统一错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // 只记录最内层原因，避免把整段 Jackson 定位信息写进日志与响应（可能含业务数据）
        String reason = ex.getMostSpecificCause().getMessage();
        String message = "请求报文无法解析: " + (reason == null ? "格式错误" : reason);
        log.warn("请求报文无法解析: {}", reason);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR, message));
    }

    /**
     * 请求方法不支持（例如用 GET 调一个 POST 接口）。
     *
     * <p><b>必须映射成 405 而不是兜底的 500</b>（2026-10-17 容器联调时发现：`GET /api/v1/outbound/orders`
     * 打成 500）：500 会被监控当成"服务故障"报警、被客户端当成"服务端挂了"重试，
     * 而真实原因只是调用方写错了方法——这类误报多了，真故障就没人看了。
     *
     * @param ex 方法不支持异常
     * @return 405 + 统一响应体
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        String message = "接口不支持 " + ex.getMethod() + " 方法，请检查请求方法";
        log.warn("{}(uri 需要 {} )", message, ex.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorCode.PARAM_ERROR, message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "参数类型错误: " + ex.getName();
        log.warn("{}", message);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(HttpServletRequest request, Exception ex) {
        log.error("未预期异常 uri={} method={}", request.getRequestURI(), request.getMethod(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.SYSTEM_ERROR));
    }

    private String describe(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    /**
     * 唯一键冲突 → 409 + 明确错误码（而不是 19999 系统错误）。
     *
     * <p>为什么必须分类：撞唯一键是**可预期的业务冲突**（重复授权、重复单号、关系表整批替换的残留键……）。
     * 报 19999 会把可预期冲突伪装成系统故障——实测就发生过：授权接口报 19999，真因是逻辑删除残留撞唯一键。
     * 409 与状态机/幂等冲突口径一致（见 03 §1.3）。
     *
     * @param ex 异常
     * @return 409 + 明确码
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(org.springframework.dao.DuplicateKeyException ex) {
        log.warn("唯一键冲突（可预期业务冲突）: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ErrorCode.DUPLICATE_KEY, "数据冲突：已存在相同记录（请勿重复提交）"));
    }
}