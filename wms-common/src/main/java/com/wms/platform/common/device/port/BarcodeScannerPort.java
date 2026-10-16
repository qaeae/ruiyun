package com.wms.platform.common.device.port;

import com.wms.platform.common.device.dto.ScanResultDto;

/**
 * 扫码端口（设备无关）。
 *
 * <p><b>为什么服务端需要"扫码端口"</b>：扫码枪是 HID 键盘仿真设备，物理上接在 PDA/仓库 PC 上，
 * 服务端**不可能**直接读它的输入。但"扫码结果怎么解析"是服务端必须统一的一件事：
 * 现场错扫、重复扫、带不可见字符、大小写不一致，都会一路污染到库存流水与对账。
 * 所以端口定义在服务端：<b>客户端负责采集，服务端负责规范化与分类</b>。
 *
 * <p>实现方：
 * <ul>
 *   <li>{@code SimulatedBarcodeScanner}（本仓库，开发/测试/PoC 用，无需硬件）；</li>
 *   <li>真实设备：由 W5 客户端（PDA/仓库 PC）采集后走接口上报，实现类在客户端侧。</li>
 * </ul>
 */
public interface BarcodeScannerPort {

    /**
     * 解析一次扫码结果（规范化 + 业务类型识别）。
     *
     * @param rawCode 客户端上报的原始串（可能含空白、大小写混杂）
     * @return 解析结果（格式非法时 {@code valid=false}，不抛异常——现场错扫是常态，需要提示重扫而非中断作业）
     */
    ScanResultDto parse(String rawCode);
}
