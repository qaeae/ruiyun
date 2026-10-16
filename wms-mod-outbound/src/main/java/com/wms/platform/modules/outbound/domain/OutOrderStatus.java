package com.wms.platform.modules.outbound.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

/**
 * 出库单（DO）状态（{@code 01} §2.1 与 {@code 01} §3.2 的 {@code out_order.status} 取值）。
 *
 * <pre>
 * 待接单 → 拣货中 → 已复核 → 已打包 → 待装车 → 已出库
 *    ↘ 缺货待处理 → 拣货中
 *    ↘ 已拦截 / 已取消
 * </pre>
 *
 * <p><b>为什么没有「部分出库」这个状态</b>：{@code 01} §2.1 的示意图里出现了"部分出库"，但
 * §3.2 的 {@code status} 列注释只列了 9 个取值、没有它。本实现按**列注释（9 态）**落地：
 * 部分发货时**原单直接转「已出库」**并在 {@code remark} 写明余量去向，余量生成一张新的「待接单」单。
 * 这样做的好处是"哪些状态是终态"没有歧义——多一个半终态会让装车、取消、回滚三处判断都要额外分支。
 *
 * <p><b>终态有三类</b>：已出库（货已出账，只能逆向流程）、已拦截（风控/错发）、已取消。
 * 终态不可再流转（{@code 01} §2.1 的"不可逆点：状态 9 之后库存已出账"）。
 */
public enum OutOrderStatus {

    /** 待接单（已建单、已预占，等库管派发）。 */
    PENDING("待接单", false),

    /** 拣货中（已派发，库位已分配）。 */
    PICKING("拣货中", false),

    /** 已复核（实拣 = 应发，含称重量方完成）。 */
    CHECKED("已复核", false),

    /** 已打包（面单已绑定）。 */
    PACKED("已打包", false),

    /** 待装车（已扫码交接给装卸工）。 */
    AWAITING_LOAD("待装车", false),

    /** 已出库（装车扫码完成，库存已出账）——终态，只能逆向流程。 */
    SHIPPED("已出库", true),

    /** 缺货待处理（实拣 &lt; 应发，差额已释放）。 */
    SHORTAGE("缺货待处理", false),

    /** 已拦截（禁入品/错发/风控命中，冻结已释放、预占已回滚）——终态。 */
    HELD("已拦截", true),

    /** 已取消（按 {@code 01} §2.6 取消矩阵回滚完毕）——终态。 */
    CANCELLED("已取消", true);

    /** 落库取值（中文，与 DDL 列注释一致）。 */
    private final String code;

    /** 是否终态。 */
    private final boolean terminal;

    OutOrderStatus(String code, boolean terminal) {
        this.code = code;
        this.terminal = terminal;
    }

    /**
     * 落库取值。
     *
     * @return 中文状态名
     */
    public String code() {
        return code;
    }

    /**
     * 是否终态。
     *
     * @return 终态返回 {@code true}
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 反查（落库与接口统一用中文取值；非法值说明数据被改坏了，必须显式失败）。
     *
     * @param code 状态取值
     * @return 状态
     * @throws BizException 非法时抛 {@code 19999}（脏数据属系统问题，不是参数问题）
     */
    public static OutOrderStatus of(String code) {
        for (OutOrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, "出库单状态取值非法（疑似脏数据）：" + code);
    }
}
