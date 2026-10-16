package com.wms.platform.modules.md.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 库区（表 {@code md_zone}，DDL 见 {@code 01} §3.12）。
 */
@Getter
@Setter
@TableName(value = "md_zone", autoResultMap = true)
public class MdZonePo {

    /** 存储区。 */
    public static final String TYPE_STORAGE = "存储区";

    /** 拣货区。 */
    public static final String TYPE_PICKING = "拣货区";

    /** 复核区。 */
    public static final String TYPE_CHECKING = "复核区";

    /** 隔离区（质检不合格暂时存放）。 */
    public static final String TYPE_ISOLATION = "隔离区";

    /** 退货区。 */
    public static final String TYPE_RETURN = "退货区";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 仓库。 */
    private Long warehouseId;

    /** 库区编码。 */
    private String zoneCode;

    /** 名称。 */
    private String name;

    /** 类型。 */
    private String zoneType;

    /** 是否隔离区。 */
    private Integer isolationFlag;

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
     * @param id           雪花 ID
     * @param tenantId     租户
     * @param warehouseId  仓库
     * @param zoneCode     库区编码
     * @param zoneType     类型
     * @param name         名称
     * @return 库区
     */
    public static MdZonePo of(long id, long tenantId, long warehouseId, String zoneCode, String zoneType,
                              String name) {
        MdZonePo po = new MdZonePo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setWarehouseId(warehouseId);
        po.setZoneCode(zoneCode);
        po.setZoneType(zoneType);
        // 隔离区标记由类型推导，避免"类型是隔离区但标记为 0"的自相矛盾数据
        po.setIsolationFlag(TYPE_ISOLATION.equals(zoneType) ? 1 : 0);
        po.setName(name);
        po.setDeleted(0);
        return po;
    }
}
