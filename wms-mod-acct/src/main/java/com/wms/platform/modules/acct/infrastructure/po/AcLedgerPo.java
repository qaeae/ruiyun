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
 * 资金流水（表 {@code ac_ledger}，DDL 见 {@code 01} §3.1）。
 *
 * <p><b>红线（B16）</b>：资金流水**不可删除**（{@code deleted} 恒为 0），只允许追加与红冲
 * （红冲是新增一条 {@code reverse_of_id} 指向原流水的记录，而不是改原记录）。
 *
 * <p>本轮的冻结/解冻流水 {@code changeAmount} 为 0：按 {@code 04} §4.3，冻结**不动 balance**，
 * 只动 {@code freeze_amount}，因此"变动额"体现在 {@code freezeAfter} 这一列上。
 */
@Getter
@Setter
@TableName(value = "ac_ledger", autoResultMap = true)
public class AcLedgerPo {

    /** 账户类型：余额。 */
    public static final String ACCOUNT_BALANCE = "BALANCE";

    /** 业务类型：冻结。 */
    public static final String BIZ_FREEZE = "FREEZE";

    /** 业务类型：解冻（释放）。 */
    public static final String BIZ_UNFREEZE = "UNFREEZE";

    /** 状态：成功。 */
    public static final String STATUS_SUCCESS = "成功";

    /** 关联单据类型：冻结单。 */
    public static final String REF_FREEZE = "FREEZE";

    /** 关联单据类型：出库单。 */
    public static final String REF_DO = "DO";

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 流水号（{@code LG + yyyyMMdd + 36 进制雪花}）。 */
    private String ledgerNo;

    /** 商家 ID。 */
    private Long merchantId;

    /** 账户类型：BALANCE/FREEZE/DEPOSIT。 */
    private String accountType;

    /** 业务类型：RECHARGE/FREEZE/UNFREEZE/DEDUCT/…。 */
    private String bizType;

    /** 变动额（正数增加、负数减少；冻结类为 0）。 */
    private BigDecimal changeAmount;

    /** 变动后账面余额。 */
    private BigDecimal balanceAfter;

    /** 变动后冻结额。 */
    private BigDecimal freezeAfter;

    /** 关联单据类型：DO/SHIPMENT/FEE/FREEZE/SETTLE。 */
    private String refType;

    /** 关联单据 ID。 */
    private Long refId;

    /** 关联业务单号。 */
    private String refNo;

    /** 持牌机构流水号（对账用）。 */
    private String channelTxnNo;

    /** 划拨批次 ID。 */
    private Long settleBatchId;

    /** 红冲指向的原流水 ID。 */
    private Long reverseOfId;

    /** 状态：成功/处理中/失败。 */
    private String status;

    /** 备注。 */
    private String remark;

    /** 资金发生时间。 */
    private LocalDateTime occurredAt;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除：恒为 0（流水不可删）。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂：冻结/解冻流水（B4）。
     *
     * @param id            雪花 ID
     * @param tenantId      租户 ID
     * @param ledgerNo      流水号
     * @param merchantId    商家 ID
     * @param bizType       FREEZE / UNFREEZE
     * @param balanceAfter  变动后账面余额
     * @param freezeAfter   变动后冻结额
     * @param refType       关联单据类型
     * @param refId         关联单据 ID（可空）
     * @param refNo         关联业务单号（冻结单号，便于按单排障）
     * @param remark        备注
     * @param occurredAt    资金发生时间
     * @param operatorId    操作人
     * @return 流水记录
     */
    public static AcLedgerPo freezeChanged(long id, long tenantId, String ledgerNo, long merchantId, String bizType,
                                           BigDecimal balanceAfter, BigDecimal freezeAfter, String refType, Long refId,
                                           String refNo, String remark, LocalDateTime occurredAt, Long operatorId) {
        AcLedgerPo po = new AcLedgerPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setLedgerNo(ledgerNo);
        po.setMerchantId(merchantId);
        po.setAccountType(ACCOUNT_BALANCE);
        po.setBizType(bizType);
        // 冻结/解冻不动 balance，故变动额为 0（04 §4.3）；金额变化体现在 freezeAfter
        po.setChangeAmount(BigDecimal.ZERO);
        po.setBalanceAfter(balanceAfter);
        po.setFreezeAfter(freezeAfter);
        po.setRefType(refType);
        po.setRefId(refId);
        po.setRefNo(refNo);
        po.setStatus(STATUS_SUCCESS);
        po.setRemark(remark);
        po.setOccurredAt(occurredAt);
        po.setCreatedBy(operatorId);
        po.setDeleted(0);
        return po;
    }
}
