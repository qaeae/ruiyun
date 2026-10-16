package com.wms.platform.common.device.dto;

import com.wms.platform.common.device.BarcodeType;

/**
 * 扫码解析结果（设备无关模型；B3：后缀 {@code Dto}、包位 {@code ..dto..}）。
 *
 * @param rawCode        客户端上报的原始串
 * @param normalizedCode 规范化后的编码（去首尾空白、去内部空格、英文大写）
 * @param type           条码业务类型
 * @param valid          格式是否合法（不合法时 {@code normalizedCode} 为空、{@code message} 给出原因）
 * @param message        结果说明（成功为 {@code ok}，失败为具体原因）
 */
public record ScanResultDto(String rawCode, String normalizedCode, BarcodeType type, boolean valid, String message) {

    /**
     * 静态工厂：解析成功（B4：统一用静态工厂，禁止调用方自行拼装）。
     *
     * @param rawCode        原始串
     * @param normalizedCode 规范化编码
     * @param type           条码类型
     * @return 成功的扫描结果
     */
    public static ScanResultDto valid(String rawCode, String normalizedCode, BarcodeType type) {
        return new ScanResultDto(rawCode, normalizedCode, type, true, "ok");
    }

    /**
     * 静态工厂：解析失败。
     *
     * @param rawCode 原始串
     * @param message 失败原因（中文）
     * @return 失败的扫描结果
     */
    public static ScanResultDto invalid(String rawCode, String message) {
        return new ScanResultDto(rawCode, "", BarcodeType.UNKNOWN, false, message);
    }
}
