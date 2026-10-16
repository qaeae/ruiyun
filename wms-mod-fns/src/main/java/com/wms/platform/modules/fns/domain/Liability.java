package com.wms.platform.modules.fns.domain;

/**
 * 差异责任归属（取值与 {@code fn_reconcile_item.liability} 一致）。
 */
public enum Liability {

    /** 平台承担（如平台价目表取价错误）。 */
    PLATFORM("平台"),

    /** 商家承担（如商家提供重量/体积不实）。 */
    MERCHANT("商家"),

    /** 物流承担（如承运商多计重量、乱加附加费）。 */
    LOGISTICS("物流");

    /** 落库值。 */
    private final String label;

    Liability(String label) {
        this.label = label;
    }

    /**
     * 落库值。
     *
     * @return 中文落库值
     */
    public String getLabel() {
        return label;
    }

    /**
     * 反查（同时接受枚举名 { LOGISTICS} 与中文标签 { 物流}）。
     *
     * <p>为什么两种都接受：接口调用方可能按枚举名传（研发友好），也可能按界面显示传中文
     * （运营从表格里复制）。**拒绝未知值**而不是回退到默认责任方——责任方决定谁承担这笔钱，
     * 猜错等于替业务做了决定。
     *
     * @param value 枚举名或中文标签
     * @return 责任方
     * @throws com.wms.platform.common.api.BizException 非法时抛 { 10001}
     */
    public static Liability of(String value) {
        if (value == null || value.isBlank()) {
            throw new com.wms.platform.common.api.BizException(
                    com.wms.platform.common.api.ErrorCode.PARAM_ERROR, "责任方不能为空");
        }
        String trimmed = value.trim();
        for (Liability liability : values()) {
            if (liability.name().equalsIgnoreCase(trimmed) || liability.label.equals(trimmed)) {
                return liability;
            }
        }
        throw new com.wms.platform.common.api.BizException(
                com.wms.platform.common.api.ErrorCode.PARAM_ERROR,
                "非法的责任方：" + value + "（仅支持 PLATFORM/MERCHANT/LOGISTICS 或 平台/商家/物流）");
    }
}
