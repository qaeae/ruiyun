package com.wms.platform.modules.iv.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wms.platform.common.constant.TraceConst;
import com.wms.platform.modules.iv.domain.MovementType;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 库存流水（表 {@code iv_movement}，{@code 01} §3.5）。
 *
 * <p>三条铁律（{@code 01} §2 公共约定 + §5 幂等防线）：
 * <ol>
 *   <li><b>不可改不可删</b>：Mapper 只暴露 insert（{@code deleted} 恒为 0）；</li>
 *   <li><b>{@code idem_key} 唯一</b>：{@code uk_movement_idem(tenant_id, idem_key)} 是
 *       "重复提交不重复扣减"的最后防线，比幂等表更可靠（幂等表有 TTL）；</li>
 *   <li><b>必须与库存变更同事务</b>：否则会出现"流水写了、库存没扣"的账实不符。</li>
 * </ol>
 */
@Getter
@Setter
@TableName(value = "iv_movement", autoResultMap = true)
public class IvMovementPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 流水号。 */
    private String movementNo;

    /** 仓库。 */
    private Long warehouseId;

    /** 商家。 */
    private Long merchantId;

    /** 商品 ID。 */
    private Long skuId;

    /** 库位 ID（SKU 级动作可为空）。 */
    private Long locationId;

    /** 批次号（一期空串）。 */
    private String batchNo;

    /** 流水类型。 */
    private String movementType;

    /** 数量（带符号：入库正、出库负）。 */
    private BigDecimal qty;

    /** 变动后实物。 */
    private BigDecimal physicalAfter;

    /** 变动后可用。 */
    private BigDecimal availableAfter;

    /** 变动后预占。 */
    private BigDecimal reservedAfter;

    /** 业务类型：ASN/DO/STOCKTAKE/RETURN/MOVE。 */
    private String bizType;

    /** 业务单据 ID。 */
    private Long bizId;

    /** 业务单号。 */
    private String bizNo;

    /** 幂等键。 */
    private String idemKey;

    /** 操作人。 */
    private Long operatorId;

    /** 设备编号。 */
    private String deviceCode;

    /** 链路 ID。 */
    private String traceId;

    /** 发生时间。 */
    private LocalDateTime occurredAt;

    /** 逻辑删除（恒为 0）。 */
    private Integer deleted;

    /**
     * 静态工厂（B4）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @param locationId  库位（可空）
     * @param type        流水类型
     * @param qty         非负数量（由类型决定符号）
     * @param stateAfter  变动后三态（可空：不改三态的动作传 {@code null}）
     * @param bizType     业务类型
     * @param bizId       业务单据 ID
     * @param bizNo       业务单号
     * @param idemKey     幂等键
     * @param operatorId  操作人
     * @param deviceCode  设备编号
     * @return 流水
     */
    public static IvMovementPo of(long id, long tenantId, long warehouseId, long merchantId, long skuId,
                                  Long locationId, MovementType type, BigDecimal qty, StockStateView stateAfter,
                                  String bizType, long bizId, String bizNo, String idemKey, Long operatorId,
                                  String deviceCode) {
        IvMovementPo po = new IvMovementPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setMovementNo(movementNoOf(id));
        po.setWarehouseId(warehouseId);
        po.setMerchantId(merchantId);
        po.setSkuId(skuId);
        po.setLocationId(locationId);
        // 一期未启用批次：uk 需非空，统一空串
        po.setBatchNo("");
        po.setMovementType(type.name());
        po.setQty(type.signedQty(qty));
        if (stateAfter != null) {
            po.setPhysicalAfter(stateAfter.physicalQty());
            po.setAvailableAfter(stateAfter.availableQty());
            po.setReservedAfter(stateAfter.reservedQty());
        }
        po.setBizType(bizType);
        po.setBizId(bizId);
        po.setBizNo(bizNo);
        po.setIdemKey(idemKey);
        po.setOperatorId(operatorId);
        po.setDeviceCode(deviceCode);
        po.setTraceId(MDC.get(TraceConst.MDC_TRACE_ID));
        po.setOccurredAt(LocalDateTime.now());
        po.setDeleted(0);
        return po;
    }

    /**
     * 流水号（{@code MV+yyyyMMdd+8 位尾号}，可读且不重复）。
     *
     * @param id 雪花 ID
     * @return 流水号
     */
    public static String movementNoOf(long id) {
        String text = String.valueOf(id);
        return "MV" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + text.substring(text.length() - 8);
    }

    /**
     * 变动后三态视图（避免 po 包直接依赖 domain 类型过深，仅用于写入 *_after）。
     *
     * @param physicalQty  实物
     * @param availableQty 可用
     * @param reservedQty  预占
     */
    public record StockStateView(BigDecimal physicalQty, BigDecimal availableQty, BigDecimal reservedQty) {

        /**
         * 由三态构造。
         *
         * @param state 三态
         * @return 视图
         */
        public static StockStateView of(com.wms.platform.modules.iv.domain.StockState state) {
            return state == null ? null
                    : new StockStateView(state.physicalQty(), state.availableQty(), state.reservedQty());
        }
    }
}
