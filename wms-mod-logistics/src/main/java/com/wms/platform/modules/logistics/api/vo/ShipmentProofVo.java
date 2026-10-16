package com.wms.platform.modules.logistics.api.vo;

import com.wms.platform.modules.logistics.app.ShipmentProofService;

import java.time.LocalDateTime;

/**
 * 发货单影像视图（P5）。
 *
 * @param id           影像 ID
 * @param shipmentNo   发货单号
 * @param bizType      用途
 * @param objectKey    对象键
 * @param originalName 原始文件名
 * @param sizeBytes    字节数
 * @param sha256       内容哈希（可用于校验下载到的内容与登记一致）
 * @param downloadUrl  预签名 URL（本地存储模式下为 null）
 * @param downloadHint 下载方式说明（前端按此选择直连或应用内下载）
 * @param createdAt    上传时间
 */
public record ShipmentProofVo(Long id, String shipmentNo, String bizType, String objectKey, String originalName,
                              Long sizeBytes, String sha256, String downloadUrl, String downloadHint,
                              LocalDateTime createdAt) {

    /**
     * 由应用层视图转换（静态工厂：禁止在 Controller 里手工 new + set）。
     *
     * @param view 应用层视图
     * @return 展示对象
     */
    public static ShipmentProofVo of(ShipmentProofService.ProofView view) {
        return new ShipmentProofVo(view.id(), view.shipmentNo(), view.bizType(), view.objectKey(),
                view.originalName(), view.sizeBytes(), view.sha256(), view.downloadUrl(),
                view.downloadHint(), view.createdAt());
    }
}
