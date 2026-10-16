package com.wms.platform.common.cache;

/**
 * 缓存键常量（集中定义，避免各业务类各写一份导致"改了 A 忘了 B"）。
 *
 * <p>命名约定：{@code 域:对象:维度}，实现会再统一加全局前缀（如 {@code wms:cache:}），
 * 便于运维按前缀查看/清理，也避免与同 Redis 实例上的其它项目键冲突（本项目已有过
 * "配置 DataId 撞名读到别人配置"的教训，键名同样要有命名空间）。
 */
public final class CacheKeys {

    /** 主数据域：SKU 编码 → ID（维度：商家 + 编码）。 */
    public static final String MD_SKU_ID = "md:sku-id";

    /** 主数据域：SKU 明细（维度：商家 + 编码）。 */
    public static final String MD_SKU_BRIEF = "md:sku-brief";

    /** 主数据域：库位编码 → ID（维度：仓库 + 编码）。 */
    public static final String MD_LOCATION_ID = "md:location-id";

    /**
     * 拼接键。
     *
     * @param parts 键片段（顺序即维度顺序）
     * @return 键
     */
    public static String of(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private CacheKeys() {
    }
}
