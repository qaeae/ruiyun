package com.wms.platform.modules.outbound.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出库单明细（表 {@code out_order_item}，DDL 见 {@code 01} §3.2）。
 *
 * <p>{@code qty} 应发 / {@code pickedQty} 实拣：两者的差值就是缺货量（{@code 01} §2.1 第 4 行
 * "实拣 &lt; 订单数量 → 差额释放"）。{@code locationId} 在派发拣货时回填，出库扣减必须带库位
 * （否则 SKU 账与库位账会不平，{@code 01} §4 不变量 2）。
 */
@Getter
@Setter
@TableName(value = "out_order_item", autoResultMap = true)
public class OutOrderItemPo {

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 出库单 ID。 */
    private Long doId;

    /** 行号（同单内唯一，{@code uk_do_line}）。 */
    private Integer lineNo;

    /** 商品 ID。 */
    private Long skuId;

    /** 商品编码（快照，便于对账与人读）。 */
    private String skuCode;

    /** 商品名称（快照）。 */
    private String skuName;

    /** 批次号（可空）。 */
    private String batchNo;

    /** 应发数量。 */
    private BigDecimal qty;

    /** 实拣数量（派发后由拣货完成/缺货上报回填）。 */
    private BigDecimal pickedQty;

    /** 拣货库位 ID（派发拣货时回填；出库扣减必填）。 */
    private Long locationId;

    /** 单位。 */
    private String unit;

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
     * 静态工厂：新建明细（B4）。
     *
     * @param id        雪花 ID
     * @param tenantId  租户 ID
     * @param doId      出库单 ID
     * @param lineNo    行号
     * @param skuId     商品 ID
     * @param skuCode   商品编码
     * @param skuName   商品名称（可空）
     * @param qty       应发数量
     * @param unit      单位（可空）
     * @param operatorId 操作人（可空）
     * @return 明细
     */
    public static OutOrderItemPo of(long id, long tenantId, long doId, int lineNo, long skuId, String skuCode,
                                    String skuName, BigDecimal qty, String unit, Long operatorId) {
        OutOrderItemPo po = new OutOrderItemPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setDoId(doId);
        po.setLineNo(lineNo);
        po.setSkuId(skuId);
        po.setSkuCode(skuCode);
        po.setSkuName(skuName);
        po.setQty(qty);
        po.setPickedQty(BigDecimal.ZERO);
        po.setUnit(unit);
        po.setCreatedBy(operatorId);
        po.setDeleted(0);
        return po;
    }

    /**
     * 缺货数量（应发 − 实拣，负数按 0 处理）。
     *
     * @return 缺货数量
     */
    public BigDecimal shortageQty() {
        BigDecimal ordered = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal picked = pickedQty == null ? BigDecimal.ZERO : pickedQty;
        BigDecimal diff = ordered.subtract(picked);
        return diff.signum() <= 0 ? BigDecimal.ZERO : diff;
    }
}
