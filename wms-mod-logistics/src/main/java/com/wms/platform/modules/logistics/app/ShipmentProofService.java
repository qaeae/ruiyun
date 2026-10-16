package com.wms.platform.modules.logistics.app;

import com.wms.platform.modules.logistics.domain.ProofBizType;

import java.io.InputStream;
import java.util.List;

/**
 * 发货单影像服务（P5）：上传、查询、下载。
 */
public interface ShipmentProofService {

    /**
     * 上传影像。
     *
     * @param shipmentNo   发货单号
     * @param bizType      用途
     * @param originalName 原始文件名
     * @param contentType  MIME 类型
     * @param size         字节数
     * @param content      内容流
     * @param remark       备注
     * @return 影像视图（含预签名 URL 或"需应用内下载"的说明）
     */
    ProofView upload(String shipmentNo, ProofBizType bizType, String originalName, String contentType,
                     long size, InputStream content, String remark);

    /**
     * 按单号查询影像。
     *
     * @param shipmentNo 发货单号
     * @param bizType    用途（可为空）
     * @return 影像列表
     */
    List<ProofView> list(String shipmentNo, ProofBizType bizType);

    /**
     * 应用内下载（本地存储模式或预签名不可用时使用）。
     *
     * @param shipmentNo 发货单号
     * @param id         影像 ID
     * @return 下载内容（含元数据）
     */
    ProofDownload download(String shipmentNo, long id);

    /**
     * 影像视图。
     *
     * @param id           影像 ID
     * @param shipmentNo   发货单号
     * @param bizType      用途
     * @param objectKey    对象键
     * @param originalName 原始文件名
     * @param sizeBytes    字节数
     * @param sha256       内容哈希
     * @param downloadUrl  预签名 URL（本地模式下为空）
     * @param downloadHint 下载方式说明（前端据此选择直连还是走应用接口）
     * @param createdAt    上传时间
     */
    record ProofView(Long id, String shipmentNo, String bizType, String objectKey, String originalName,
                     Long sizeBytes, String sha256, String downloadUrl, String downloadHint,
                     java.time.LocalDateTime createdAt) {
    }

    /**
     * 应用内下载结果。
     *
     * @param fileName    文件名
     * @param contentType MIME
     * @param sizeBytes   字节数
     * @param sha256      内容哈希
     * @param content     内容流
     */
    record ProofDownload(String fileName, String contentType, long sizeBytes, String sha256, InputStream content) {
    }
}
