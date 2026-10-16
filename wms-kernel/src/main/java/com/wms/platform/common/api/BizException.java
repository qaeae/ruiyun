package com.wms.platform.common.api;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 {@link ErrorCode} 与期望的 HTTP 状态。
 *
 * <p>HTTP 状态语义（{@code 03-接口定义.md} §1.2）：
 * <ul>
 *   <li>默认 {@code 200}：业务规则失败（调用方看 {@code code}）</li>
 *   <li>{@code 409}：状态机非法流转 / 幂等键冲突 —— 便于调用方区分"重试无意义"与"可以重试"</li>
 * </ul>
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus httpStatus;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), HttpStatus.OK);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, HttpStatus.OK);
    }

    public BizException(ErrorCode errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus == null ? HttpStatus.OK : httpStatus;
    }

    /**
     * 包装底层异常（B5：禁止抛无状态码异常，底层异常必须转成带码的业务异常并保留 cause）。
     *
     * @param errorCode 错误码
     * @param message   中文消息
     * @param cause     原始异常
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = HttpStatus.OK;
    }

    /** 状态机非法流转（HTTP 409）。 */
    public static BizException illegalTransition(String message) {
        return new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION, message, HttpStatus.CONFLICT);
    }

    /** 幂等键重复但请求体不一致（HTTP 409，见 03 §1.3 第 2 条）。 */
    public static BizException idempotentMismatch() {
        return idempotentMismatch(ErrorCode.IDEMPOTENT_MISMATCH.getMessage());
    }

    /**
     * 幂等键重复但请求体不一致（带上下文消息，便于定位是哪张单据）。
     *
     * @param message 中文消息（建议带上业务键，如 {@code shpNo:printSeq}）
     * @return 409 业务异常
     */
    public static BizException idempotentMismatch(String message) {
        return new BizException(ErrorCode.IDEMPOTENT_MISMATCH, message, HttpStatus.CONFLICT);
    }

    /** 命中唯一键冲突：按 03 §1.3 第 3 条转成幂等成功，此处仅提供显式声明为冲突的场景。 */
    public static BizException conflict(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message, HttpStatus.CONFLICT);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
