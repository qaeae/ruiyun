package com.wms.platform.modules.iv.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存-库位级明细账（表 {@code iv_location}，{@code 01} §3.4）。
 *
 * <p>两级账的分工（这是库存域最容易理解错的地方）：
 * <ul>
 *   <li>{@code iv_sku}：**决策用**——下单预占、可用量判断；</li>
 *   <li>{@code iv_location}：**作业用**——拣货去哪、上架去哪、库位还剩多少；</li>
 * </ul>
 * 因此"预占"只动 SKU 级（还没决定从哪个库位拿），"分配/拣货"才动库位级。
 * 两级账的实物合计必须相等（{@code 01} §4 不变量 2）。
 */
@Getter
@Setter
@TableName(value = "iv_location", autoResultMap = true)
public class IvLocationPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 仓库。 */
    private Long warehouseId;

    /** 库位 ID。 */
    private Long locationId;

    /** 商家。 */
    private Long merchantId;

    /** 商品 ID。 */
    private Long skuId;

    /** 批次号（一期未启用批次，写空串——唯一键需非空）。 */
    private String batchNo;

    /** 实物库存。 */
    private BigDecimal physicalQty;

    /** 已分配待拣。 */
    private BigDecimal allocatedQty;

    /** 效期（一期仅登记）。 */
    private LocalDate expireDate;

    /** 版本。 */
    private Integer version;

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
     * 静态工厂：首次在库位建账（B4）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param locationId  库位
     * @param merchantId  商家
     * @param skuId       商品
     * @return 零库存库位账
     */
    public static IvLocationPo of(long id, long tenantId, long warehouseId, long locationId, long merchantId,
                                  long skuId) {
        IvLocationPo po = new IvLocationPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setWarehouseId(warehouseId);
        po.setLocationId(locationId);
        po.setMerchantId(merchantId);
        po.setSkuId(skuId);
        po.setBatchNo("");
        po.setPhysicalQty(BigDecimal.ZERO);
        po.setAllocatedQty(BigDecimal.ZERO);
        po.setVersion(0);
        po.setDeleted(0);
        return po;
    }
}
