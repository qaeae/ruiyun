package com.wms.platform.modules.fns.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.fns.infrastructure.po.FnReconcileBillPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 对账单 Mapper（表 {@code fn_reconcile_bill}）。 */
@Mapper
public interface FnReconcileBillMapper extends BaseMapper<FnReconcileBillPo> {

    /**
     * 按（承运商 + 账期）查（唯一键 {@code uk_rcl}；重复生成请求靠它幂等）。
     *
     * @param tenantId    租户
     * @param carrierCode 承运商
     * @param period      账期
     * @return 对账单，不存在返回 {@code null}
     */
    default FnReconcileBillPo selectByCarrierPeriod(long tenantId, String carrierCode, String period) {
        return selectOne(Wrappers.<FnReconcileBillPo>lambdaQuery()
                .eq(FnReconcileBillPo::getTenantId, tenantId)
                .eq(FnReconcileBillPo::getCarrierCode, carrierCode)
                .eq(FnReconcileBillPo::getPeriod, period)
                .last("limit 1"));
    }

    /**
     * 按单号查。
     *
     * @param tenantId 租户
     * @param rclNo    对账单号
     * @return 对账单，不存在返回 {@code null}
     */
    default FnReconcileBillPo selectByNo(long tenantId, String rclNo) {
        return selectOne(Wrappers.<FnReconcileBillPo>lambdaQuery()
                .eq(FnReconcileBillPo::getTenantId, tenantId)
                .eq(FnReconcileBillPo::getRclNo, rclNo)
                .last("limit 1"));
    }

    /**
     * 列表（按账期倒序）。
     *
     * @param tenantId    租户
     * @param carrierCode 承运商（可空）
     * @param period      账期（可空）
     * @return 列表
     */
    default List<FnReconcileBillPo> selectListBy(long tenantId, String carrierCode, String period) {
        return selectList(Wrappers.<FnReconcileBillPo>lambdaQuery()
                .eq(carrierCode != null && !carrierCode.isBlank(), FnReconcileBillPo::getCarrierCode, carrierCode)
                .eq(period != null && !period.isBlank(), FnReconcileBillPo::getPeriod, period)
                .orderByDesc(FnReconcileBillPo::getPeriod));
    }

    /**
     * 回写汇总（生成明细后调用；**条件更新**：仅未确认的账单可更新，防止确认后金额被改）。
     *
     * @param tenantId        租户
     * @param rclNo           单号
     * @param status          状态
     * @param totalWaybills   运单数
     * @param platformAmount  平台金额
     * @param carrierAmount   承运商金额
     * @param diffAmount      差异额
     * @param autoCount       自动平账条数
     * @param manualCount     人工条数
     * @param operatorId      操作人
     * @return 影响行数（0 = 账单已确认，不可改）
     */
    @Update("UPDATE fn_reconcile_bill SET status = #{status}, total_waybills = #{totalWaybills}, "
            + "platform_amount = #{platformAmount}, carrier_amount = #{carrierAmount}, diff_amount = #{diffAmount}, "
            + "auto_settled_count = #{autoCount}, manual_count = #{manualCount}, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND rcl_no = #{rclNo} AND status <> '已确认' AND deleted = 0")
    int updateSummary(@Param("tenantId") long tenantId, @Param("rclNo") String rclNo, @Param("status") String status,
                      @Param("totalWaybills") int totalWaybills, @Param("platformAmount") BigDecimal platformAmount,
                      @Param("carrierAmount") BigDecimal carrierAmount, @Param("diffAmount") BigDecimal diffAmount,
                      @Param("autoCount") int autoCount, @Param("manualCount") int manualCount,
                      @Param("operatorId") Long operatorId);

    /**
     * 仅更新人工条数与状态（逐条处理差异后重算；条件更新保证确认后不可再改）。
     *
     * @param tenantId    租户
     * @param rclNo       单号
     * @param status      状态
     * @param manualCount 未处理人工条数
     * @param operatorId  操作人
     * @return 影响行数
     */
    @Update("UPDATE fn_reconcile_bill SET status = #{status}, manual_count = #{manualCount}, "
            + "updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND rcl_no = #{rclNo} AND status <> '已确认' AND deleted = 0")
    int updateManualCount(@Param("tenantId") long tenantId, @Param("rclNo") String rclNo,
                          @Param("status") String status, @Param("manualCount") int manualCount,
                          @Param("operatorId") Long operatorId);

    /**
     * 确认对账（**条件更新：仅「待确认」可确认**，避免并发重复确认导致重复结算）。
     *
     * @param tenantId    租户
     * @param rclNo       单号
     * @param status      目标状态
     * @param confirmedBy 确认人
     * @param confirmedAt 确认时间
     * @return 影响行数（0 = 状态已变）
     */
    @Update("UPDATE fn_reconcile_bill SET status = #{status}, confirmed_by = #{confirmedBy}, "
            + "confirmed_at = #{confirmedAt}, updated_by = #{confirmedBy} "
            + "WHERE tenant_id = #{tenantId} AND rcl_no = #{rclNo} AND status = '待确认' AND deleted = 0")
    int confirm(@Param("tenantId") long tenantId, @Param("rclNo") String rclNo, @Param("status") String status,
                @Param("confirmedBy") Long confirmedBy, @Param("confirmedAt") LocalDateTime confirmedAt);
}
