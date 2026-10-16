package com.wms.platform.modules.iv.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wms.platform.modules.iv.domain.StocktakeRule;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 盘点明细（表 {@code iv_stocktake_item}，DDL 见 {@code 01} §3.15）。
 *
 * <p>{@code bookQty} 是**建单时的账面快照**，录入与审批都以它为基准（理由见 {@link StocktakeRule}）。
 */
@Getter
@Setter
@TableName(value = "iv_stocktake_item", autoResultMap = true)
public class IvStocktakeItemPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 盘点单 ID。 */
    private Long stocktakeId;

    /** 商家。 */
    private Long merchantId;

    /** 商品。 */
    private Long skuId;

    /** 库位（非空）。 */
    private Long locationId;

    /** 账面快照。 */
    private BigDecimal bookQty;

    /** 实盘数（未录入为 null）。 */
    private BigDecimal actualQty;

    /** 差异。 */
    private BigDecimal diffQty;

    /** 明细状态：待录入/已录入/已调整。 */
    private String itemStatus;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂（B4）：建单时按账面快照生成明细。
     *
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param stocktakeId 盘点单 ID
     * @param merchantId  商家
     * @param skuId       商品
     * @param locationId  库位
     * @param bookQty     账面快照
     * @return 明细
     */
    public static IvStocktakeItemPo of(long id, long tenantId, long stocktakeId, long merchantId, long skuId,
                                       long locationId, BigDecimal bookQty) {
        IvStocktakeItemPo po = new IvStocktakeItemPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setStocktakeId(stocktakeId);
        po.setMerchantId(merchantId);
        po.setSkuId(skuId);
        po.setLocationId(locationId);
        po.setBookQty(bookQty);
        po.setItemStatus(StocktakeRule.ITEM_PENDING);
        po.setDeleted(0);
        return po;
    }

    /**
     * 录入实盘并计算差异。
     *
     * @param actualQty 实盘数
     * @param diffQty   差异（由领域规则计算）
     */
    public void record(BigDecimal actualQty, BigDecimal diffQty) {
        this.actualQty = actualQty;
        this.diffQty = diffQty;
        this.itemStatus = StocktakeRule.ITEM_RECORDED;
    }
}
