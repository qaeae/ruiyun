package com.wms.platform.common.storage;

/**
 * 已存储文件的元数据（会落库，是"影像去哪了"的凭据）。
 *
 * @param objectKey   对象键（存储中的唯一标识）
 * @param size        字节数
 * @param contentType MIME 类型
 * @param sha256      内容 SHA-256（**校验与去重的依据**：回单影像被质疑"被改过"时可自证）
 * @param storage     存储介质描述（LOCAL/MINIO + 位置，便于排障时确认"到底存哪了"）
 */
public record StoredFile(String objectKey, long size, String contentType, String sha256, String storage) {
}
