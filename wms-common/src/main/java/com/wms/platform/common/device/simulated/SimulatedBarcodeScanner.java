package com.wms.platform.common.device.simulated;

import com.wms.platform.common.device.BarcodeType;
import com.wms.platform.common.device.dto.ScanResultDto;
import com.wms.platform.common.device.port.BarcodeScannerPort;

/**
 * 模拟扫码枪（开发/测试/PoC 用，无需硬件）。
 *
 * <p>做的不是"假数据"，而是把**服务端必须统一的规范化规则**实现出来：现场扫码的常见脏数据
 * （首尾空白、全角空格、大小写混用、HID 键盘带来的控制字符、粘贴导致的超长串）如果不在入口收敛，
 * 会一路传播到库存流水与对账（表现为"同一张单在系统里有多个写法"）。
 *
 * <p>前缀规则（{@code DO-/SHP-/ASN-/SKU-/LOC-/WB-}）是**一期约定**，
 * W3 拿到承运商与 SKU 主数据的真实条码规则后需在此处校准（已在 {@code 08} 文档登记）。
 */
public class SimulatedBarcodeScanner implements BarcodeScannerPort {

    /** 条码最大长度，与 {@code 01} 单号字段（{@code VARCHAR(32~64)}）口径一致。 */
    private static final int MAX_LENGTH = 64;

    @Override
    public ScanResultDto parse(String rawCode) {
        // 1. 空值校验：扫码枪没读到内容（或前端传空）时提示重扫，而不是抛异常中断作业
        if (rawCode == null || rawCode.isBlank()) {
            return ScanResultDto.invalid(rawCode, "扫码内容为空，请重扫");
        }
        // 2. 规范化：去掉所有空白字符（含全角空格）、统一大写；HID 键盘可能带入尾随回车/制表符
        String normalized = rawCode.replaceAll("[\\s\\u3000]+", "").toUpperCase();
        // 3. 长度校验：超长通常是把整行文本粘进来了，必须拒绝并提示
        if (normalized.length() > MAX_LENGTH) {
            return ScanResultDto.invalid(rawCode, "条码长度超过 " + MAX_LENGTH + "，疑似误扫");
        }
        // 4. 合法性校验：拒绝控制字符（扫码枪配置错误时会出现）与缺少分隔符的串
        if (normalized.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            return ScanResultDto.invalid(rawCode, "条码包含不可见控制字符，请检查扫码枪配置");
        }
        // 5. 前缀识别：格式合法即视为有效；能否使用由业务域判断（设备端口不越界）
        int separator = normalized.indexOf('-');
        String prefix = separator > 0 ? normalized.substring(0, separator) : normalized;
        return ScanResultDto.valid(rawCode, normalized, BarcodeType.ofPrefix(prefix));
    }
}
