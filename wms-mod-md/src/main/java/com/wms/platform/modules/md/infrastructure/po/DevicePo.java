package com.wms.platform.modules.md.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设备档案（表 {@code md_device}，DDL 见 {@code 01} §3.10）。
 *
 * <p>规范落地（B3/B4/B6）：后缀 {@code Po} + {@code @TableName(autoResultMap = true)} +
 * 逻辑删除显式 {@code @TableLogic} + 与本源强绑定的状态取值定义在类内 + 静态工厂创建。
 *
 * <p>注意：设备档案是**主数据、不是单据**——启停不写 {@code pf_biz_status_log}、不走状态机
 * （{@code 01} §1.2 的范围限定为单据表）。
 */
@Getter
@Setter
@TableName(value = "md_device", autoResultMap = true)
public class DevicePo {

    /** 启用：请求可通过设备绑定校验。 */
    public static final String STATUS_ENABLED = "启用";

    /** 停用：该设备的 PDA 请求被拒（403 + 10006）。 */
    public static final String STATUS_DISABLED = "停用";

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 设备编号（现场唯一，PDA 请求头 X-Device-Code 取值）。 */
    private String deviceCode;

    /** 设备类型：PDA/SCANNER/PRINTER/SCALE/GATE。 */
    private String deviceType;

    /** 设备名称（如"复核台1-电子秤"）。 */
    private String name;

    /** 归属仓库；NULL 表示平台或跨仓共用。 */
    private Long warehouseId;

    /** 摆放位置描述。 */
    private String locationDesc;

    /** 状态：启用/停用。 */
    private String status;

    /** 最近一次在线时间（由设备请求刷新）。 */
    private LocalDateTime lastOnlineAt;

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

    /** 逻辑删除：0 未删、1 已删。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂：新登记的设备（B4：禁止 {@code new + 连续 setter}）。
     *
     * @param id           雪花 ID
     * @param tenantId     租户 ID
     * @param deviceCode   设备编号
     * @param deviceType   设备类型枚举码
     * @param name         设备名称
     * @param warehouseId  归属仓库（可空）
     * @param locationDesc 摆放位置（可空）
     * @param remark       备注（可空）
     * @param createdBy    创建人
     * @return 启用状态的新设备
     */
    public static DevicePo of(long id, long tenantId, String deviceCode, String deviceType, String name,
                              Long warehouseId, String locationDesc, String remark, Long createdBy) {
        DevicePo po = new DevicePo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setDeviceCode(deviceCode);
        po.setDeviceType(deviceType);
        po.setName(name);
        po.setWarehouseId(warehouseId);
        po.setLocationDesc(locationDesc);
        po.setStatus(STATUS_ENABLED);
        po.setRemark(remark);
        po.setCreatedBy(createdBy);
        po.setDeleted(0);
        return po;
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return STATUS_ENABLED.equals(status);
    }
}
