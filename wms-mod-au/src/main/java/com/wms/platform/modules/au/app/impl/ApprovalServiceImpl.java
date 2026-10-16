package com.wms.platform.modules.au.app.impl;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.au.api.vo.ApprovalVo;
import com.wms.platform.common.approval.exec.ApprovalExecutor;
import com.wms.platform.common.approval.ApprovalContext;
import com.wms.platform.modules.au.app.ApprovalService;
import com.wms.platform.common.approval.ApprovalBizType;
import com.wms.platform.modules.au.domain.ApprovalRule;
import com.wms.platform.modules.au.infrastructure.mapper.AuApprovalMapper;
import com.wms.platform.modules.au.infrastructure.po.AuApprovalPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 双人复核服务实现（{@code 01} §3.16）。
 *
 * <p>规范落地：B1（Wrapper 只在 Mapper）、B4（Po 工厂）、B5（带码异常）、B7（编号步骤注释）、B9（构造注入）。
 *
 * <p><b>本类的三个关键动作</b>：
 * <ol>
 *   <li>提交时对内容快照算 SHA-256 指纹并落库；</li>
 *   <li>复核通过时**先 CAS 置状态**（挡住并发双人复核），再校验指纹、执行副作用、标记已执行；</li>
 *   <li>找不到执行器时**拒绝通过**——否则会出现"审批已通过、但什么都没发生"的空批单，
 *       比"批不了"更危险（发起人以为事情办了）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ApprovalServiceImpl.class);

    /** 审批单 Mapper。 */
    private final AuApprovalMapper approvalMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** JSON（内容规范化：MySQL JSON 列会重排键，直接对原始字符串算指纹必然不一致）。 */
    private final ObjectMapper objectMapper;

    /**
     * 执行处理器（由各业务域提供实现，Spring 自动注入）。
     *
     * <p>用 {@code List} 注入 + 运行期按类型建索引：新增业务类型只需要新增一个 Handler，
     * **不需要改本类**（开闭原则在这里有实际价值——复核矩阵会持续扩项）。
     */
    private final ApprovalExecutor approvalExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submit(ApprovalBizType bizType, Long bizId, String bizNo, Long warehouseId, Long merchantId,
                         BigDecimal amount, String payloadJson, String reason) {
        long tenant = TenantContext.tenantId();
        Long applicant = TenantContext.userId();
        // 1. 入参校验（涉金额类型必须给金额、事由与内容快照必填）
        ApprovalRule.assertSubmittable(bizType, amount, reason, payloadJson);
        // 2. 幂等：同一业务对象只允许一张审批单
        //    有数值 ID 时按 ID 判重（uk_approval_biz）；没有数值 ID 时**必须**按业务单号判重
        //    （uk_approval_biz_no）——否则要么"所有对象共用一个占位 ID"（第二次就被拒），
        //    要么完全没有防重（同一件事提交两张单，都通过即重复执行）。
        if ((bizId == null) && (bizNo == null || bizNo.isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "审批必须绑定业务对象标识（bizId 或 bizNo 至少一个），否则无法防重");
        }
        AuApprovalPo exists = bizId != null
                ? approvalMapper.selectByBiz(tenant, bizType.code(), bizId)
                : approvalMapper.selectByBizNo(tenant, bizType.code(), bizNo);
        if (exists != null) {
            if (ApprovalRule.STATUS_PENDING.equals(exists.getStatus())) {
                log.info("该业务对象已有待复核审批单，返回既有单号 approvalNo={}", exists.getApprovalNo());
                return exists.getApprovalNo();
            }
            // 已通过/已驳回/已撤回：不允许再提交第二张，否则同一件事会有两份结论
            throw new BizException(ErrorCode.APPROVAL_ALREADY_EXISTS,
                    "该业务对象已存在审批记录（" + exists.getApprovalNo() + " / " + exists.getStatus()
                            + "），不可重复提交；如需再次操作请更换业务对象或走人工流程");
        }
        // 3. 建单（状态由 Po 工厂内置「待复核」）
        String approvalNo = ApprovalRule.approvalNo(idGenerator.nextId());
        AuApprovalPo po = AuApprovalPo.of(idGenerator.nextId(), tenant, approvalNo, bizType.code(), bizId, bizNo,
                warehouseId, merchantId, amount, payloadJson, fingerprint(payloadJson), reason, applicant);
        try {
            approvalMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            // 唯一键兜住重复提交。但**必须区分是哪把键**，否则报错会把人带偏（真实踩坑）：
            //   ① uk_approval_no   → 单号生成碰撞（生成器缺陷，与"业务对象"无关）
            //   ② uk_approval_biz / uk_approval_biz_no → 同一业务对象已有审批单（业务语义）
            // 之前不分青红皂白按 ② 处理，遇到 ① 时会报"该业务对象已存在审批记录"，
            // 让人去查业务单号，而真因是单号生成器取模导致的碰撞。
            String duplicateKey = ex.getMessage() == null ? "" : ex.getMessage();
            if (duplicateKey.contains("uk_approval_no")) {
                log.error("审批单号碰撞（生成器问题，非业务重复）approvalNo={} bizType={} bizNo={}",
                        approvalNo, bizType.code(), bizNo, ex);
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "审批单号生成冲突（" + approvalNo + "），请重试；若持续出现请联系研发检查单号生成器", ex);
            }
            // 并发重复提交：唯一键兜住，查回既有单
            AuApprovalPo concurrent = approvalMapper.selectByBiz(tenant, bizType.code(), bizId);
            if (concurrent == null) {
                concurrent = approvalMapper.selectByBizNo(tenant, bizType.code(), bizNo);
            }
            if (concurrent == null) {
                throw ex;
            }
            return concurrent.getApprovalNo();
        }
        log.info("提交复核申请 approvalNo={} bizType={} bizId={} amount={} 事由={}", approvalNo, bizType.code(),
                bizId, amount, reason);
        return approvalNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String approve(long id, long approverId) {
        long tenant = TenantContext.tenantId();
        // 1. 取单 + 状态/双人校验
        AuApprovalPo po = requireApproval(tenant, id);
        ApprovalRule.assertReviewable(po.getStatus(), po.getApplyBy(), approverId);
        ApprovalBizType bizType = ApprovalBizType.of(po.getBizType());
        // 2. 执行器必须在（否则"批了但没执行"，是最坏的一种结果）
        // 3. 先 CAS 置「已通过」：并发下两人同时点通过，只有一次能成功
        int affected = approvalMapper.approve(tenant, id, approverId);
        if (affected == 0) {
            throw new BizException(ErrorCode.APPROVAL_NOT_PENDING,
                    "该审批单已被处理或状态已变化，请刷新后重试（可能有人同时复核了）");
        }
        // 4. 校验内容未被改动（防止"批的是 100 元、执行的是 10000 元"）
        ApprovalRule.assertPayloadUnchanged(po.getPayloadHash(), fingerprint(po.getPayloadJson()));
        // 5. 执行副作用（同一事务：抛异常 → 状态回滚，不会出现"已通过但没执行"）
        ApprovalContext context = new ApprovalContext(po.getTenantId(), po.getId(), po.getApprovalNo(), bizType,
                po.getBizId(), po.getBizNo(),
                po.getWarehouseId(), po.getMerchantId(), po.getAmount(), po.getPayloadJson(), po.getPayloadHash(),
                po.getReason(), po.getApplyBy(), approverId);
        approvalExecutor.execute(context, approverId);
        // 6. 标记已执行（作为"副作用确实落地"的唯一凭据）
        approvalMapper.markExecuted(tenant, id, approverId);
        log.warn("复核通过并已执行 approvalNo={} bizType={} 发起人={} 复核人={}（双人复核闭环）", po.getApprovalNo(),
                bizType.code(), po.getApplyBy(), approverId);
        return po.getApprovalNo();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(long id, String reason, long approverId) {
        long tenant = TenantContext.tenantId();
        // 1. 取单 + 状态/原因校验
        AuApprovalPo po = requireApproval(tenant, id);
        ApprovalRule.assertRejectable(po.getStatus(), reason);
        // 2. 驳回同样要求"非本人"：自己发起的单自己驳回虽无风险，但会让"复核"这一环节形同虚设
        ApprovalRule.assertReviewable(po.getStatus(), po.getApplyBy(), approverId);
        // 3. CAS 置「已驳回」
        int affected = approvalMapper.reject(tenant, id, reason, approverId);
        if (affected == 0) {
            throw new BizException(ErrorCode.APPROVAL_NOT_PENDING, "该审批单已被处理或状态已变化，请刷新后重试");
        }
        log.info("复核驳回 approvalNo={} 复核人={} 原因={}", po.getApprovalNo(), approverId, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(long id, long operator) {
        long tenant = TenantContext.tenantId();
        // 1. 取单 + 仅本人可撤回
        AuApprovalPo po = requireApproval(tenant, id);
        ApprovalRule.assertWithdrawable(po.getStatus(), po.getApplyBy(), operator);
        // 2. CAS 置「已撤回」
        int affected = approvalMapper.withdraw(tenant, id, operator);
        if (affected == 0) {
            throw new BizException(ErrorCode.APPROVAL_NOT_PENDING, "该审批单已被处理或状态已变化，请刷新后重试");
        }
        log.info("撤回复核申请 approvalNo={} 操作人={}", po.getApprovalNo(), operator);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalVo> pending(String bizType) {
        long tenant = TenantContext.tenantId();
        return toVos(approvalMapper.selectPending(tenant, bizType));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalVo> myApplications(long applicantId) {
        long tenant = TenantContext.tenantId();
        return toVos(approvalMapper.selectByApplicant(tenant, applicantId));
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalVo detail(long id) {
        long tenant = TenantContext.tenantId();
        return toVo(requireApproval(tenant, id));
    }

    /**
     * 取审批单（不存在则报错）。
     *
     * @param tenantId 租户
     * @param id       记录 ID
     * @return 审批单
     */
    private AuApprovalPo requireApproval(long tenantId, long id) {
        AuApprovalPo po = approvalMapper.selectByIdSafe(tenantId, id);
        if (po == null) {
            throw new BizException(ErrorCode.APPROVAL_NOT_FOUND, "审批单不存在：" + id);
        }
        return po;
    }

    /**
     * 计算内容指纹（**先规范化再算 SHA-256**）。
     *
     * <p><b>为什么必须规范化</b>（真实踩坑，由 { ApprovalIT} 抓出）：
     * `payload_json` 是 MySQL 的 JSON 列，**存取会重排键、去掉空白**——
     * 提交时算的指纹与读回后重算的指纹必然不同，校验就变成了"永远阻断"。
     * 规范化（键排序 + 紧凑输出）之后，指纹只取决于**内容本身**，
     * 与"客户端怎么格式化 JSON""数据库怎么存储"都无关。
     *
     * @param payloadJson 内容快照
     * @return 指纹
     */
    private String fingerprint(String payloadJson) {
        return sha256(canonicalize(payloadJson));
    }

    /**
     * 规范化 JSON：**递归把对象转成 TreeMap（键排序）** + 紧凑输出。
     *
     * <p>注意：不能只靠 { SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS}——
     * 它只对 { java.util.Map} 生效，对 { JsonNode}（ObjectNode 保留原始插入顺序）**无效**。
     * 这是本功能第二个踩坑点：第一个坑是"直接对原始字符串算指纹"，第二个坑是
     * "以为开了配置就排好序了"——两者都会让校验恒定失败或恒定通过。
     *
     * @param payloadJson 原始 JSON
     * @return 规范化后的字符串
     */
    private String canonicalize(String payloadJson) {
        try {
            Object plain = toPlain(objectMapper.readTree(payloadJson));
            return objectMapper.writeValueAsString(plain);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审批内容快照不是合法 JSON：" + payloadJson, ex);
        }
    }

    /**
     * JsonNode → 纯 Java 对象（对象转 TreeMap 以固定键序，数组转 List）。
     *
     * @param node JSON 节点
     * @return 纯 Java 对象（TreeMap/List/基本类型）
     */
    private Object toPlain(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            java.util.TreeMap<String, Object> map = new java.util.TreeMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), toPlain(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            node.forEach(child -> list.add(toPlain(child)));
            return list;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        return node.asText();
    }

    /**
     * 计算 SHA-256（十六进制小写）。
     *
     * @param text 文本
     * @return 指纹
     */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常
            throw new BizException(ErrorCode.SYSTEM_ERROR, "SHA-256 不可用", ex);
        }
    }

    /**
     * 批量转换。
     *
     * @param rows 记录
     * @return 视图列表
     */
    private List<ApprovalVo> toVos(List<AuApprovalPo> rows) {
        List<ApprovalVo> vos = new ArrayList<>(rows.size());
        for (AuApprovalPo row : rows) {
            vos.add(toVo(row));
        }
        return vos;
    }

    /**
     * 转换。
     *
     * @param row 记录
     * @return 视图
     */
    private ApprovalVo toVo(AuApprovalPo row) {
        Map<String, String> labels = new HashMap<>();
        for (ApprovalBizType type : ApprovalBizType.values()) {
            labels.put(type.code(), type.getLabel());
        }
        return new ApprovalVo(row.getId(), row.getApprovalNo(), row.getBizType(),
                labels.getOrDefault(row.getBizType(), row.getBizType()), row.getBizId(), row.getBizNo(),
                row.getAmount(), row.getPayloadJson(), row.getReason(), row.getStatus(), row.getApplyBy(),
                row.getApplyAt(), row.getApproveBy(), row.getApproveAt(), row.getRejectReason(),
                row.getExecuted() != null && row.getExecuted() == 1);
    }
}
