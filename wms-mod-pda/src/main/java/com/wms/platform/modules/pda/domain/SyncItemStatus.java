package com.wms.platform.modules.pda.domain;

/**
 * 补传逐条结果（取值即 `03` §七 的响应字段，PDA 据此决定本地如何标记）。
 */
public enum SyncItemStatus {

    /** 成功受理 → PDA 标记已同步。 */
    ACCEPTED("accepted"),

    /** 幂等重复 → PDA 标记已同步（**不是错误**，重复补传是离线场景的常态）。 */
    DUPLICATED("duplicated"),

    /** 状态/库存冲突 → PDA 标记"待人工处理"，现场走纸质兜底（05 文档）。 */
    CONFLICT("conflict"),

    /** 校验失败（错扫/单据不存在/动作非法）→ 保留本地并提示重扫。 */
    REJECTED("rejected");

    /** 对外码值（小写，与 03 §七 一致）。 */
    private final String code;

    SyncItemStatus(String code) {
        this.code = code;
    }

    /**
     * 对外码值。
     *
     * @return 小写码值
     */
    public String getCode() {
        return code;
    }

    /**
     * 是否需要 PDA 保留本地记录（未同步成功）。
     *
     * <p>区分很重要：`duplicated` 表示服务端已有该数据，PDA 必须**清掉本地队列**，
     * 否则每次补传都会把同一批数据再传一遍；`conflict`/`rejected` 则必须保留，
     * 否则现场做完的作业就真丢了。
     *
     * @return 需要保留返回 {@code true}
     */
    public boolean shouldKeepLocal() {
        return this == CONFLICT || this == REJECTED;
    }
}
