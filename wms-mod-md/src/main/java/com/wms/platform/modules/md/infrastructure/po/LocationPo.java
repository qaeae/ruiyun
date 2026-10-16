package com.wms.platform.modules.md.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 库位主数据（表 {@code md_location}，DDL 见 {@code 01} §3.12）。
 *
 * <p>它的存在解决一个真实问题：**"扫了不存在的库位"必须能被判定为错扫**。
 * 在它之前（PoC-3 阶段）只能拿"该库位是否已建库存账"来近似，会把"新库位第一次上架"
 * 误判成错扫；有了主数据后判定依据才是正确的（`01` §6 冲突分级第 4 行）。
 */
@Getter
@Setter
@TableName(value = "md_location", autoResultMap = true)
public class LocationPo {

    /** 启用。 */
    public static final String STATUS_ENABLED = "启用";

    /** 停用。 */
    public static final String STATUS_DISABLED = "停用";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 仓库 ID。 */
    private Long warehouseId;

    /** 库区 ID。 */
    private Long zoneId;

    /** 库位编码（现场贴码）。 */
    private String locationCode;

    /** ABC 分类。 */
    private String abcClass;

    /** 是否可拣（0/1）。 */
    private Integer isPickable;

    /** 折算托盘位数（超占用）。 */
    private Integer quotaUnit;

    /** 状态。 */
    private String status;

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
     * 是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return STATUS_ENABLED.equals(status);
    }

    /**
     * 是否可拣。
     *
     * @return 可拣返回 {@code true}
     */
    public boolean isPickable() {
        return isPickable != null && isPickable == 1;
    }
    /**
     * 静态工厂（B4：禁止 new + setXxx）。
     *
     * @param id           雪花 ID
     * @param tenantId     租户
     * @param warehouseId  仓库
     * @param zoneId       库区
     * @param locationCode 库位编码
     * @param abcClass     ABC 分类
     * @param pickable     是否可拣
     * @param quotaUnit    折算托盘位
     * @return 库位
     */
    public static LocationPo of(long id, long tenantId, long warehouseId, long zoneId, String locationCode,
                               String abcClass, boolean pickable, int quotaUnit) {
        LocationPo po = new LocationPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setWarehouseId(warehouseId);
        po.setZoneId(zoneId);
        po.setLocationCode(locationCode);
        po.setAbcClass(abcClass);
        po.setIsPickable(pickable ? 1 : 0);
        po.setQuotaUnit(quotaUnit);
        po.setStatus(STATUS_ENABLED);
        po.setDeleted(0);
        return po;
    }
}
