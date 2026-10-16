package com.wms.platform.common.device;

import com.wms.platform.common.device.dto.PrintResultDto;
import com.wms.platform.common.device.dto.PrintTaskDto;
import com.wms.platform.common.device.dto.ScanResultDto;
import com.wms.platform.common.device.dto.WeightReadingDto;
import com.wms.platform.common.device.simulated.SimulatedBarcodeScanner;
import com.wms.platform.common.device.simulated.SimulatedElectronicScale;
import com.wms.platform.common.device.simulated.SimulatedLabelPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模拟设备与设备网关的行为测试。
 *
 * <p>重点不在"能跑"，而在**现场脏数据与设备故障是否被正确处理**：
 * 扫码脏串、重复投递打印、秤未稳定就取数——这三类问题在真实现场都会发生，
 * 且都会以"数据不对/多出纸/误拦停"的形式暴露。
 */
@DisplayName("设备端口与模拟设备")
class SimulatedDeviceTest {

    @Nested
    @DisplayName("扫码枪：规范化与分类")
    class ScannerTest {

        /** 被测对象。 */
        private final SimulatedBarcodeScanner scanner = new SimulatedBarcodeScanner();

        @Test
        @DisplayName("规范化：去空白（含全角）、转大写")
        void shouldNormalizeWhitespaceAndCase() {
            ScanResultDto result = scanner.parse("  do- 2026 001 \n");
            assertThat(result.valid()).isTrue();
            assertThat(result.normalizedCode()).isEqualTo("DO-2026001");
            assertThat(result.type()).isEqualTo(BarcodeType.DO);
        }

        @Test
        @DisplayName("识别各业务类型前缀")
        void shouldRecognizePrefixes() {
            assertThat(scanner.parse("SHP-100").type()).isEqualTo(BarcodeType.SHP);
            assertThat(scanner.parse("asn-9").type()).isEqualTo(BarcodeType.ASN);
            assertThat(scanner.parse("SKU-88").type()).isEqualTo(BarcodeType.SKU);
            assertThat(scanner.parse("LOC-A-01").type()).isEqualTo(BarcodeType.LOCATION);
            assertThat(scanner.parse("WB-7788").type()).isEqualTo(BarcodeType.WAYBILL);
        }

        @Test
        @DisplayName("前缀未知但格式合法：判为有效（能否使用由业务域决定，设备端口不越界）")
        void unknownPrefixShouldStillBeValid() {
            ScanResultDto result = scanner.parse("XYZ-123");
            assertThat(result.valid()).isTrue();
            assertThat(result.type()).isEqualTo(BarcodeType.UNKNOWN);
        }

        @Test
        @DisplayName("空内容与超长串：判为无效并给出中文原因（现场提示重扫）")
        void blankAndTooLongShouldBeInvalid() {
            assertThat(scanner.parse("   ").valid()).isFalse();
            assertThat(scanner.parse(null).valid()).isFalse();
            assertThat(scanner.parse("A".repeat(65)).message()).contains("超过 64");
        }

        @Test
        @DisplayName("含控制字符：判为无效（扫码枪配置错误的典型症状）")
        void controlCharacterShouldBeInvalid() {
            assertThat(scanner.parse("DO-1\u0007").valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("打印机：幂等去重与故障")
    class PrinterTest {

        /** 被测对象。 */
        private final SimulatedLabelPrinter printer = new SimulatedLabelPrinter();

        @Test
        @DisplayName("同一 taskId 重复投递不重复出纸（现场后果：一个包裹两张面单）")
        void duplicateTaskShouldNotPrintAgain() {
            PrintTaskDto task = PrintTaskDto.of("SHP-1:1", "SHP-1", "WB-1", "http://label", "P1", 2);

            PrintResultDto first = printer.print(task);
            PrintResultDto second = printer.print(task);

            assertThat(first.success()).isTrue();
            assertThat(second.success()).isTrue();
            assertThat(printer.printedSheets()).as("只应出 2 张（1 个任务 × 2 份），不能是 4 张").isEqualTo(2);
            assertThat(printer.printedTasks()).hasSize(1);
        }

        @Test
        @DisplayName("不同序号（补打）会真的再出纸")
        void differentSeqShouldPrintAgain() {
            printer.print(PrintTaskDto.of("SHP-1:1", "SHP-1", "WB-1", "http://label", "P1", 1));
            printer.print(PrintTaskDto.of("SHP-1:2", "SHP-1", "WB-1", "http://label", "P1", 1));

            assertThat(printer.printedSheets()).isEqualTo(2);
        }

        @Test
        @DisplayName("打印机离线：返回失败而不是抛异常（业务侧据此重试或转人工）")
        void offlineShouldReturnFailure() {
            printer.goOffline();
            PrintResultDto result = printer.print(PrintTaskDto.of("T1", "SHP-1", "WB-1", "u", "P1", 1));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("离线");
            assertThat(printer.printedSheets()).isZero();
        }

        @Test
        @DisplayName("参数非法：缺 taskId / 缺打印机 / 份数越界均失败")
        void invalidTaskShouldFail() {
            assertThat(printer.print(PrintTaskDto.of(null, "SHP-1", "WB-1", "u", "P1", 1)).message())
                    .contains("taskId");
            assertThat(printer.print(PrintTaskDto.of("T1", "SHP-1", "WB-1", "u", null, 1)).message())
                    .contains("打印机编码");
            assertThat(printer.print(PrintTaskDto.of("T1", "SHP-1", "WB-1", "u", "P1", 99)).message())
                    .contains("份数");
        }
    }

    @Nested
    @DisplayName("电子秤：稳定性判定")
    class ScaleTest {

        /** 被测对象。 */
        private final SimulatedElectronicScale scale = new SimulatedElectronicScale();

        @Test
        @DisplayName("未稳定时读到跳数（远小于真实值），稳定后才返回真值")
        void unstableThenStable() {
            scale.setWeight("S1", new BigDecimal("10.0000"));
            scale.setUnstableReads("S1", 2);

            assertThat(scale.read("S1").stable()).isFalse();
            assertThat(scale.read("S1").stable()).isFalse();
            assertThat(scale.read("S1").stable()).isTrue();
            assertThat(scale.read("S1").weightKg()).isEqualByComparingTo("10.0000");
        }

        @Test
        @DisplayName("readStable 会等到稳定（而不是把跳数当实重导致误拦停）")
        void readStableShouldWait() {
            scale.setWeight("S1", new BigDecimal("5.5000"));
            scale.setUnstableReads("S1", 3);

            WeightReadingDto reading = scale.readStable("S1", 10, 1L);

            assertThat(reading.stable()).isTrue();
            assertThat(reading.weightKg()).isEqualByComparingTo("5.5000");
        }

        @Test
        @DisplayName("始终不稳定：返回最后一次读数且 stable=false（由调用方决定是否拦停）")
        void neverStableShouldReturnLastReading() {
            scale.setWeight("S1", new BigDecimal("8.0000"));
            scale.setUnstableReads("S1", 100);

            WeightReadingDto reading = scale.readStable("S1", 3, 1L);

            assertThat(reading.stable()).isFalse();
        }
    }

    @Nested
    @DisplayName("设备网关：统一入口")
    class GatewayTest {

        @Test
        @DisplayName("转发三类能力，业务代码不感知具体实现")
        void shouldDelegateToPorts() {
            SimulatedLabelPrinter printer = new SimulatedLabelPrinter();
            SimulatedElectronicScale scale = new SimulatedElectronicScale();
            scale.setWeight("S1", new BigDecimal("2.0000"));
            DeviceGateway gateway = new DeviceGateway(new SimulatedBarcodeScanner(), printer, scale);

            assertThat(gateway.scan("DO-1").type()).isEqualTo(BarcodeType.DO);
            assertThat(gateway.print(PrintTaskDto.of("T1", "SHP-1", "WB-1", "u", "P1", 1)).success()).isTrue();
            assertThat(gateway.weigh("S1").weightKg()).isEqualByComparingTo("2.0000");
            assertThat(gateway.printer()).isSameAs(printer);
        }
    }

    @Nested
    @DisplayName("设备类型枚举")
    class DeviceTypeTest {

        @Test
        @DisplayName("反查不区分大小写；非法值抛带码异常（B5）")
        void ofShouldBeCaseInsensitive() {
            assertThat(DeviceType.of("pda")).isEqualTo(DeviceType.PDA);
            assertThat(DeviceType.of(" Scale ")).isEqualTo(DeviceType.SCALE);
            assertThatThrownBy(() -> DeviceType.of("ROBOT"))
                    .hasMessageContaining("非法的设备类型");
        }

        @Test
        @DisplayName("只有 PDA/扫码枪需要入口绑定校验")
        void onlyEntryDevicesRequireBinding() {
            assertThat(DeviceType.PDA.requiresBinding()).isTrue();
            assertThat(DeviceType.SCANNER.requiresBinding()).isTrue();
            assertThat(DeviceType.PRINTER.requiresBinding()).isFalse();
            assertThat(DeviceType.SCALE.requiresBinding()).isFalse();
        }
    }
}
