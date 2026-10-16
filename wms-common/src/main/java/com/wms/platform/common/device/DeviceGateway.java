package com.wms.platform.common.device;

import com.wms.platform.common.device.dto.PrintResultDto;
import com.wms.platform.common.device.dto.PrintTaskDto;
import com.wms.platform.common.device.dto.ScanResultDto;
import com.wms.platform.common.device.dto.WeightReadingDto;
import com.wms.platform.common.device.port.BarcodeScannerPort;
import com.wms.platform.common.device.port.ElectronicScalePort;
import com.wms.platform.common.device.port.LabelPrinterPort;
import lombok.RequiredArgsConstructor;

/**
 * 设备网关：三类设备能力的统一入口。
 *
 * <p><b>业务代码只依赖本类（或直接依赖单个端口），不依赖任何具体设备实现</b>。
 * 这样做的直接收益：P1（面单打印）、P3（扫码补传）、M7-03（称重）在**没有硬件**的开发机上
 * 就能跑通全链路（用模拟设备），硬件到位或换品牌时只替换装配处的实现。
 *
 * <p>本类刻意保持极薄（只做转发）：网关不应承载业务规则——"偏差多少算超标"属于业务域
 * （见 {@code modules.outbound.domain.WeightCheck}），"该不该打印"属于业务域。
 * 网关一旦开始写 if-else，业务规则就会分裂到两处。
 */
@RequiredArgsConstructor
public class DeviceGateway {

    /** 扫码端口。 */
    private final BarcodeScannerPort scanner;

    /** 打印端口。 */
    private final LabelPrinterPort printer;

    /** 电子秤端口。 */
    private final ElectronicScalePort scale;

    /**
     * 解析扫码结果。
     *
     * @param rawCode 原始扫码串
     * @return 解析结果
     */
    public ScanResultDto scan(String rawCode) {
        return scanner.parse(rawCode);
    }

    /**
     * 执行打印。
     *
     * @param task 打印任务
     * @return 打印结果
     */
    public PrintResultDto print(PrintTaskDto task) {
        return printer.print(task);
    }

    /**
     * 采集稳定称重读数。
     *
     * @param scaleCode 电子秤编码
     * @return 称重读数
     */
    public WeightReadingDto weigh(String scaleCode) {
        return scale.readStable(scaleCode);
    }

    /**
     * 扫码端口（需要自定义解析策略时直接取用）。
     *
     * @return 扫码端口
     */
    public BarcodeScannerPort scanner() {
        return scanner;
    }

    /**
     * 打印端口。
     *
     * @return 打印端口
     */
    public LabelPrinterPort printer() {
        return printer;
    }

    /**
     * 电子秤端口。
     *
     * @return 电子秤端口
     */
    public ElectronicScalePort scale() {
        return scale;
    }
}
