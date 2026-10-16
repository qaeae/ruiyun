package com.wms.platform.modules.logistics.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.datasource.ReadOnly;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.constant.TraceConst;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.storage.FileStoragePort;
import com.wms.platform.common.storage.StoredFile;
import com.wms.platform.modules.logistics.app.ShipmentProofService;
import com.wms.platform.modules.logistics.domain.ObjectKeyRule;
import com.wms.platform.modules.logistics.domain.ProofBizType;
import com.wms.platform.modules.logistics.infrastructure.mapper.ShipmentProofMapper;
import com.wms.platform.modules.logistics.infrastructure.po.ShipmentProofPo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 发货单影像服务实现（P5）。
 *
 * <p><b>顺序很重要：先落对象存储、再写登记表</b>。
 * 反过来的话，登记表里会出现"指向不存在文件"的记录，业务侧读到它去下载就 404；
 * 而当前顺序的失败后果是"文件已存但没登记"——属于可清理的孤儿对象（存储便宜、可巡检），
 * 比"记录存在但文件没有"（业务不可用）轻得多。
 *
 * <p><b>数据库只存键与元数据</b>：影像本体在对象存储。sha256 落库，用于日后自证内容未被替换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentProofServiceImpl implements ShipmentProofService {

    /** 元数据字段最大长度（与 DDL 对齐，避免超长导致写库失败）。 */
    private static final int MAX_NAME_LENGTH = 255;

    /** 影像登记 Mapper。 */
    private final ShipmentProofMapper proofMapper;

    /** 对象存储端口（LOCAL 或 MINIO）。 */
    private final FileStoragePort fileStorage;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** 配置（预签名 TTL 等）。 */
    private final WmsProperties wmsProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProofView upload(String shipmentNo, ProofBizType bizType, String originalName, String contentType,
                            long size, InputStream content, String remark) {
        long tenant = TenantContext.tenantId();
        // 1. 上传前校验：大小与类型不合规就地拒绝（不要把不合规文件先传到存储再回滚）
        ObjectKeyRule.assertUploadable(size, originalName);
        // 2. 生成对象键（含租户/日期/单号 + 唯一片段，避免同单多次上传互相覆盖）
        long fileId = idGenerator.nextId();
        String objectKey = ObjectKeyRule.build(tenant, shipmentNo, bizType, originalName,
                Long.toString(fileId, 36), LocalDate.now());
        // 3. 先落存储（失败即失败，不留脏登记）
        StoredFile stored = fileStorage.put(objectKey, content, size,
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        // 4. 再写登记（唯一键 uk_proof_object 兜住重复登记）
        ShipmentProofPo po = ShipmentProofPo.of(fileId, tenant, shipmentNo, bizType.name(), objectKey,
                truncate(originalName), stored.contentType(), stored.size(), stored.sha256(), stored.storage(),
                MDC.get(TraceConst.MDC_TRACE_ID), TenantContext.userId());
        po.setRemark(truncate(remark));
        try {
            proofMapper.insert(po);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发/重试导致同一键重复登记：不影响业务（文件已在存储中），返回既有记录
            log.warn("[影像] 重复登记，返回既有记录 objectKey={}", objectKey);
            return toView(proofMapper.selectByObjectKey(tenant, objectKey));
        }
        log.info("[影像] 已登记 shipmentNo={} type={} key={} size={} sha256={}",
                shipmentNo, bizType, objectKey, stored.size(), stored.sha256());
        return toView(po);
    }

    @Override
    @ReadOnly("回单影像列表浏览：不参与业务判断")
    public List<ProofView> list(String shipmentNo, ProofBizType bizType) {
        long tenant = TenantContext.tenantId();
        return proofMapper.selectByShipment(tenant, shipmentNo, bizType == null ? null : bizType.name())
                .stream().map(this::toView).toList();
    }

    @Override
    public ProofDownload download(String shipmentNo, long id) {
        long tenant = TenantContext.tenantId();
        ShipmentProofPo po = proofMapper.selectById(id);
        // 1. 必须存在、属于本租户、且与请求的单号一致（防止用别人的 ID 钓文件）
        if (po == null || !po.getTenantId().equals(tenant)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "影像不存在：" + id);
        }
        if (!po.getShipmentNo().equals(shipmentNo)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "影像不属于该发货单：" + shipmentNo);
        }
        // 2. 从存储读回
        InputStream content = fileStorage.get(po.getObjectKey());
        return new ProofDownload(po.getOriginalName(), po.getContentType(),
                po.getSizeBytes() == null ? 0 : po.getSizeBytes(), po.getSha256(), content);
    }

    /**
     * 转视图：带上预签名 URL（存储支持时）或"改用应用内下载"的提示。
     *
     * @param po 登记
     * @return 视图
     */
    private ProofView toView(ShipmentProofPo po) {
        String url = null;
        String hint;
        if (fileStorage.supportsPresignedUrl()) {
            url = fileStorage.presignedUrl(po.getObjectKey(),
                    Duration.ofMinutes(wmsProperties.getStorage().getPresignedTtlMinutes()));
            hint = "直连对象存储（预签名 URL，有效期 "
                    + wmsProperties.getStorage().getPresignedTtlMinutes() + " 分钟）";
        } else {
            // 本地存储模式：明确告诉前端走应用内下载，而不是给一个用不了的 URL
            hint = "本地存储模式：请调用应用内下载接口 GET /proofs/" + po.getId() + "/content";
        }
        return new ProofView(po.getId(), po.getShipmentNo(), po.getBizType(), po.getObjectKey(),
                po.getOriginalName(), po.getSizeBytes(), po.getSha256(), url, hint, po.getCreatedAt());
    }

    /**
     * 截断超长文本（DB 列长度有限，超长会直接写失败；截断比失败更合适，因为文件名只是展示用）。
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_NAME_LENGTH ? text : text.substring(0, MAX_NAME_LENGTH);
    }
}
