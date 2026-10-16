package com.wms.platform.modules.fns.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.fns.infrastructure.po.FnAdvancePaymentPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 垫付台账 Mapper（表 {@code fn_advance_payment}）。 */
@Mapper
public interface FnAdvancePaymentMapper extends BaseMapper<FnAdvancePaymentPo> {

    /**
     * 按运单查（幂等键）。
     *
     * @param tenantId  租户
     * @param waybillNo 运单号
     * @return 记录，不存在返回 {@code null}
     */
    default FnAdvancePaymentPo selectByWaybill(long tenantId, String waybillNo) {
        return selectOne(Wrappers.<FnAdvancePaymentPo>lambdaQuery()
                .eq(FnAdvancePaymentPo::getTenantId, tenantId)
                .eq(FnAdvancePaymentPo::getWaybillNo, waybillNo)
                .last("limit 1"));
    }

    /**
     * 商家列表（按发生时间倒序）。
     *
     * @param tenantId   租户
     * @param merchantId 商家（可空）
     * @return 列表
     */
    default List<FnAdvancePaymentPo> selectListByMerchant(long tenantId, Long merchantId) {
        return selectList(Wrappers.<FnAdvancePaymentPo>lambdaQuery()
                .eq(FnAdvancePaymentPo::getTenantId, tenantId)
                .eq(merchantId != null, FnAdvancePaymentPo::getMerchantId, merchantId)
                .orderByDesc(FnAdvancePaymentPo::getOccurredAt));
    }

    /**
     * 累计垫付金额（限额判定用）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @return 累计金额（无记录返回 0）
     */
    @Select("SELECT IFNULL(SUM(amount), 0) FROM fn_advance_payment WHERE tenant_id = #{tenantId} "
            + "AND merchant_id = #{merchantId} AND deleted = 0")
    BigDecimal sumAdvance(long tenantId, long merchantId);

    /**
     * 距今最近一次垫付时间（连续垫付计数用）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @return 时间，无记录返回 {@code null}
     */
    @Select("SELECT MAX(occurred_at) FROM fn_advance_payment WHERE tenant_id = #{tenantId} "
            + "AND merchant_id = #{merchantId} AND deleted = 0")
    LocalDateTime lastOccurredAt(long tenantId, long merchantId);

    /**
     * 统计某时间点之后的垫付笔数（"连续垫付"口径：近期连续发生即视为高频）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @param since      起始时间
     * @return 笔数
     */
    @Select("SELECT COUNT(*) FROM fn_advance_payment WHERE tenant_id = #{tenantId} "
            + "AND merchant_id = #{merchantId} AND occurred_at >= #{since} AND deleted = 0")
    long countSince(@Param("tenantId") long tenantId, @Param("merchantId") long merchantId,
                    @Param("since") LocalDateTime since);

    /**
     * 核销（条件更新：仅「待核销」可核销）。
     *
     * @param tenantId  租户
     * @param waybillNo 运单号
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Update("UPDATE fn_advance_payment SET status = '已核销', cleared_at = NOW(3), updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND waybill_no = #{waybillNo} AND status = '待核销' AND deleted = 0")
    int clear(@Param("tenantId") long tenantId, @Param("waybillNo") String waybillNo,
              @Param("operatorId") Long operatorId);
}
