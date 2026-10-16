package com.wms.platform.modules.acct.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家账户（表 {@code ac_merchant_account}，DDL 见 {@code 01} §3.1 / {@code 04} §4.1）。
 *
 * <p><b>字段口径（最容易搞错的一处，已在类注释与 {@code FeeFreezeRule} 双重写明）</b>：
 * {@code balance} 是**含冻结的账面余额**，可用余额 = {@code balance − freeze_amount}。
 * 冻结只增加 {@code freeze_amount}，不减 {@code balance}（{@code 04} §4.3）。
 *
 * <p>规范落地（B3/B4/B6）：后缀 {@code Po} + {@code @TableName(autoResultMap = true)} + 逻辑删除显式
 * {@code @TableLogic} + 与本表强绑定的取值定义在类内 + 静态工厂创建。
 */
@Getter
@Setter
@TableName(value = "ac_merchant_account", autoResultMap = true)
public class AcMerchantAccountPo {

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 商家 ID（一商家一账户，唯一键 {@code uk_acct_merchant}）。 */
    private Long merchantId;

    /** 账面余额（**含冻结**）。 */
    private BigDecimal balance;

    /** 冻结金额。 */
    private BigDecimal freezeAmount;

    /** 押金（存管专户）。 */
    private BigDecimal depositAmount;

    /** 授信额度，一期恒为 0（不做透支）。 */
    private BigDecimal creditLimit;

    /** 持牌机构虚拟子账户号（充值/划拨后回填）。 */
    private String channelAcctNo;

    /** 乐观锁版本。 */
    private Integer version;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除：0 未删、1 已删。 */
    @TableLogic
    private Integer deleted;

    /**
     * 可用余额（账面余额 − 冻结额）。
     *
     * @return 可用余额（字段缺失时按 0 处理，避免 NPE 让"余额不足"变成 500）
     */
    public BigDecimal availableAmount() {
        BigDecimal total = balance == null ? BigDecimal.ZERO : balance;
        BigDecimal frozen = freezeAmount == null ? BigDecimal.ZERO : freezeAmount;
        return total.subtract(frozen);
    }
}
