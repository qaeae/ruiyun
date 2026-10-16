package com.wms.platform.modules.au.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户（表 {@code au_user}，DDL 见 {@code 01} §3.14）。
 *
 * <p>字段口径以 DDL 注释为准；口令只存 {@code password_hash}（PBKDF2，见 {@code PasswordHasher}），
 * 手机号只存密文 + 脱敏值（{@code 01} §7）。
 */
@Getter
@Setter
@TableName(value = "au_user", autoResultMap = true)
public class AuUserPo {

    /** 启用。 */
    public static final String STATUS_ENABLED = "启用";

    /** 停用。 */
    public static final String STATUS_DISABLED = "停用";

    /** 锁定。 */
    public static final String STATUS_LOCKED = "锁定";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 登录名。 */
    private String username;

    /** 口令密文。 */
    private String passwordHash;

    /** 姓名。 */
    private String name;

    /** 手机号密文。 */
    private String phoneEnc;

    /** 手机号脱敏。 */
    private String phoneMasked;

    /** 状态。 */
    private String status;

    /** 是否超管。 */
    private Integer adminFlag;

    /** 末次登录时间。 */
    private LocalDateTime lastLoginAt;

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
     * @param username     登录名
     * @param passwordHash 口令密文
     * @param name         姓名
     * @param phoneEnc     手机号密文
     * @param phoneMasked  手机号脱敏
     * @return 用户
     */
    public static AuUserPo of(long id, long tenantId, String username, String passwordHash, String name,
                              String phoneEnc, String phoneMasked) {
        AuUserPo po = new AuUserPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setUsername(username);
        po.setPasswordHash(passwordHash);
        po.setName(name);
        po.setPhoneEnc(phoneEnc);
        po.setPhoneMasked(phoneMasked);
        po.setStatus(STATUS_ENABLED);
        po.setAdminFlag(0);
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

    /**
     * 是否超管。
     *
     * @return 超管返回 {@code true}
     */
    public boolean isAdmin() {
        return adminFlag != null && adminFlag == 1;
    }
}
