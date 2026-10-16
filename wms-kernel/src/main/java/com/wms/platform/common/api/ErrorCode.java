package com.wms.platform.common.api;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 全局错误码（唯一来源：{@code 03-接口定义.md} §1.2）。
 *
 * <p>码段规则：5 位数字 = 域(1) + 类别(2) + 序号(2)。
 * <ul>
 *   <li>1xxxx 通用 / 2xxxx 出库 / 3xxxx 物流 / 4xxxx 资金</li>
 *   <li>5xxxx 库存 / 6xxxx 入库 / 7xxxx 对账结算 / 8xxxx 权限审批</li>
 * </ul>
 *
 * <p>约定：{@code code = 0} 表示业务成功；业务规则失败仍返回 HTTP 200 + {@code code != 0}，
 * 仅"状态机非法流转 / 幂等冲突"返回 HTTP 409（见 {@link BizException#conflict}）。
 */
public enum ErrorCode {

    // ===== 通用 1xxxx =====
    OK(0, "ok"),
    PARAM_ERROR(10001, "参数错误"),

    /** 唯一键冲突（可预期的业务冲突：重复授权/重复单号等）。用 409 返回，不能伪装成系统错误。 */
    DUPLICATE_KEY(10007, "数据冲突：已存在相同记录"),
    IDEMPOTENT_MISMATCH(10002, "幂等键重复且请求体不一致"),
    RATE_LIMITED(10003, "请求过于频繁"),
    SIGN_INVALID(10004, "签名校验失败"),
    TIMESTAMP_EXPIRED(10005, "请求时间戳过期"),
    SYSTEM_ERROR(19999, "系统错误"),

    // ===== 通用 · 设备与入口校验（10006 起；与 10001~10005 同属入口校验类） =====
    DEVICE_NOT_REGISTERED(10006, "设备未登记或已停用"),

    // ===== 通用 · 权限（03 §1.1 引用的 40300，此前未登记） =====
    FORBIDDEN(40300, "无权限访问"),

    // ===== 出库 2xxxx =====
    INSUFFICIENT_BALANCE_BLOCK_ORDER(20001, "余额不足，已拦截下单"),
    ILLEGAL_STATUS_TRANSITION(20002, "非法状态流转"),
    WEIGHT_DEVIATION_EXCEEDED(20003, "称重偏差超阈值"),
    LABEL_NOT_MATCH_ORDER(20004, "面单与单据不匹配"),
    HANDOVER_REQUIRED(20005, "未完成交接，不可装车"),

    // ===== 物流 3xxxx =====
    WAYBILL_FETCH_FAILED(30001, "取号失败"),
    WAYBILL_UNAVAILABLE(30002, "单号不可用"),
    LABEL_NOT_VOIDABLE(30003, "面单不可作废（已揽收）"),
    CARRIER_TIMEOUT(30004, "承运商接口超时"),

    // ===== 资金 4xxxx =====
    BALANCE_INSUFFICIENT(40001, "余额不足"),
    FREEZE_STATUS_NOT_ALLOW_DEDUCT(40002, "冻结单状态不允许扣减"),
    FREEZE_INSUFFICIENT_NEED_ADVANCE(40003, "冻结额不足，需垫付"),
    CHANNEL_CALL_FAILED(40004, "通道调用失败"),
    ALREADY_REVERSED(40005, "已冲正，不可重复冲正"),

    /** 价目表不存在或未启用（计费前必须能命中版本，否则不允许静默按 0 计算）。 */
    RATE_CARD_UNAVAILABLE(40006, "价目表不存在或未启用"),

    /** 价目表未命中的重量区间（**不允许静默按 0 元计费**——那会造成持续漏计费且无人发现）。 */
    RATE_ITEM_NOT_MATCHED(40007, "价目表未覆盖该区域/重量区间"),

    // ===== 库存 5xxxx =====
    AVAILABLE_STOCK_INSUFFICIENT(50001, "可用库存不足"),
    STOCK_OCCUPIED(50002, "库存已被占用（离线补传冲突）"),
    LOCATION_NOT_PICKABLE(50003, "库位不可拣"),
    INVARIANT_VIOLATED(50004, "库存三态不变量校验失败"),

    // ===== 入库 6xxxx =====
    CATEGORY_MATERIAL_INCOMPLETE(60001, "品类材料不全"),
    FORBIDDEN_CATEGORY(60002, "命中禁入品类"),
    PRE_INSPECT_NOT_PASSED(60003, "先验未通过，不得卸货"),

    // ===== 对账结算 7xxxx =====
    RECONCILE_STATUS_NOT_ALLOW_CONFIRM(70001, "对账单状态不允许确认"),
    DIFF_EXCEED_THRESHOLD_NEED_MANUAL(70002, "差异超阈值，需人工处理"),
    SETTLE_BATCH_ALREADY_DISPATCHED(70003, "结算批次已下发"),
    SETTLE_BILL_NOT_CONFIRMED(70004, "对账单未确认，不能生成结算批次"),
    SETTLE_DISPATCH_FAILED(70005, "支付指令未受理"),
    SETTLE_DISPATCH_CONFLICT(70006, "支付指令已受理但本地状态冲突，需人工核对"),
    ADVANCE_LIMIT_EXCEEDED(70007, "垫付超限额，需人工审批"),

    // ===== 权限审批 8xxxx =====
    APPROVAL_REQUIRED(80001, "该操作需要双人复核"),
    APPROVER_MUST_DIFFER(80002, "发起人与复核人不可为同一人"),
    APPROVAL_ALREADY_EXISTS(80003, "审批已存在"),
    APPROVAL_NOT_FOUND(80004, "审批单不存在"),
    APPROVAL_NOT_PENDING(80005, "审批单不在待复核状态"),
    APPROVAL_PAYLOAD_CHANGED(80006, "审批内容与提交时不一致，已阻断执行"),
    APPROVAL_NO_HANDLER(80007, "该业务类型未接入执行处理器，不能复核通过");

    private static final Map<Integer, ErrorCode> INDEX =
            Arrays.stream(values()).collect(Collectors.toMap(ErrorCode::getCode, Function.identity()));

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /** 按码值反查，未登记返回 {@link #SYSTEM_ERROR}（避免把未知码透传给调用方）。 */
    public static ErrorCode of(int code) {
        return INDEX.getOrDefault(code, SYSTEM_ERROR);
    }
}
