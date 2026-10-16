package com.wms.platform.modules.au.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 角色（表 {@code au_role}，DDL 见 {@code 01} §3.14）。 */
@Getter
@Setter
@TableName(value = "au_role", autoResultMap = true)
public class AuRolePo {

    /** 启用。 */
    public static final String STATUS_ENABLED = "启用";

    /** 停用。 */
    public static final String STATUS_DISABLED = "停用";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 角色码。 */
    private String roleCode;

    /** 名称。 */
    private String name;

    /** 说明。 */
    private String description;

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
     * @param id          雪花 ID
     * @param tenantId    租户
     * @param roleCode    角色码
     * @param name        名称
     * @param description 说明
     * @return 角色
     */
    public static AuRolePo of(long id, long tenantId, String roleCode, String name, String description) {
        AuRolePo po = new AuRolePo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setRoleCode(roleCode);
        po.setName(name);
        po.setDescription(description);
        po.setStatus(STATUS_ENABLED);
        po.setDeleted(0);
        return po;
    }
}
