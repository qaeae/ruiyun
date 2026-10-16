package com.wms.platform.common.device.dto;

import java.time.LocalDateTime;

/**
 * 打印结果（打印端口执行后回传）。
 *
 * @param success     是否打印成功
 * @param taskId      打印任务 ID
 * @param printerCode 打印机编码
 * @param message     结果说明（失败时为中文原因，如"打印机离线"）
 * @param finishedAt  完成时间
 */
public record PrintResultDto(boolean success, String taskId, String printerCode,
                             String message, LocalDateTime finishedAt) {

    /**
     * 静态工厂：成功。
     *
     * @param taskId      任务 ID
     * @param printerCode 打印机编码
     * @return 成功结果
     */
    public static PrintResultDto success(String taskId, String printerCode) {
        return new PrintResultDto(true, taskId, printerCode, "ok", LocalDateTime.now());
    }

    /**
     * 静态工厂：失败。
     *
     * @param taskId      任务 ID
     * @param printerCode 打印机编码
     * @param message     失败原因（中文）
     * @return 失败结果
     */
    public static PrintResultDto failure(String taskId, String printerCode, String message) {
        return new PrintResultDto(false, taskId, printerCode, message, LocalDateTime.now());
    }
}
