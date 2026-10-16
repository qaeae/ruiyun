package com.wms.platform.modules.au.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 用户仓库数据权限（表 {@code au_user_warehouse}；**跨仓任职需显式授权**，03 API-AUTH-03）。 */
@Getter
@Setter
@TableName(value = "au_user_warehouse", autoResultMap = true)
public class AuUserWarehousePo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 用户。 */
    private Long userId;

    /** 仓库。 */
    private Long warehouseId;

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
     * @param userId      用户
     * @param warehouseId 仓库
     * @return 授权
     */
    public static AuUserWarehousePo of(long id, long tenantId, long userId, long warehouseId) {
        AuUserWarehousePo po = new AuUserWarehousePo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setUserId(userId);
        po.setWarehouseId(warehouseId);
        po.setDeleted(0);
        return po;
    }
}
