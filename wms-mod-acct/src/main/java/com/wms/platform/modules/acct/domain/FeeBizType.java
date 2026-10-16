package com.wms.platform.modules.acct.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

/**
 * 费用类型（对应 {@code ac_fee_freeze.biz_type}，取值见 {@code 04-资金合规落地方案} §4.3）。
 *
 * <p>为什么用枚举而不是字符串常量：{@code uk_freeze_biz(tenant_id, biz_type, biz_no)} 是同一业务对象
 * 只冻结一次的**唯一防线**；如果允许调用方随便传字符串，同一个 DO 的物流费写成 {@code LOGISTIC} 与
 * {@code LOGISTICS} 会被当成两类费用**各冻一次**——重复冻结不会报错，只会让冻结额悄悄变大。
 */
public enum FeeBizType {

    /** 物流费（出库建单时冻结，量最大）。 */
    LOGISTICS("物流费"),

    /** 操作费（上架/打包等作业动作触发）。 */
    OPERATION("操作费"),

    /** 仓储费（按月/按天计费，M4 落地）。 */
    STORAGE("仓储费");

    /** 中文名。 */
    private final String label;

    FeeBizType(String label) {
        this.label = label;
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
     * 反查（仅接受枚举名，落库与接口统一用枚举名，避免两套写法）。
     *
     * @param value 枚举名
     * @return 费用类型
     * @throws BizException 非法时抛 {@code 10001}
     */
    public static FeeBizType of(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "费用类型不能为空");
        }
        for (FeeBizType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR, "非法的费用类型：" + value);
    }
}
