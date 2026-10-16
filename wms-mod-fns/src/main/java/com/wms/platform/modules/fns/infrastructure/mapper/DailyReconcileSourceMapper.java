package com.wms.platform.modules.fns.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 日终勾稽取数（**跨域只读缝隙，临时**）。
 *
 * <p><b>为什么要跨域读</b>：日终勾稽天生就是"把多个域的账放在一起对"，而 `ac_*`（资金域）
 * 尚未落地（K2 阻塞）。这里全是**只读汇总查询**，不含任何业务判断——
 * 判断都在 {@code DailyReconcileRule} 与各层实现里。
 *
 * <p><b>回收计划</b>：资金域 app 层落地后改为调用其查询服务，删除本类（已登记映射表 §八）。
 *
 * <p><b>只读约束</b>：本类只允许 SELECT。
 */
@Mapper
public interface DailyReconcileSourceMapper {

    /**
     * 资金层平台侧：逐商家（余额 + 冻结 + 押金）。
     *
     * @param tenantId 租户
     * @return 商家汇总行
     */
    @Select("SELECT merchant_id, IFNULL(balance,0) + IFNULL(freeze_amount,0) + IFNULL(deposit_amount,0) AS total "
            + "FROM ac_merchant_account WHERE tenant_id = #{tenantId} AND deleted = 0")
    List<MerchantAmountRow> selectMerchantTotals(@Param("tenantId") long tenantId);

    /**
     * 业务层平台侧：当日费用单合计（已确认/已扣减）。
     *
     * @param tenantId 租户
     * @param bizDate  业务日期
     * @return 合计（无记录返回 0）
     */
    @Select("SELECT IFNULL(SUM(amount),0) FROM fn_fee_order WHERE tenant_id = #{tenantId} AND deleted = 0 "
            + "AND status IN ('已确认','已扣减') AND DATE(billed_at) = #{bizDate}")
    BigDecimal selectFeeOrderTotal(@Param("tenantId") long tenantId, @Param("bizDate") LocalDate bizDate);

    /**
     * 业务层对方侧：当日资金流水扣减合计。
     *
     * @param tenantId 租户
     * @param bizDate  业务日期
     * @return 合计（无记录返回 0）
     */
    @Select("SELECT IFNULL(SUM(ABS(change_amount)),0) FROM ac_ledger WHERE tenant_id = #{tenantId} AND deleted = 0 "
            + "AND biz_type = 'DEDUCT' AND DATE(occurred_at) = #{bizDate}")
    BigDecimal selectLedgerDeductTotal(@Param("tenantId") long tenantId, @Param("bizDate") LocalDate bizDate);

    /**
     * 三方层平台侧：当日物流运费费用单（逐运单）。
     *
     * @param tenantId 租户
     * @param bizDate  业务日期
     * @return 运单费用行
     */
    @Select("SELECT biz_no AS waybill_no, SUM(amount) AS amount FROM fn_fee_order WHERE tenant_id = #{tenantId} "
            + "AND deleted = 0 AND fee_type = 'LOGISTICS' AND DATE(billed_at) = #{bizDate} GROUP BY biz_no")
    List<WaybillAmountRow> selectWaybillFees(@Param("tenantId") long tenantId, @Param("bizDate") LocalDate bizDate);

    /**
     * 商家汇总行。
     *
     * @param merchantId 商家
     * @param total      平台侧总额
     */
    record MerchantAmountRow(long merchantId, BigDecimal total) {
    }

    /**
     * 运单费用行。
     *
     * @param waybillNo 运单号
     * @param amount    金额
     */
    record WaybillAmountRow(String waybillNo, BigDecimal amount) {
    }
}
