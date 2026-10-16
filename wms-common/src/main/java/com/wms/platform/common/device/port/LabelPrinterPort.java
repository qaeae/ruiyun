package com.wms.platform.common.device.port;

import com.wms.platform.common.device.dto.PrintResultDto;
import com.wms.platform.common.device.dto.PrintTaskDto;

/**
 * 打印端口（面单/标签打印，设备无关）。
 *
 * <p><b>调用方向</b>：服务端 → 打印执行方。服务端负责"该打什么、打第几次"（业务与幂等），
 * 端口负责"把任务交给某台打印机执行"。
 *
 * <p>实现方：
 * <ul>
 *   <li>{@code SimulatedLabelPrinter}（本仓库，记录任务并可模拟失败，供 P1 与自动化测试使用）；</li>
 *   <li>真实设备：局域网打印服务/浏览器打印控件/承运商打印控件——由 W5 客户端实现
 *       （见 {@code 08-设备网关与本地设备接入.md} §三，服务端不直连硬件）。</li>
 * </ul>
 */
public interface LabelPrinterPort {

    /**
     * 执行一次打印。
     *
     * <p>约定：**失败不抛异常，返回 {@code success=false}**。打印失败需要落到业务上处理
     * （重试 / 换打印机 / 转人工），抛异常会让调用方丢失"究竟打到哪一步"的信息。
     *
     * @param task 打印任务
     * @return 打印结果
     */
    PrintResultDto print(PrintTaskDto task);
}
