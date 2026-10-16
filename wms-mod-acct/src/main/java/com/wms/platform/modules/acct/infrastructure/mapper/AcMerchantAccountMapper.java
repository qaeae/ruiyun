package com.wms.platform.modules.acct.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.acct.infrastructure.po.AcMerchantAccountPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 商家账户 Mapper（表 {@code ac_merchant_account}）。
 *
 * <p><b>为什么冻结/解冻必须是条件更新（CAS）而不是"先查后改"</b>：
 * 查与改之间的窗口足以让并发的两次建单各自看到"可用足够"，于是两笔都冻成功 →
 * 冻结额超过余额。条件写进 {@code WHERE} 之后，判定交给数据库（行锁 + 谓词），
 * 影响行数 = 0 就是"冻不动"，调用方据此拒绝下单。
 *
 * <p><b>为什么冻结不动 balance</b>：{@code 04} §4.3 定义可用 = {@code balance − freeze_amount}，
 * {@code balance} 是含冻结的账面余额。若把冻结实现成"balance 减少"，日终勾稽
 * （{@code balance + freeze_amount + deposit_amount} 对存管账户）会两边都不平。
 */
@Mapper
public interface AcMerchantAccountMapper extends BaseMapper<AcMerchantAccountPo> {

    /**
     * 按商家查账户（唯一键 {@code uk_acct_merchant}）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @return 账户，不存在返回 {@code null}
     */
    default AcMerchantAccountPo selectByMerchant(long tenantId, long merchantId) {
        return selectOne(Wrappers.<AcMerchantAccountPo>lambdaQuery()
                .eq(AcMerchantAccountPo::getTenantId, tenantId)
                .eq(AcMerchantAccountPo::getMerchantId, merchantId)
                .last("limit 1"));
    }

    /**
     * 冻结（可用余额足够才冻得动）：{@code freeze_amount += amount}，**balance 不变**。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @param amount     冻结额
     * @return 影响行数（0 = 可用余额不足或账户不存在）
     */
    @Update("UPDATE ac_merchant_account SET freeze_amount = freeze_amount + #{amount}, version = version + 1 "
            + "WHERE tenant_id = #{tenantId} AND merchant_id = #{merchantId} AND deleted = 0 "
            + "AND balance - freeze_amount >= #{amount}")
    int freeze(@Param("tenantId") long tenantId, @Param("merchantId") long merchantId,
               @Param("amount") BigDecimal amount);

    /**
     * 解冻/释放：{@code freeze_amount -= amount}（**balance 不变**，差额自动回到可用）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @param amount     释放额
     * @return 影响行数（0 = 冻结额不足，属数据异常，需人工核对）
     */
    @Update("UPDATE ac_merchant_account SET freeze_amount = freeze_amount - #{amount}, version = version + 1 "
            + "WHERE tenant_id = #{tenantId} AND merchant_id = #{merchantId} AND deleted = 0 "
            + "AND freeze_amount >= #{amount}")
    int release(@Param("tenantId") long tenantId, @Param("merchantId") long merchantId,
                @Param("amount") BigDecimal amount);
}
