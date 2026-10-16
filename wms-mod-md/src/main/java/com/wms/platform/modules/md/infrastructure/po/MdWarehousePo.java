package com.wms.platform.modules.md.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 仓库主数据（表 {@code md_warehouse}，DDL 见 {@code 01} §3.12）。
 */
@Getter
@Setter
@TableName(value = "md_warehouse", autoResultMap = true)
public class MdWarehousePo {

    /** 启用。 */
    public static final String STATUS_ENABLED = "启用";

    /** 停用。 */
    public static final String STATUS_DISABLED = "停用";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 仓库编码。 */
    private String warehouseCode;

    /** 名称。 */
    private String name;

    /** 省。 */
    private String province;

    /** 市。 */
    private String city;

    /** 地址。 */
    private String address;

    /** 时区。 */
    private String timezone;

    /** 截单时间。 */
    private LocalTime cutoffTime;

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
     * 静态工厂（B4）。
     *
     * @param id            雪花 ID
     * @param tenantId      租户
     * @param warehouseCode 仓库编码
     * @param name          名称
     * @param province      省
     * @param city          市
     * @param address       地址
     * @param cutoffTime    截单时间（{@code null} 时为默认 18:00）
     * @return 仓库
     */
    public static MdWarehousePo of(long id, long tenantId, String warehouseCode, String name, String province,
                                   String city, String address, LocalTime cutoffTime) {
        MdWarehousePo po = new MdWarehousePo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setWarehouseCode(warehouseCode);
        po.setName(name);
        po.setProvince(province);
        po.setCity(city);
        po.setAddress(address);
        po.setTimezone("Asia/Shanghai");
        po.setCutoffTime(cutoffTime == null ? LocalTime.of(18, 0) : cutoffTime);
        po.setStatus(STATUS_ENABLED);
        po.setDeleted(0);
        return po;
    }
}
