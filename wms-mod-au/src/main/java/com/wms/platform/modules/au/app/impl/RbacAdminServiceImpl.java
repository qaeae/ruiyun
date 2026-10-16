package com.wms.platform.modules.au.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.security.PiiCipher;
import com.wms.platform.modules.au.api.ro.CreateUserRo;
import com.wms.platform.modules.au.api.ro.RoleCreateRo;
import com.wms.platform.modules.au.api.vo.RoleVo;
import com.wms.platform.modules.au.api.vo.UserVo;
import com.wms.platform.modules.au.app.RbacAdminService;
import com.wms.platform.modules.au.domain.PasswordHasher;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.modules.au.infrastructure.mapper.PermissionMapper;
import com.wms.platform.modules.au.infrastructure.mapper.RoleMapper;
import com.wms.platform.modules.au.infrastructure.mapper.RolePermissionMapper;
import com.wms.platform.modules.au.infrastructure.mapper.UserMapper;
import com.wms.platform.modules.au.infrastructure.mapper.UserRoleMapper;
import com.wms.platform.modules.au.infrastructure.mapper.UserWarehouseMapper;
import com.wms.platform.modules.au.infrastructure.po.AuPermissionPo;
import com.wms.platform.modules.au.infrastructure.po.AuRolePermissionPo;
import com.wms.platform.modules.au.infrastructure.po.AuRolePo;
import com.wms.platform.modules.au.infrastructure.po.AuUserPo;
import com.wms.platform.modules.au.infrastructure.po.AuUserRolePo;
import com.wms.platform.modules.au.infrastructure.po.AuUserWarehousePo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * RBAC 管理服务实现。
 *
 * <p>规范落地：B1（Wrapper 只在 Mapper）、B4（Po 静态工厂）、B5（异常带码）、B7（编号步骤注释）、B9（构造注入）。
 */
@Service
@RequiredArgsConstructor
public class RbacAdminServiceImpl implements RbacAdminService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RbacAdminServiceImpl.class);

    /** 用户 Mapper。 */
    private final UserMapper userMapper;

    /** 角色 Mapper。 */
    private final RoleMapper roleMapper;

    /** 权限 Mapper。 */
    private final PermissionMapper permissionMapper;

    /** 用户角色 Mapper。 */
    private final UserRoleMapper userRoleMapper;

    /** 角色权限 Mapper。 */
    private final RolePermissionMapper rolePermissionMapper;

    /** 用户仓库 Mapper。 */
    private final UserWarehouseMapper userWarehouseMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** PII 加密。 */
    private final PiiCipher piiCipher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUser(CreateUserRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 登录名唯一
        if (userMapper.selectByUsername(tenant, ro.username()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "登录名已存在：" + ro.username());
        }
        // 2. 口令哈希（不落明文；强度下限在 PasswordHasher 内校验）
        String hash = PasswordHasher.hash(ro.password());
        // 3. 手机号加密 + 脱敏
        String phone = ro.phone();
        String enc = phone == null || phone.isBlank() ? null : piiCipher.encrypt(phone.trim());
        String masked = phone == null || phone.isBlank() ? null : PiiCipher.maskPhone(phone.trim());
        // 4. 落库
        AuUserPo po = AuUserPo.of(idGenerator.nextId(), tenant, ro.username().trim(), hash, ro.name().trim(),
                enc, masked);
        boolean admin = ro.adminFlag() != null && ro.adminFlag();
        if (admin) {
            // 超管标记必须显式：它是"跨仓全权限"，不能通过普通授权路径悄悄获得
            po.setAdminFlag(1);
            log.warn("创建超管账号 username={}（跨仓全权限，请确认已走审批）", ro.username());
        }
        po.setCreatedBy(TenantContext.userId());
        userMapper.insert(po);
        return po.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRole(RoleCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 角色码唯一
        if (roleMapper.selectByCode(tenant, ro.roleCode()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色码已存在：" + ro.roleCode());
        }
        // 2. 落库
        AuRolePo po = AuRolePo.of(idGenerator.nextId(), tenant, ro.roleCode().trim(), ro.name().trim(),
                ro.description());
        po.setCreatedBy(TenantContext.userId());
        roleMapper.insert(po);
        return po.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantRolePermissions(long roleId, List<String> permCodes) {
        long tenant = TenantContext.tenantId();
        // 1. 角色必须存在
        AuRolePo role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色不存在：" + roleId);
        }
        // 2. 权限码必须已登记（否则会产生永远不生效的僵尸授权）
        List<String> codes = permCodes == null ? List.of() : permCodes;
        for (String code : codes) {
            PermissionCodes.of(code);
        }
        List<AuPermissionPo> perms = permissionMapper.selectByCodes(tenant, codes);
        if (perms.size() != new java.util.LinkedHashSet<>(codes).size()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "存在未同步到权限表的权限码，请先执行权限目录同步（syncPermissionCatalog）");
        }
        // 3. 整批替换：先清空后重建（撤销必须确定可达）
        // 3. **整批物理清空**（不是逻辑删）：唯一键不含 deleted，逻辑删会留下残留行与重插冲突
        rolePermissionMapper.deleteAllByRole(tenant, roleId);
        for (AuPermissionPo perm : perms) {
            // 3.1 **先物理清理残留再插入**（真实缺陷修复）：
            //     唯一键 uk_au_role_perm(tenant, role, perm) **不含 deleted**，
            //     而上面的删除是**逻辑删除** → 直接插会与残留行撞唯一键
            //     （实测：第二次授权必报 DuplicateKeyException → 19999 系统错误，整批回滚）。
            //     与 assignUserRoles / assignUserWarehouses 的处理保持一致。
            rolePermissionMapper.insert(AuRolePermissionPo.of(idGenerator.nextId(), tenant, roleId, perm.getId()));
        }
        log.info("角色授权完成 roleCode={} 权限数={}", role.getRoleCode(), perms.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignUserRoles(long userId, List<String> roleCodes) {
        long tenant = TenantContext.tenantId();
        // 1. 用户必须存在
        if (userMapper.selectByIdSafe(tenant, userId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户不存在：" + userId);
        }
        // 2. 角色码必须存在
        List<String> codes = roleCodes == null ? List.of() : new ArrayList<>(new java.util.LinkedHashSet<>(roleCodes));
        List<AuRolePo> roles = roleMapper.selectByCodes(tenant, codes);
        if (roles.size() != codes.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "存在无效的角色码（角色不存在或不属于当前租户）");
        }
        // 3. 整批替换
        userRoleMapper.deleteByUser(tenant, userId, TenantContext.userId());
        for (AuRolePo role : roles) {
            // 3.1 已逻辑删除的记录会与唯一键冲突，这里按"重新激活"处理：直接物理清理残留后再插
            if (userRoleMapper.countAny(tenant, userId, role.getId()) > 0) {
                userRoleMapper.deleteAny(tenant, userId, role.getId());
            }
            try {
                userRoleMapper.insert(AuUserRolePo.of(idGenerator.nextId(), tenant, userId, role.getId()));
            } catch (DuplicateKeyException ex) {
                log.debug("用户角色关系已存在，忽略 userId={} roleCode={}", userId, role.getRoleCode());
            }
        }
        log.info("用户角色分配完成 userId={} 角色数={}", userId, roles.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignUserWarehouses(long userId, List<Long> warehouseIds) {
        long tenant = TenantContext.tenantId();
        // 1. 用户必须存在
        if (userMapper.selectByIdSafe(tenant, userId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户不存在：" + userId);
        }
        // 2. 整批替换（跨仓任职需显式授权；撤销同样要通过整批替换才能确定生效）
        userWarehouseMapper.deleteByUser(tenant, userId, TenantContext.userId());
        List<Long> ids = warehouseIds == null ? List.of() : new ArrayList<>(new java.util.LinkedHashSet<>(warehouseIds));
        for (Long warehouseId : ids) {
            userWarehouseMapper.deleteAny(tenant, userId, warehouseId);
            userWarehouseMapper.insert(AuUserWarehousePo.of(idGenerator.nextId(), tenant, userId, warehouseId));
        }
        log.info("用户仓库授权完成 userId={} 仓库数={}", userId, ids.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeUserStatus(long userId, boolean enabled) {
        long tenant = TenantContext.tenantId();
        if (userMapper.selectByIdSafe(tenant, userId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户不存在：" + userId);
        }
        userMapper.updateStatus(tenant, userId,
                enabled ? AuUserPo.STATUS_ENABLED : AuUserPo.STATUS_DISABLED, TenantContext.userId());
        log.info("用户状态变更 userId={} enabled={}", userId, enabled);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserVo> listUsers() {
        long tenant = TenantContext.tenantId();
        List<AuUserPo> users = userMapper.selectListAll(tenant);
        List<UserVo> vos = new ArrayList<>(users.size());
        for (AuUserPo user : users) {
            vos.add(new UserVo(user.getId(), user.getUsername(), user.getName(), user.getPhoneMasked(),
                    user.getStatus(), user.isAdmin(),
                    permissionMapper.selectRoleCodesByUser(tenant, user.getId()),
                    userWarehouseMapper.selectWarehouseIdsByUser(tenant, user.getId())));
        }
        return vos;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleVo> listRoles() {
        long tenant = TenantContext.tenantId();
        List<AuRolePo> roles = roleMapper.selectListAll(tenant);
        List<RoleVo> vos = new ArrayList<>(roles.size());
        for (AuRolePo role : roles) {
            List<Long> permIds = rolePermissionMapper.selectPermIdsByRole(tenant, role.getId());
            List<String> permCodes = new ArrayList<>();
            if (!permIds.isEmpty()) {
                for (AuPermissionPo perm : permissionMapper.selectByIds(tenant, permIds)) {
                    permCodes.add(perm.getPermCode());
                }
            }
            vos.add(new RoleVo(role.getId(), role.getRoleCode(), role.getName(), role.getStatus(), permCodes));
        }
        return vos;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listPermissionCodes() {
        return permissionMapper.selectListAll(TenantContext.tenantId()).stream()
                .map(AuPermissionPo::getPermCode).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncPermissionCatalog() {
        long tenant = TenantContext.tenantId();
        // 1. 现有权限码
        List<String> existing = listPermissionCodes();
        // 2. 补齐缺失的（幂等：重复执行只增不减，权限码的删除由评审决定而不是同步任务）
        int added = 0;
        int sort = 0;
        for (String code : PermissionCodes.all()) {
            sort++;
            if (existing.contains(code)) {
                continue;
            }
            permissionMapper.insert(AuPermissionPo.of(idGenerator.nextId(), tenant, code, code,
                    AuPermissionPo.TYPE_API, sort));
            added++;
        }
        if (added > 0) {
            log.info("权限目录同步完成，新增 {} 条", added);
        }
        return added;
    }
}
