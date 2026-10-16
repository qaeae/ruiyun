package com.wms.platform.modules.au.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 用户角色关联（表 {@code au_user_role}；**一人多角色**，03 API-AUTH-02）。 */
@Getter
@Setter
@TableName(value = "au_user_role", autoResultMap = true)
public class AuUserRolePo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 用户。 */
    private Long userId;

    /** 角色。 */
    private Long roleId;

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
     * @param userId   用户
     * @param roleId   角色
     * @return 关联
     */
    public static AuUserRolePo of(long id, long tenantId, long userId, long roleId) {
        AuUserRolePo po = new AuUserRolePo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setUserId(userId);
        po.setRoleId(roleId);
        po.setDeleted(0);
        return po;
    }
}
