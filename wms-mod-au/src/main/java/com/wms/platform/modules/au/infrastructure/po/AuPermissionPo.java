package com.wms.platform.modules.au.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 权限点（表 {@code au_permission}，DDL 见 {@code 01} §3.14）。 */
@Getter
@Setter
@TableName(value = "au_permission", autoResultMap = true)
public class AuPermissionPo {

    /** 接口型。 */
    public static final String TYPE_API = "接口";

    /** 按钮型。 */
    public static final String TYPE_BUTTON = "按钮";

    /** 菜单型。 */
    public static final String TYPE_MENU = "菜单";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 权限码。 */
    private String permCode;

    /** 名称。 */
    private String name;

    /** 类型。 */
    private String permType;

    /** 父权限。 */
    private Long parentId;

    /** 排序。 */
    private Integer sortNo;

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
     * @param permCode 权限码
     * @param name     名称
     * @param permType 类型
     * @param sortNo   排序
     * @return 权限点
     */
    public static AuPermissionPo of(long id, long tenantId, String permCode, String name, String permType,
                                    int sortNo) {
        AuPermissionPo po = new AuPermissionPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setPermCode(permCode);
        po.setName(name);
        po.setPermType(permType);
        po.setSortNo(sortNo);
        po.setDeleted(0);
        return po;
    }
}
