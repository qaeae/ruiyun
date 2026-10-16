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
 * 费用冻结单（表 {@code ac_fee_freeze}，DDL 见 {@code 01} §3.1 / {@code 04} §4.3）。
 *
 * <p><b>幂等防线是唯一键 {@code uk_freeze_biz(tenant_id, biz_type, biz_no)}</b>：同一个业务对象
 * （如一张 DO 的物流费）只允许冻结一次。这也是出库重复建单、PDA 重放、任务补跑时的最终兜底——
 * 靠"先查再冻"的判断在并发下不可靠，缺了唯一键就会出现重复冻结（钱被冻两遍而无人报错）。
 */
@Getter
@Setter
@TableName(value = "ac_fee_freeze", autoResultMap = true)
public class AcFeeFreezePo {

    /** 待冻结。 */
    public static final String STATUS_PENDING = "待冻结";

    /** 已冻结（本轮出库建单落到的状态）。 */
    public static final String STATUS_FROZEN = "已冻结";

    /** 对账中。 */
    public static final String STATUS_RECONCILING = "对账中";

    /** 已确认（对账确认，可扣减）。 */
    public static final String STATUS_CONFIRMED = "已确认";

    /** 已扣减。 */
    public static final String STATUS_DEDUCTED = "已扣减";

    /** 已释放（取消/作废/兜底差额回退）。 */
    public static final String STATUS_RELEASED = "已释放";

    /** 冻结失败。 */
    public static final String STATUS_FAILED = "冻结失败";

    /** 已兜底扣减。 */
    public static final String STATUS_FALLBACK_DEDUCTED = "已兜底扣减";

    /** 已垫付。 */
    public static final String STATUS_ADVANCED = "已垫付";

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 冻结单号（{@code FRZ + yyyyMMdd + 36 进制雪花}）。 */
    private String freezeNo;

    /** 商家 ID。 */
    private Long merchantId;

    /** 归属仓库（可空）。 */
    private Long warehouseId;

    /** 费用类型：LOGISTICS/OPERATION/STORAGE。 */
    private String bizType;

    /** 业务单号（DO 号 / 运单号）。 */
    private String bizNo;

    /** 关联出库单 ID。 */
    private Long doId;

    /** 关联装运单 ID。 */
    private Long shipmentId;

    /** 冻结额 = 预估价 × (1 + 缓冲系数)。 */
    private BigDecimal estimatedAmount;

    /** 缓冲系数（默认 0.1000）。 */
    private BigDecimal bufferRate;

    /** 已冻结金额。 */
    private BigDecimal frozenAmount;

    /** 已扣减金额。 */
    private BigDecimal deductedAmount;

    /** 已释放金额。 */
    private BigDecimal releasedAmount;

    /** 垫付金额。 */
    private BigDecimal advanceAmount;

    /** 状态（见本类常量）。 */
    private String status;

    /** 兜底扣减少时点 = 揽收后 7 天。 */
    private LocalDateTime fallbackDeadline;

    /** 冻结时间。 */
    private LocalDateTime frozenAt;

    /** 对账确认时间。 */
    private LocalDateTime confirmedAt;

    /** 扣减时间。 */
    private LocalDateTime deductedAt;

    /** 释放时间。 */
    private LocalDateTime releasedAt;

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
     * 静态工厂：新建一张"已冻结"的冻结单（B4：禁止 {@code new + 连续 setter}）。
     *
     * @param id              雪花 ID
     * @param tenantId        租户 ID
     * @param freezeNo        冻结单号
     * @param merchantId      商家 ID
     * @param warehouseId     仓库 ID（可空）
     * @param bizType         费用类型
     * @param bizNo           业务单号
     * @param doId            出库单 ID（可空）
     * @param shipmentId      装运单 ID（可空）
     * @param estimatedAmount 预估价
     * @param bufferRate      缓冲系数
     * @param frozenAmount    冻结额
     * @param frozenAt        冻结时间
     * @param operatorId      操作人
     * @return 冻结单
     */
    public static AcFeeFreezePo frozen(long id, long tenantId, String freezeNo, long merchantId, Long warehouseId,
                                       String bizType, String bizNo, Long doId, Long shipmentId,
                                       BigDecimal estimatedAmount, BigDecimal bufferRate, BigDecimal frozenAmount,
                                       LocalDateTime frozenAt, Long operatorId) {
        AcFeeFreezePo po = new AcFeeFreezePo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setFreezeNo(freezeNo);
        po.setMerchantId(merchantId);
        po.setWarehouseId(warehouseId);
        po.setBizType(bizType);
        po.setBizNo(bizNo);
        po.setDoId(doId);
        po.setShipmentId(shipmentId);
        po.setEstimatedAmount(estimatedAmount);
        po.setBufferRate(bufferRate);
        po.setFrozenAmount(frozenAmount);
        po.setDeductedAmount(BigDecimal.ZERO);
        po.setReleasedAmount(BigDecimal.ZERO);
        po.setAdvanceAmount(BigDecimal.ZERO);
        po.setStatus(STATUS_FROZEN);
        po.setFrozenAt(frozenAt);
        po.setVersion(0);
        po.setCreatedBy(operatorId);
        po.setDeleted(0);
        return po;
    }

    /**
     * 是否处于已冻结（可释放）状态。
     *
     * @return 已冻结返回 {@code true}
     */
    public boolean isFrozen() {
        return STATUS_FROZEN.equals(status);
    }
}
