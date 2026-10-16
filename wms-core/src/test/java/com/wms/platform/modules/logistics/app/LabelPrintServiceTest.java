package com.wms.platform.modules.logistics.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.device.DeviceGateway;
import com.wms.platform.common.device.simulated.SimulatedBarcodeScanner;
import com.wms.platform.common.device.simulated.SimulatedElectronicScale;
import com.wms.platform.common.device.simulated.SimulatedLabelPrinter;
import com.wms.platform.common.idempotent.IdempotentService;
import com.wms.platform.modules.logistics.api.ro.LabelPrintRo;
import com.wms.platform.modules.logistics.api.vo.LabelPrintVo;
import com.wms.platform.modules.logistics.infrastructure.mapper.ShipmentPrintMapper;
import com.wms.platform.modules.logistics.infrastructure.po.ShipmentPrintRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面单打印登记测试（03 API-LOG-03，幂等键 {@code shpNo + printSeq}）。
 *
 * <p>这里验证的是**打印这条链路上最容易付出真实代价的三件事**：
 * <ol>
 *   <li>重试不重复出纸（纸是不会自己消失的）；</li>
 *   <li>打印失败不累加计数（否则"补打次数"虚高，掩盖真实的重试问题）；</li>
 *   <li>计数失败不回滚、不重打（宁可少记一次，也不能多出一张）。</li>
 * </ol>
 *
 * <p>用真实的模拟打印机（而不是 mock 端口）来断言"到底出了几张纸"——
 * 这正是 PoC 夹具的价值：把物理后果做成可断言的事实。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("面单打印登记")
class LabelPrintServiceTest {

    /** 运单打印 Mapper（mock）。 */
    @Mock
    private ShipmentPrintMapper shipmentPrintMapper;

    /** 幂等服务（mock）。 */
    @Mock
    private IdempotentService idempotentService;

    /** 模拟打印机（真实实现，可断言出纸张数）。 */
    private SimulatedLabelPrinter printer;

    /** 被测服务。 */
    private LabelPrintService service;

    /** 打印入参。 */
    private LabelPrintRo ro;

    @BeforeEach
    void setUp() {
        printer = new SimulatedLabelPrinter();
        DeviceGateway gateway = new DeviceGateway(new SimulatedBarcodeScanner(), printer, new SimulatedElectronicScale());
        // 与生产一致：Spring Boot 提供的 ObjectMapper 已注册 JavaTimeModule（否则 LocalDateTime 序列化会炸）
        service = new LabelPrintService(shipmentPrintMapper, gateway, idempotentService,
                new ObjectMapper().findAndRegisterModules());
        ro = new LabelPrintRo("SHP-1001", 1, "PRINTER-A-01", 2);
    }

    @Test
    @DisplayName("首次打印成功：出纸 2 张、累计计数 +2、写入幂等完成")
    void firstPrintShouldSucceed() {
        when(idempotentService.begin(any(), any(), any())).thenReturn(IdempotentService.Decision.PROCEED);
        when(shipmentPrintMapper.selectPrintInfo(anyLong(), eq("SHP-1001"))).thenReturn(row(0, false));
        when(shipmentPrintMapper.increasePrintCount(anyLong(), eq("SHP-1001"), eq(2), any()))
                .thenReturn(1);

        LabelPrintVo vo = service.print(ro);

        assertThat(vo.isSuccess()).isTrue();
        assertThat(vo.getSheets()).isEqualTo(2);
        assertThat(vo.getPrintCount()).isEqualTo(2);
        assertThat(printer.printedSheets()).as("真的出了 2 张纸").isEqualTo(2);
        verify(idempotentService).complete(eq("SHP-1001:1"), any());
    }

    @Test
    @DisplayName("重复序号（重试）→ 直接返回首次结果，不再出纸")
    void replayShouldNotPrintAgain() throws Exception {
        LabelPrintVo cached = LabelPrintVo.of("SHP-1001", "WB-1", "PRINTER-A-01", 2, 2, true, "ok");
        String cachedJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(cached);
        when(idempotentService.begin(any(), any(), any())).thenReturn(IdempotentService.Decision.REPLAY);
        when(idempotentService.cachedResponse("SHP-1001:1")).thenReturn(Optional.of(cachedJson));

        LabelPrintVo vo = service.print(ro);

        assertThat(vo.isSuccess()).isTrue();
        assertThat(printer.printedSheets()).as("重放不得再出纸").isZero();
        verify(shipmentPrintMapper, never()).increasePrintCount(anyLong(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("同序号不同内容 → 409（不静默以第一次为准）")
    void mismatchShouldConflict() {
        when(idempotentService.begin(any(), any(), any())).thenReturn(IdempotentService.Decision.MISMATCH);

        assertThatThrownBy(() -> service.print(ro))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getHttpStatus().value()).isEqualTo(409));
        assertThat(printer.printedSheets()).isZero();
    }

    @Test
    @DisplayName("并发重入（IN_PROGRESS）→ 409/429 让调用方稍后重试")
    void inProgressShouldConflict() {
        when(idempotentService.begin(any(), any(), any())).thenReturn(IdempotentService.Decision.IN_PROGRESS);

        assertThatThrownBy(() -> service.print(ro)).isInstanceOf(BizException.class);
        assertThat(printer.printedSheets()).isZero();
    }

    @Test
    @DisplayName("打印失败（打印机离线）→ 返回 success=false，不累加计数，并标记幂等失败以便重试")
    void printFailureShouldNotIncreaseCount() {
        printer.goOffline();
        when(idempotentService.begin(any(), any(), any())).thenReturn(IdempotentService.Decision.PROCEED);
        when(shipmentPrintMapper.selectPrintInfo(anyLong(), eq("SHP-1001"))).thenReturn(row(3, false));

        LabelPrintVo vo = service.print(ro);

        assertThat(vo.isSuccess()).isFalse();
        assertThat(vo.getMessage()).contains("离线");
        assertThat(vo.getPrintCount()).as("失败不改变累计计数").isEqualTo(3);
        verify(shipmentPrintMapper, never()).increasePrintCount(anyLong(), any(), anyInt(), any());
        verify(idempotentService).fail("SHP-1001:1");
    }

    @Test
    @DisplayName("运单不存在 → 抛业务异常并标记幂等失败")
    void shipmentNotFoundShouldFail() {
        when(idempotentService.begin(any(), any(), any())).thenReturn(IdempotentService.Decision.PROCEED);
        when(shipmentPrintMapper.selectPrintInfo(anyLong(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.print(ro))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("装运单不存在");
        verify(idempotentService).fail("SHP-1001:1");
    }

    @Test
    @DisplayName("面单已作废 → 拒绝打印（重印会与承运商数据不一致）")
    void voidedLabelShouldBeRejected() {
        when(idempotentService.begin(any(), any(), any())).thenReturn(IdempotentService.Decision.PROCEED);
        when(shipmentPrintMapper.selectPrintInfo(anyLong(), eq("SHP-1001"))).thenReturn(row(1, true));

        assertThatThrownBy(() -> service.print(ro))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已作废");
        assertThat(printer.printedSheets()).isZero();
    }

    @Test
    @DisplayName("计数失败（受影响 0 行）→ 仍返回成功，不重打（纸已出，重复出纸代价更大）")
    void countFailureShouldNotReprint() {
        when(idempotentService.begin(any(), any(), any())).thenReturn(IdempotentService.Decision.PROCEED);
        when(shipmentPrintMapper.selectPrintInfo(anyLong(), eq("SHP-1001"))).thenReturn(row(0, false));
        when(shipmentPrintMapper.increasePrintCount(anyLong(), any(), anyInt(), any())).thenReturn(0);

        LabelPrintVo vo = service.print(ro);

        assertThat(vo.isSuccess()).isTrue();
        assertThat(printer.printedSheets()).as("只出一次纸").isEqualTo(2);
        assertThat(vo.getPrintCount()).as("计数按预期值返回（实际以库为准，需人工核对时会记 error 日志）").isEqualTo(2);
    }

    /**
     * 造运单投影。
     *
     * @param printCount 已打印张数
     * @param labelVoid  是否已作废
     * @return 运单投影
     */
    private ShipmentPrintRow row(int printCount, boolean labelVoid) {
        ShipmentPrintRow row = new ShipmentPrintRow();
        row.setShpNo("SHP-1001");
        row.setWaybillNo("WB-1");
        row.setLabelUrl("http://label/1.pdf");
        row.setPrintCount(printCount);
        row.setLabelVoid(labelVoid);
        row.setStatus("已取号");
        return row;
    }

}
