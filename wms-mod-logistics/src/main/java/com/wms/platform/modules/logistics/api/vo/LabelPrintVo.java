package com.wms.platform.modules.logistics.api.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 面单打印登记结果（03 API-LOG-03）。
 *
 * <p>刻意把 {@code success} 与 {@code printCount} 都返回：现场最需要知道的是
 * <b>"这张面单一共打过几次"</b>（补打次数异常往往是现场私自打印或客户端重试失控的信号）。
 */
@Getter
@Setter
public class LabelPrintVo {

    /** 装运单号。 */
    private String shpNo;

    /** 承运单号。 */
    private String waybillNo;

    /** 打印机编码。 */
    private String printerCode;

    /** 本次出纸张数（打印失败时为 0）。 */
    private int sheets;

    /** 累计打印张数（打印成功后才累加）。 */
    private int printCount;

    /** 是否打印成功。 */
    private boolean success;

    /** 结果说明（失败时为中文原因）。 */
    private String message;

    /** 完成时间。 */
    private LocalDateTime finishedAt;

    /**
     * 静态工厂（B4）。
     *
     * @param shpNo       装运单号
     * @param waybillNo   承运单号
     * @param printerCode 打印机编码
     * @param sheets      本次张数
     * @param printCount  累计张数
     * @param success     是否成功
     * @param message     说明
     * @return 打印登记结果
     */
    public static LabelPrintVo of(String shpNo, String waybillNo, String printerCode, int sheets,
                                  int printCount, boolean success, String message) {
        LabelPrintVo vo = new LabelPrintVo();
        vo.setShpNo(shpNo);
        vo.setWaybillNo(waybillNo);
        vo.setPrinterCode(printerCode);
        vo.setSheets(sheets);
        vo.setPrintCount(printCount);
        vo.setSuccess(success);
        vo.setMessage(message);
        vo.setFinishedAt(LocalDateTime.now());
        return vo;
    }
}
