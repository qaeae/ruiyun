package com.wms.platform.modules.iv.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wms.platform.modules.iv.domain.StockState;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存-SKU 级三态聚合账（表 {@code iv_sku}，{@code 01} §3.4）。
 *
 * <p>{@code version} 是乐观锁字段：**目前所有变更都走条件更新（CAS），version 只做审计线索**。
 * 之所以不改成"先查 version 再更新"，是因为并发下 CAS 的条件表达式本身就是原子的
 * （{@code WHERE available_qty >= ?}），比乐观锁重试更简单可靠（PoC-4 已实测）。
 */
@Getter
@Setter
@TableName(value = "iv_sku", autoResultMap = true)
public class IvSkuPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 仓库。 */
    private Long warehouseId;

    /** 商家。 */
    private Long merchantId;

    /** 商品 ID。 */
    private Long skuId;

    /** 实物库存。 */
    private BigDecimal physicalQty;

    /** 可用库存。 */
    private BigDecimal availableQty;

    /** 预占库存。 */
    private BigDecimal reservedQty;

    /** 版本（乐观锁审计字段，见类注释）。 */
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
     * 静态工厂：首次建账（B4）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param warehouseId 仓库
     * @param merchantId  商家
     * @param skuId       商品
     * @return 零库存账
     */
    public static IvSkuPo of(long id, long tenantId, long warehouseId, long merchantId, long skuId) {
        IvSkuPo po = new IvSkuPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setWarehouseId(warehouseId);
        po.setMerchantId(merchantId);
        po.setSkuId(skuId);
        po.setPhysicalQty(BigDecimal.ZERO);
        po.setAvailableQty(BigDecimal.ZERO);
        po.setReservedQty(BigDecimal.ZERO);
        po.setVersion(0);
        po.setDeleted(0);
        return po;
    }

    /**
     * 转三态值对象（构造即校验，读出来的脏数据会立刻暴露）。
     *
     * @return 三态
     */
    public StockState toState() {
        return new StockState(physicalQty, availableQty, reservedQty);
    }
}
