package com.wms.platform.common.device;

/**
 * 条码业务类型（扫码结果的语义分类）。
 *
 * <p>识别规则：取规范化后条码的**前缀**（第一个 {@code -} 之前的段）匹配本枚举。
 * 识别不出来时归为 {@link #UNKNOWN}——**不判为无效**：格式合法的条码是否"存在/可用"
 * 属于业务校验（由各业务域负责），设备端口只负责规范化与分类。
 */
public enum BarcodeType {

    /** 出库单：{@code DO-...}。 */
    DO("出库单", "DO"),

    /** 装运单：{@code SHP-...}。 */
    SHP("装运单", "SHP"),

    /** 入库通知：{@code ASN-...}。 */
    ASN("入库通知", "ASN"),

    /** 商品条码：{@code SKU-...}。 */
    SKU("商品", "SKU"),

    /** 库位码：{@code LOC-...}。 */
    LOCATION("库位", "LOC"),

    /** 承运单号/面单：{@code WB-...}。 */
    WAYBILL("承运单号", "WB"),

    /** 无法识别前缀（格式合法但业务类型未知）。 */
    UNKNOWN("未识别", "");

    /** 中文名。 */
    private final String label;

    /**
     * 条码前缀。
     *
     * <p><b>为什么单独定义前缀而不用枚举名匹配</b>：前缀是现场贴码的业务约定，枚举名是代码标识，
     * 两者不必相同（{@code LOCATION} 的前缀是 {@code LOC}）。用枚举名匹配会产生
     * "库位码永远识别不出来"这类静默故障——本工程已由单测抓住过一次；
     * 外部规范也记录过同类缺陷（枚举 code 与库中字典取值不一致）。
     */
    private final String prefix;

    BarcodeType(String label, String prefix) {
        this.label = label;
        this.prefix = prefix;
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
     * 条码前缀。
     *
     * @return 前缀（{@link #UNKNOWN} 为空串）
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * 按前缀识别类型（识别不出返回 {@link #UNKNOWN}）。
     *
     * @param prefix 已规范化为大写的条码前缀
     * @return 条码类型
     */
    public static BarcodeType ofPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return UNKNOWN;
        }
        for (BarcodeType type : values()) {
            if (type != UNKNOWN && type.prefix.equals(prefix)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
