package com.wms.platform.modules.acct.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.acct.infrastructure.po.AcFeeFreezePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用冻结单 Mapper（表 {@code ac_fee_freeze}）。
 *
 * <p>释放接口用条件更新（{@code status = 已冻结}）：并发下两个取消请求只有一个能改成"已释放"，
 * 另一个影响行数 = 0 → 上层按"已释放过"处理，**不会把同一笔冻结额释放两次**。
 */
@Mapper
public interface AcFeeFreezeMapper extends BaseMapper<AcFeeFreezePo> {

    /**
     * 按业务键查冻结单（唯一键 {@code uk_freeze_biz}）。
     *
     * @param tenantId 租户
     * @param bizType  费用类型
     * @param bizNo    业务单号
     * @return 冻结单，不存在返回 {@code null}
     */
    default AcFeeFreezePo selectByBiz(long tenantId, String bizType, String bizNo) {
        return selectOne(Wrappers.<AcFeeFreezePo>lambdaQuery()
                .eq(AcFeeFreezePo::getTenantId, tenantId)
                .eq(AcFeeFreezePo::getBizType, bizType)
                .eq(AcFeeFreezePo::getBizNo, bizNo)
                .last("limit 1"));
    }

    /**
     * 按冻结单号查。
     *
     * @param tenantId 租户
     * @param freezeNo 冻结单号
     * @return 冻结单，不存在返回 {@code null}
     */
    default AcFeeFreezePo selectByFreezeNo(long tenantId, String freezeNo) {
        return selectOne(Wrappers.<AcFeeFreezePo>lambdaQuery()
                .eq(AcFeeFreezePo::getTenantId, tenantId)
                .eq(AcFeeFreezePo::getFreezeNo, freezeNo)
                .last("limit 1"));
    }

    /**
     * 回填出库单 / 装运单关联（建单联动用；只补空前值，避免覆盖已写入的关联）。
     *
     * @param tenantId   租户
     * @param freezeNo   冻结单号
     * @param doId       出库单 ID（可空）
     * @param shipmentId 装运单 ID（可空）
     * @return 影响行数
     */
    @Update("UPDATE ac_fee_freeze SET do_id = COALESCE(#{doId}, do_id), "
            + "shipment_id = COALESCE(#{shipmentId}, shipment_id) "
            + "WHERE tenant_id = #{tenantId} AND freeze_no = #{freezeNo}")
    int fillRefs(@Param("tenantId") long tenantId, @Param("freezeNo") String freezeNo,
                 @Param("doId") Long doId, @Param("shipmentId") Long shipmentId);

    /**
     * 置为已释放（仅"已冻结"可释放；幂等：重复调用影响行数 = 0）。
     *
     * @param tenantId 租户
     * @param freezeNo 冻结单号
     * @param amount   释放额（= 冻结额）
     * @param at       释放时间
     * @param operator 操作人
     * @return 影响行数（1 = 本次真的释放了）
     */
    @Update("UPDATE ac_fee_freeze SET status = '已释放', released_amount = #{amount}, released_at = #{at}, "
            + "updated_by = #{operator} WHERE tenant_id = #{tenantId} AND freeze_no = #{freezeNo} "
            + "AND status = '已冻结'")
    int markReleased(@Param("tenantId") long tenantId, @Param("freezeNo") String freezeNo,
                     @Param("amount") BigDecimal amount, @Param("at") LocalDateTime at,
                     @Param("operator") Long operator);
}
