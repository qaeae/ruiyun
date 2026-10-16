package com.wms.platform.modules.au.app;

import com.wms.platform.common.approval.ApprovalActionHandler;

import com.wms.platform.modules.au.api.vo.ApprovalVo;
import com.wms.platform.common.approval.ApprovalBizType;

import java.util.List;

/**
 * 双人复核服务（`02` M2-05、`03` API-AUTH-04/05、`01` §3.16）。
 *
 * <p><b>这是"敏感操作"的统一入口</b>：任何需要复核的动作都走同一套流程——
 * 提交（含内容快照与指纹）→ 待办 → 他人复核 → 执行副作用 → 留痕。
 *
 * <p><b>复核点放在接口入口层</b>：高危写接口不再直接执行，而是"提交审批单"；
 * 通过后由 {@link ApprovalActionHandler} 调服务层执行。
 * 为什么不放在服务层：审批通过后的执行本身就要调服务，若服务层也强制复核会形成死循环。
 * 代价是"内网直调服务可绕过复核"——一期靠"服务不对外暴露、调用方只有审批回调/定时任务"约束，
 * 已记入 `01` §3.16。
 */
public interface ApprovalService {

    /**
     * 提交审批（同一业务对象幂等：已存在则返回既有单号）。
     *
     * @param bizType     业务类型
     * @param bizId       业务对象 ID（可空）
     * @param bizNo       业务单号（可空）
     * @param warehouseId 仓库（可空）
     * @param merchantId  商家（可空）
     * @param amount      金额（可空，涉金额类型必填）
     * @param payloadJson 审批内容快照（执行时以它为准）
     * @param reason      申请事由
     * @return 审批单号
     */
    String submit(ApprovalBizType bizType, Long bizId, String bizNo, Long warehouseId, Long merchantId,
                  java.math.BigDecimal amount, String payloadJson, String reason);

    /**
     * 复核通过：双人校验 → CAS 置通过 → 校验内容未变 → 执行处理器 → 标记已执行。
     *
     * @param id         审批单 ID
     * @param approverId 复核人（取自令牌身份）
     * @return 审批单号
     */
    String approve(long id, long approverId);

    /**
     * 驳回（须填原因）。
     *
     * @param id         审批单 ID
     * @param reason     驳回原因
     * @param approverId 复核人
     */
    void reject(long id, String reason, long approverId);

    /**
     * 撤回（仅发起人本人）。
     *
     * @param id       审批单 ID
     * @param operator 操作人
     */
    void withdraw(long id, long operator);

    /**
     * 待复核列表。
     *
     * @param bizType 业务类型（可空）
     * @return 列表
     */
    List<ApprovalVo> pending(String bizType);

    /**
     * 我发起的审批。
     *
     * @param applicantId 发起人
     * @return 列表
     */
    List<ApprovalVo> myApplications(long applicantId);

    /**
     * 详情。
     *
     * @param id 审批单 ID
     * @return 详情
     */
    ApprovalVo detail(long id);
}
