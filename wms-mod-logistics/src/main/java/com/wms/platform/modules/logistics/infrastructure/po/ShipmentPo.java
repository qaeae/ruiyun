package com.wms.platform.modules.logistics.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 装运单 / 运单（表 {@code lg_shipment}，DDL 见 {@code 01} §3.3）。
 *
 * <p><b>本轮只用到"待取号"这一小段</b>：出库建单联动生成装运单（{@code 03} API-LOG-01），
 * 取号、面单打印（{@link com.wms.platform.modules.logistics.app.LabelPrintService} 已实现打印登记）、
 * 轨迹回调、作废、交接承运商属 M8 轮次。
 *
 * <p><b>金额与重量口径</b>：{@code estimated_fee} 是下单时按价目表算出的预估价（与
 * {@code ac_fee_freeze} 的冻结基数同源）；{@code actual_fee} 由承运商回传后回填。
 */
@Getter
@Setter
@TableName(value = "lg_shipment", autoResultMap = true)
public class ShipmentPo {

    /** 待取号（出库建单联动落到的状态）。 */
    public static final String STATUS_PENDING_WAYBILL = "待取号";

    /** 已取号。 */
    public static final String STATUS_WAYBILL_GOT = "已取号";

    /** 已作废（出库取消/拦截联动作废落到的状态）。 */
    public static final String STATUS_VOIDED = "已作废";

    /**
     * 不可作废的状态集合（已进入承运流程：揽收后作废只会让面单与实物对不上，
     * {@code 03 30003 面单不可作废（已揽收）}）。
     */
    public static final java.util.Set<String> STATUS_NOT_VOIDABLE = java.util.Set.of(
            "已揽收", "运输中", "已签收", "已拒收", "退回中", "已退回入库");

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 装运单号（{@code SHP + yyyyMMdd + 36 进制雪花}）。 */
    private String shpNo;

    /** 关联出库单 ID。 */
    private Long doId;

    /** 关联出库单号。 */
    private String doNo;

    /** 仓库 ID。 */
    private Long warehouseId;

    /** 商家 ID。 */
    private Long merchantId;

    /** 承运商编码。 */
    private String carrierCode;

    /** 承运商名称。 */
    private String carrierName;

    /** 承运单号（取号后回填）。 */
    private String waybillNo;

    /** 面单地址。 */
    private String labelUrl;

    /** 面单是否已作废。 */
    private Boolean labelVoid;

    /** 面单打印次数（补打监控）。 */
    private Integer labelPrintCount;

    /** 状态（见本类常量）。 */
    private String status;

    /** 价目表预估价。 */
    private BigDecimal estimatedFee;

    /** 承运商回传实费。 */
    private BigDecimal actualFee;

    /** 实际重量（kg，复核称重回填）。 */
    private BigDecimal actualWeight;

    /** 费用回传时间。 */
    private LocalDateTime feeBackAt;

    /** 揽收时间（兜底扣减起算点）。 */
    private LocalDateTime pickedUpAt;

    /** 签收时间。 */
    private LocalDateTime signedAt;

    /** 交接时间。 */
    private LocalDateTime handoverAt;

    /** 作废原因。 */
    private String voidReason;

    /** 作废时间。 */
    private LocalDateTime voidAt;

    /** 下单时命中的价目表版本。 */
    private Long priceVersionId;

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
     * 静态工厂：出库建单联动生成"待取号"装运单（B4）。
     *
     * @param id            雪花 ID
     * @param tenantId      租户 ID
     * @param shpNo         装运单号
     * @param doId          出库单 ID
     * @param doNo          出库单号
     * @param warehouseId   仓库 ID
     * @param merchantId    商家 ID
     * @param carrierCode   承运商编码
     * @param carrierName   承运商名称（可空）
     * @param estimatedFee  预估价（可空）
     * @param operatorId    操作人（可空，系统触发时为 null）
     * @return 装运单
     */
    public static ShipmentPo awaitingWaybill(long id, long tenantId, String shpNo, long doId, String doNo,
                                             long warehouseId, long merchantId, String carrierCode,
                                             String carrierName, BigDecimal estimatedFee, Long operatorId) {
        ShipmentPo po = new ShipmentPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setShpNo(shpNo);
        po.setDoId(doId);
        po.setDoNo(doNo);
        po.setWarehouseId(warehouseId);
        po.setMerchantId(merchantId);
        po.setCarrierCode(carrierCode);
        po.setCarrierName(carrierName);
        po.setLabelVoid(false);
        po.setLabelPrintCount(0);
        po.setStatus(STATUS_PENDING_WAYBILL);
        po.setEstimatedFee(estimatedFee);
        po.setVersion(0);
        po.setCreatedBy(operatorId);
        po.setDeleted(0);
        return po;
    }

    /**
     * 是否已死单（作废）——面单是否可继续使用。
     *
     * @return 已作废返回 {@code true}
     */
    public boolean isLabelVoided() {
        return Boolean.TRUE.equals(labelVoid);
    }
}
