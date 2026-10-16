package com.wms.platform.common.device.dto;

/**
 * 打印任务（服务端下发 → 打印端口执行）。
 *
 * <p>为什么任务里带 {@code taskId}：打印是**不可回滚的物理动作**，必须能在重试时被客户端去重，
 * 否则网络抖动会产生两张面单（现场后果：一个包裹两张面单，揽收时对不上）。
 *
 * @param taskId      打印任务 ID（幂等去重键；建议用"业务号 + 打印序号"派生）
 * @param shpNo       装运单号（面单打印的归属单据）
 * @param waybillNo   承运单号（未取号时为空）
 * @param labelUrl    面单地址（承运商返回，客户端据此下载并打印）
 * @param printerCode 目标打印机编码（{@code md_device.device_code}）
 * @param copies      打印份数（1~10）
 */
public record PrintTaskDto(String taskId, String shpNo, String waybillNo, String labelUrl,
                           String printerCode, int copies) {

    /** 单次允许的最大份数（防止误传导致批量出纸）。 */
    public static final int MAX_COPIES = 10;

    /**
     * 静态工厂（B4）。
     *
     * @param taskId      任务 ID
     * @param shpNo       装运单号
     * @param waybillNo   承运单号
     * @param labelUrl    面单地址
     * @param printerCode 打印机编码
     * @param copies      份数
     * @return 打印任务
     */
    public static PrintTaskDto of(String taskId, String shpNo, String waybillNo, String labelUrl,
                                  String printerCode, int copies) {
        return new PrintTaskDto(taskId, shpNo, waybillNo, labelUrl, printerCode, copies);
    }
}
