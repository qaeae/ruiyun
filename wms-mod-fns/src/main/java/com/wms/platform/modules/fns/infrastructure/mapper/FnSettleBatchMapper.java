package com.wms.platform.modules.fns.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.fns.infrastructure.po.FnSettleBatchPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 结算批次 Mapper（表 {@code fn_settle_batch}）。
 *
 * <p>所有状态变更都是**条件更新**（CAS）：并发下只有一次能成功，
 * 这是"重复下发 = 重复付款"的第一道防线（第二道是机构侧幂等号）。
 */
@Mapper
public interface FnSettleBatchMapper extends BaseMapper<FnSettleBatchPo> {

    /**
     * 按单号查。
     *
     * @param tenantId 租户
     * @param stbNo    批次号
     * @return 批次，不存在返回 {@code null}
     */
    default FnSettleBatchPo selectByNo(long tenantId, String stbNo) {
        return selectOne(Wrappers.<FnSettleBatchPo>lambdaQuery()
                .eq(FnSettleBatchPo::getTenantId, tenantId)
                .eq(FnSettleBatchPo::getStbNo, stbNo)
                .last("limit 1"));
    }

    /**
     * 按对账单查（唯一键 {@code uk_bill}：一张对账单只生成一个批次）。
     *
     * @param tenantId 租户
     * @param billId   对账单 ID
     * @return 批次，不存在返回 {@code null}
     */
    default FnSettleBatchPo selectByBill(long tenantId, long billId) {
        return selectOne(Wrappers.<FnSettleBatchPo>lambdaQuery()
                .eq(FnSettleBatchPo::getTenantId, tenantId)
                .eq(FnSettleBatchPo::getBillId, billId)
                .last("limit 1"));
    }

    /**
     * 列表（按创建时间倒序；Wrapper 收口在 Mapper，B1）。
     *
     * @param tenantId    租户
     * @param carrierCode 承运商（可空）
     * @param period      账期（可空）
     * @return 列表
     */
    default List<FnSettleBatchPo> selectListBy(long tenantId, String carrierCode, String period) {
        return selectList(Wrappers.<FnSettleBatchPo>lambdaQuery()
                .eq(FnSettleBatchPo::getTenantId, tenantId)
                .eq(carrierCode != null && !carrierCode.isBlank(), FnSettleBatchPo::getCarrierCode, carrierCode)
                .eq(period != null && !period.isBlank(), FnSettleBatchPo::getPeriod, period)
                .orderByDesc(FnSettleBatchPo::getCreatedAt));
    }

    /**
     * 下发成功（**条件更新：仅「待下发」可下发**）。
     *
     * @param tenantId      租户
     * @param stbNo         批次号
     * @param instructionNo 机构侧指令号
     * @param operatorId    操作人
     * @return 影响行数（0 = 已被下发/状态已变 → 调用方必须报冲突，不能当成功）
     */
    @Update("UPDATE fn_settle_batch SET status = '已下发', instruction_no = #{instructionNo}, "
            + "dispatched_at = NOW(3), updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND stb_no = #{stbNo} AND status = '待下发' AND deleted = 0")
    int markDispatched(@Param("tenantId") long tenantId, @Param("stbNo") String stbNo,
                       @Param("instructionNo") String instructionNo, @Param("operatorId") Long operatorId);

    /**
     * 下发失败（保留失败原因，便于重试与排查）。
     *
     * @param tenantId   租户
     * @param stbNo      批次号
     * @param failReason 失败原因
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Update("UPDATE fn_settle_batch SET status = '下发失败', fail_reason = #{failReason}, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND stb_no = #{stbNo} AND status = '待下发' AND deleted = 0")
    int markDispatchFailed(@Param("tenantId") long tenantId, @Param("stbNo") String stbNo,
                           @Param("failReason") String failReason, @Param("operatorId") Long operatorId);
}
