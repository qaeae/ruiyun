package com.wms.platform.modules.fns.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.fns.infrastructure.po.FnReconcileItemPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/** 对账明细 Mapper（表 {@code fn_reconcile_item}）。 */
@Mapper
public interface FnReconcileItemMapper extends BaseMapper<FnReconcileItemPo> {

    /**
     * 查对账单的全部明细。
     *
     * @param tenantId 租户
     * @param billId   对账单 ID
     * @return 列表（按运单号排序，结果稳定）
     */
    default List<FnReconcileItemPo> selectByBill(long tenantId, long billId) {
        return selectList(Wrappers.<FnReconcileItemPo>lambdaQuery()
                .eq(FnReconcileItemPo::getTenantId, tenantId)
                .eq(FnReconcileItemPo::getBillId, billId)
                .orderByAsc(FnReconcileItemPo::getWaybillNo));
    }

    /**
     * 查单条明细。
     *
     * @param tenantId 租户
     * @param id       明细 ID
     * @return 明细，不存在返回 {@code null}
     */
    default FnReconcileItemPo selectByIdSafe(long tenantId, long id) {
        return selectOne(Wrappers.<FnReconcileItemPo>lambdaQuery()
                .eq(FnReconcileItemPo::getTenantId, tenantId)
                .eq(FnReconcileItemPo::getId, id)
                .last("limit 1"));
    }

    /**
     * 未处理的人工条目数（挂账/人工 ≤ 待处理）。
     *
     * @param tenantId 租户
     * @param billId   对账单 ID
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM fn_reconcile_item WHERE tenant_id = #{tenantId} AND bill_id = #{billId} "
            + "AND deleted = 0 AND handle_result IN ('挂账待处理','人工处理')")
    long countOpenManual(long tenantId, long billId);

    /**
     * 汇总：平台金额、承运商金额、差异额、自动平账条数。
     *
     * @param tenantId 租户
     * @param billId   对账单 ID
     * @return 汇总行
     */
    @Select("SELECT IFNULL(SUM(platform_fee),0) AS platform_amount, IFNULL(SUM(carrier_fee),0) AS carrier_amount, "
            + "IFNULL(SUM(diff_amount),0) AS diff_amount, "
            + "SUM(CASE WHEN handle_result = '自动平账' THEN 1 ELSE 0 END) AS auto_count, COUNT(*) AS total_count "
            + "FROM fn_reconcile_item WHERE tenant_id = #{tenantId} AND bill_id = #{billId} AND deleted = 0")
    SummaryRow sumByBill(@Param("tenantId") long tenantId, @Param("billId") long billId);

    /**
     * 人工处理差异（条件更新：**只能改未处理的条目**，防止"改到能过为止"）。
     *
     * @param tenantId     租户
     * @param id           明细 ID
     * @param handleResult 处理结果（责任方承担）
     * @param liability    责任方
     * @param remark       说明
     * @param operatorId   操作人
     * @return 影响行数（0 = 已被处理过）
     */
    @Update("UPDATE fn_reconcile_item SET handle_result = #{handleResult}, liability = #{liability}, "
            + "remark = #{remark}, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted = 0 "
            + "AND handle_result IN ('挂账待处理','人工处理')")
    int handleManual(@Param("tenantId") long tenantId, @Param("id") long id,
                     @Param("handleResult") String handleResult, @Param("liability") String liability,
                     @Param("remark") String remark, @Param("operatorId") Long operatorId);

    /**
     * 汇总行。
     *
     * @param platformAmount 平台金额
     * @param carrierAmount  承运商金额
     * @param diffAmount     差异额
     * @param autoCount      自动平账条数
     * @param totalCount     条数
     */
    record SummaryRow(BigDecimal platformAmount, BigDecimal carrierAmount, BigDecimal diffAmount, long autoCount,
                      long totalCount) {
    }
}
