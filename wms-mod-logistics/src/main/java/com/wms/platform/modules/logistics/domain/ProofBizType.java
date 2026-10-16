package com.wms.platform.modules.logistics.domain;

import java.util.Locale;

/**
 * 影像用途（决定保留期与访问策略）。
 */
public enum ProofBizType {

    /** 回单（签收凭证，结算举证用）。 */
    POD("回单"),

    /** 异常影像（破损/短少/拒收现场）。 */
    EXCEPTION("异常"),

    /** 面单（打印存档）。 */
    LABEL("面单"),

    /** 其它。 */
    OTHER("其它");

    /** 中文标签。 */
    private final String label;

    ProofBizType(String label) {
        this.label = label;
    }

    /**
     * 标签。
     *
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * 解析（大小写不敏感）。
     *
     * @param code 编码
     * @return 用途；空则默认 POD（最常用）
     */
    public static ProofBizType of(String code) {
        if (code == null || code.isBlank()) {
            return POD;
        }
        for (ProofBizType type : values()) {
            if (type.name().equals(code.trim().toUpperCase(Locale.ROOT))) {
                return type;
            }
        }
        throw new com.wms.platform.common.api.BizException(
                com.wms.platform.common.api.ErrorCode.PARAM_ERROR,
                "不支持的影像用途：" + code + "（可选 POD/EXCEPTION/LABEL/OTHER）");
    }
}
