package com.wms.platform.modules.au.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.au.infrastructure.po.AuApprovalPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 双人复核单 Mapper（表 {@code au_approval}）。
 *
 * <p>状态变更一律**条件更新（CAS）**：并发下"两个人同时点通过"或"一个人点通过、另一个人点驳回"，
 * 只能有一次生效——否则会出现"既通过又驳回"的两条结论同时落库。
 */
@Mapper
public interface AuApprovalMapper extends BaseMapper<AuApprovalPo> {

    /**
     * 按单号查。
     *
     * @param tenantId   租户
     * @param approvalNo 单号
     * @return 审批单，不存在返回 {@code null}
     */
    default AuApprovalPo selectByNo(long tenantId, String approvalNo) {
        return selectOne(Wrappers.<AuApprovalPo>lambdaQuery()
                .eq(AuApprovalPo::getTenantId, tenantId)
                .eq(AuApprovalPo::getApprovalNo, approvalNo)
                .last("limit 1"));
    }

    /**
     * 按 ID 查（含租户过滤）。
     *
     * @param tenantId 租户
     * @param id       记录 ID
     * @return 审批单，不存在返回 {@code null}
     */
    default AuApprovalPo selectByIdSafe(long tenantId, long id) {
        return selectOne(Wrappers.<AuApprovalPo>lambdaQuery()
                .eq(AuApprovalPo::getTenantId, tenantId)
                .eq(AuApprovalPo::getId, id)
                .last("limit 1"));
    }

    /**
     * 按业务对象查（唯一键 {@code uk_approval_biz}；幂等提交靠它）。
     *
     * @param tenantId 租户
     * @param bizType  业务类型
     * @param bizId    业务对象 ID
     * @return 审批单，不存在返回 {@code null}
     */
    default AuApprovalPo selectByBiz(long tenantId, String bizType, Long bizId) {
        if (bizId == null) {
            return null;
        }
        return selectOne(Wrappers.<AuApprovalPo>lambdaQuery()
                .eq(AuApprovalPo::getTenantId, tenantId)
                .eq(AuApprovalPo::getBizType, bizType)
                .eq(AuApprovalPo::getBizId, bizId)
                .last("limit 1"));
    }

    /**
     * 按业务单号查（唯一键 {@code uk_approval_biz_no}）。
     *
     * <p><b>为什么需要它</b>：有些业务对象没有数值 ID（如"结算下发"以批次号为标识），
     * 而 {@link #selectByBiz} 对 {@code bizId == null} 直接返回 null——等于**没有防重**。
     * 联调时因此出现过两个后果：占位 0 让所有批次共用一个对象（第二次下发被拒），
     * 以及库存调整完全没防重（可提交两张单，都通过即重复扣减）。
     *
     * @param tenantId 租户
     * @param bizType  业务类型
     * @param bizNo    业务单号
     * @return 审批单，不存在或入参为空返回 {@code null}
     */
    default AuApprovalPo selectByBizNo(long tenantId, String bizType, String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            return null;
        }
        return selectOne(Wrappers.<AuApprovalPo>lambdaQuery()
                .eq(AuApprovalPo::getTenantId, tenantId)
                .eq(AuApprovalPo::getBizType, bizType)
                .eq(AuApprovalPo::getBizNo, bizNo)
                .last("limit 1"));
    }

    /**
     * 待复核列表（按发起时间正序：先提交的先处理）。
     *
     * @param tenantId 租户
     * @param bizType  业务类型（可空）
     * @return 列表
     */
    default List<AuApprovalPo> selectPending(long tenantId, String bizType) {
        return selectList(Wrappers.<AuApprovalPo>lambdaQuery()
                .eq(AuApprovalPo::getTenantId, tenantId)
                .eq(AuApprovalPo::getStatus, "待复核")
                .eq(bizType != null && !bizType.isBlank(), AuApprovalPo::getBizType, bizType)
                .orderByAsc(AuApprovalPo::getApplyAt));
    }

    /**
     * 我的申请列表。
     *
     * @param tenantId 租户
     * @param applyBy  发起人
     * @return 列表（按发起时间倒序）
     */
    default List<AuApprovalPo> selectByApplicant(long tenantId, long applyBy) {
        return selectList(Wrappers.<AuApprovalPo>lambdaQuery()
                .eq(AuApprovalPo::getTenantId, tenantId)
                .eq(AuApprovalPo::getApplyBy, applyBy)
                .orderByDesc(AuApprovalPo::getApplyAt));
    }

    /**
     * 复核通过（**CAS：仅「待复核」可置「已通过」**）。
     *
     * @param tenantId   租户
     * @param id         审批单 ID
     * @param approverId 复核人
     * @return 影响行数（0 = 已被他人处理或状态已变 → 调用方必须报冲突）
     */
    @Update("UPDATE au_approval SET status = '已通过', approve_by = #{approverId}, approve_at = NOW(3), "
            + "updated_by = #{approverId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = '待复核' AND deleted = 0")
    int approve(@Param("tenantId") long tenantId, @Param("id") long id, @Param("approverId") Long approverId);

    /**
     * 驳回（CAS：仅「待复核」）。
     *
     * @param tenantId   租户
     * @param id         审批单 ID
     * @param reason     驳回原因
     * @param approverId 复核人
     * @return 影响行数
     */
    @Update("UPDATE au_approval SET status = '已驳回', reject_reason = #{reason}, approve_by = #{approverId}, "
            + "approve_at = NOW(3), updated_by = #{approverId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = '待复核' AND deleted = 0")
    int reject(@Param("tenantId") long tenantId, @Param("id") long id, @Param("reason") String reason,
               @Param("approverId") Long approverId);

    /**
     * 撤回（CAS：仅「待复核」且**仅本人**）。
     *
     * @param tenantId 租户
     * @param id       审批单 ID
     * @param applyBy  发起人
     * @return 影响行数
     */
    @Update("UPDATE au_approval SET status = '已撤回', updated_by = #{applyBy} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = '待复核' AND apply_by = #{applyBy} "
            + "AND deleted = 0")
    int withdraw(@Param("tenantId") long tenantId, @Param("id") long id, @Param("applyBy") Long applyBy);

    /**
     * 标记副作用已执行（CAS：仅「已通过」且未执行）。
     *
     * @param tenantId   租户
     * @param id         审批单 ID
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Update("UPDATE au_approval SET executed = 1, executed_at = NOW(3), updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = '已通过' AND executed = 0 AND deleted = 0")
    int markExecuted(@Param("tenantId") long tenantId, @Param("id") long id, @Param("operatorId") Long operatorId);
}
