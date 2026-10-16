package com.wms.platform.modules.au.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 角色权限关联（表 {@code au_role_permission}）。 */
@Getter
@Setter
@TableName(value = "au_role_permission", autoResultMap = true)
public class AuRolePermissionPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 角色。 */
    private Long roleId;

    /** 权限。 */
    private Long permId;

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
     * @param roleId   角色
     * @param permId   权限
     * @return 关联
     */
    public static AuRolePermissionPo of(long id, long tenantId, long roleId, long permId) {
        AuRolePermissionPo po = new AuRolePermissionPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setRoleId(roleId);
        po.setPermId(permId);
        po.setDeleted(0);
        return po;
    }
}
