package com.wms.platform.modules.logistics.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 发货单影像登记持久化对象（文件本体在对象存储，这里只存"在哪 + 元数据"）。
 */
@Getter
@Setter
@TableName("lg_shipment_proof")
public class ShipmentProofPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 发货单号。 */
    private String shipmentNo;

    /** 用途（POD/EXCEPTION/LABEL/OTHER）。 */
    private String bizType;

    /** 对象键。 */
    private String objectKey;

    /** 原始文件名。 */
    private String originalName;

    /** MIME 类型。 */
    private String contentType;

    /** 字节数。 */
    private Long sizeBytes;

    /** 内容 SHA-256（自证凭据）。 */
    private String sha256;

    /** 存储介质描述。 */
    private String storage;

    /** 备注。 */
    private String remark;

    /** 上传请求 traceId（P4 全链路）。 */
    private String traceId;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 逻辑删除。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂（B4：禁止 new + 连续 setter）。
     *
     * @param id           主键
     * @param tenantId     租户
     * @param shipmentNo   发货单号
     * @param bizType      用途
     * @param objectKey    对象键
     * @param originalName 原始文件名
     * @param contentType  MIME
     * @param sizeBytes    字节数
     * @param sha256       内容哈希
     * @param storage      存储介质
     * @param traceId      上传请求 traceId
     * @param operatorId   上传人
     * @return 影像登记
     */
    public static ShipmentProofPo of(Long id, Long tenantId, String shipmentNo, String bizType, String objectKey,
                                     String originalName, String contentType, Long sizeBytes, String sha256,
                                     String storage, String traceId, Long operatorId) {
        ShipmentProofPo po = new ShipmentProofPo();
        po.id = id;
        po.tenantId = tenantId;
        po.shipmentNo = shipmentNo;
        po.bizType = bizType;
        po.objectKey = objectKey;
        po.originalName = originalName;
        po.contentType = contentType;
        po.sizeBytes = sizeBytes;
        po.sha256 = sha256;
        po.storage = storage;
        po.traceId = traceId;
        po.createdBy = operatorId;
        po.createdAt = LocalDateTime.now();
        po.deleted = 0;
        return po;
    }
}
