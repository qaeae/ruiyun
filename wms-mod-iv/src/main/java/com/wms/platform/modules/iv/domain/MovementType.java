package com.wms.platform.modules.iv.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

/**
 * 库存流水类型（取值与 {@code iv_movement.movement_type} 一致，{@code 01} §3.5）。
 *
 * <p><b>为什么要带"符号"和"是否影响三态"两个属性</b>：{@code iv_movement.qty} 是**带符号**的
 * （入库正、出库负）。若让每个调用方自己决定传正还是负，迟早会出现
 * "出库传了正数"——流水看着正常，但流水累加值与实物库存对不上，而这类差异往往要到月末盘点才暴露。
 * 把符号规则钉在枚举里，调用方只表达"做什么动作"。
 */
public enum MovementType {

    /** 上架收货：实物 + 可用同增。 */
    RECEIVE("上架", 1, true),

    /** 预占：可用 → 预占（三态总量不变）。 */
    RESERVE("预占", 0, true),

    /** 释放预占：预占 → 可用。 */
    UNRESERVE("释放", 0, true),

    /** 派发分配：库位级 `allocated_qty` 增加，SKU 三态不变（{@code 01} §3 状态机第 2 行）。 */
    ALLOCATE("分配", 0, false),

    /** 拣货：库位级 `allocated_qty` 减少，SKU 三态不变（{@code 01} §3 状态机第 3 行）。 */
    PICK("拣货", 0, false),

    /**
     * 取消分配：库位级 `allocated_qty` 归位（取消已派发未拣货的出库单用，{@code 01} §2.6 取消矩阵
     * "待接单/拣货中 → allocated 归位"）。
     *
     * <p>为什么不复用 {@link #PICK}：两者对库位的效果一样（allocated −q），但**业务含义相反**——
     * 一个是"货被拣走了"，一个是"不拣了、把分配撤回来"。共用会让流水无法解释
     * （复盘时看到 PICK 却找不到拣货动作），而流水正是盘点差异时唯一的追溯依据。
     */
    UNALLOCATE("取消分配", 0, false),

    /** 出库：实物与预占同减（不变量 {@code physical = available + reserved} 保持）。 */
    SHIP("出库", -1, true),

    /** 盘点调整：按差异方向增减（盘盈正、盘亏负）。 */
    ADJUST("盘点调整", 0, true),

    /** 移库：库位级一减一增（双条流水），SKU 三态不变。 */
    MOVE("移库", 0, false),

    /** 退货入库：实物 + 可用同增。 */
    RETURN("退货入库", 1, true);

    /** 中文名。 */
    private final String label;

    /** 符号：1 正、-1 负、0 由调用方传入带符号值。 */
    private final int sign;

    /** 是否影响 SKU 级三态。 */
    private final boolean affectsSkuState;

    MovementType(String label, int sign, boolean affectsSkuState) {
        this.label = label;
        this.sign = sign;
        this.affectsSkuState = affectsSkuState;
    }

    /**
     * 中文名。
     *
     * @return 中文名
     */
    public String getLabel() {
        return label;
    }

    /**
     * 符号。
     *
     * @return 1 正、-1 负、0 由调用方传入带符号值
     */
    public int getSign() {
        return sign;
    }

    /**
     * 是否影响 SKU 级三态。
     *
     * @return 影响返回 {@code true}
     */
    public boolean isAffectsSkuState() {
        return affectsSkuState;
    }

    /**
     * 把"数量"按本类型的符号规则转成带符号流水值。
     *
     * @param qty 非负数量
     * @return 带符号流水值
     */
    public java.math.BigDecimal signedQty(java.math.BigDecimal qty) {
        if (qty == null) {
            return java.math.BigDecimal.ZERO;
        }
        return switch (sign) {
            case 1 -> qty.abs();
            case -1 -> qty.abs().negate();
            default -> qty;
        };
    }

    /**
     * 反查（不区分大小写）。
     *
     * @param type 类型码
     * @return 类型
     * @throws BizException 非法时抛 {@code 10001}
     */
    public static MovementType of(String type) {
        if (type == null || type.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "流水类型不能为空");
        }
        for (MovementType value : values()) {
            if (value.name().equalsIgnoreCase(type.trim())) {
                return value;
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR, "非法的流水类型：" + type);
    }
}
