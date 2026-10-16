package com.wms.platform.modules.md.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SKU 箱规单位换算（表 {@code md_sku_unit_conv}，DDL 见 {@code 01} §3.13）。
 *
 * <p>口径：{@code 1 unit_from = factor unit_to}。例如
 * {@code unit_from=箱, unit_to=件, factor=24} 表示 1 箱 = 24 件。
 */
@Getter
@Setter
@TableName(value = "md_sku_unit_conv", autoResultMap = true)
public class SkuUnitConvPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 商品 ID。 */
    private Long skuId;

    /** 源单位。 */
    private String unitFrom;

    /** 目标单位。 */
    private String unitTo;

    /** 系数。 */
    private BigDecimal factor;

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
     * 静态工厂（B4）。
     *
     * @param id       雪花 ID
     * @param tenantId 租户
     * @param skuId    商品 ID
     * @param unitFrom 源单位
     * @param unitTo   目标单位
     * @param factor   系数
     * @return 换算配置
     */
    public static SkuUnitConvPo of(long id, long tenantId, long skuId, String unitFrom, String unitTo,
                                   BigDecimal factor) {
        SkuUnitConvPo po = new SkuUnitConvPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setSkuId(skuId);
        po.setUnitFrom(unitFrom);
        po.setUnitTo(unitTo);
        po.setFactor(factor);
        po.setDeleted(0);
        return po;
    }
}
