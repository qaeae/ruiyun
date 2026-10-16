package com.wms.platform.modules.fns.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

/**
 * 日终勾稽四层（{@code 04} §5.2）。
 *
 * <p>四层不是"多写几遍对账"，而是**四种互为独立证据的核对**：
 * <ul>
 *   <li>{@link #FUND} 资金层：平台账（商家余额+冻结+押金）vs 存管账户对账文件——**钱在不在**；</li>
 *   <li>{@link #FEE} 业务层：费用单合计 vs 资金流水扣减合计——**该扣的是否真扣了**；</li>
 *   <li>{@link #CARRIER} 三方层：平台运费 vs 承运商账单——**对方算的和我们算的是否一致**；</li>
 *   <li>{@link #INVENTORY} 库存层：三态账 vs 库位明细（+实物抽盘）——**货在不在**。</li>
 * </ul>
 * 任何一层漏掉都会留下一个盲区：只对资金不对库存 → 钱对得上但货少了；
 * 只对库存不对资金 → 货对得上但钱被重复扣了。
 */
public enum DailyReconcileLayer {

    /** 资金层。 */
    FUND("资金层", true),

    /** 业务层。 */
    FEE("业务层", true),

    /** 三方层。 */
    CARRIER("三方层", true),

    /** 库存层。 */
    INVENTORY("库存层", false);

    /** 中文落库值（与 {@code fn_daily_reconciliation.layer} 注释一致）。 */
    private final String label;

    /** 是否需要通道/承运商侧数据（用于判定"待接入"）。 */
    private final boolean needsCounterpartyData;

    DailyReconcileLayer(String label, boolean needsCounterpartyData) {
        this.label = label;
        this.needsCounterpartyData = needsCounterpartyData;
    }

    /**
     * 中文落库值。
     *
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * 是否需要通道/承运商侧数据。
     *
     * @return 需要返回 {@code true}
     */
    public boolean isNeedsCounterpartyData() {
        return needsCounterpartyData;
    }

    /**
     * 枚举名。
     *
     * @return 枚举名（落库用）
     */
    public String code() {
        return name();
    }

    /**
     * 反查（接受枚举名与中文标签）。
     *
     * @param value 枚举名或中文标签
     * @return 层
     * @throws BizException 非法时抛 {@code 10001}
     */
    public static DailyReconcileLayer of(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "勾稽层不能为空");
        }
        String trimmed = value.trim();
        for (DailyReconcileLayer layer : values()) {
            if (layer.name().equalsIgnoreCase(trimmed) || layer.label.equals(trimmed)) {
                return layer;
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                "非法的勾稽层：" + value + "（仅支持 FUND/FEE/CARRIER/INVENTORY 或 资金层/业务层/三方层/库存层）");
    }
}
