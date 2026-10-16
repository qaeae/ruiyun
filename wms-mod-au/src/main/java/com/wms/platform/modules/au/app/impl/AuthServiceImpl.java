package com.wms.platform.modules.au.app;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.security.token.AccessToken;
import com.wms.platform.modules.au.domain.PasswordHasher;
import com.wms.platform.modules.au.infrastructure.mapper.PermissionMapper;
import com.wms.platform.modules.au.infrastructure.mapper.UserMapper;
import com.wms.platform.modules.au.infrastructure.mapper.UserWarehouseMapper;
import com.wms.platform.modules.au.infrastructure.po.AuUserPo;
import com.wms.platform.common.security.token.Hs256TokenCodec;
import com.wms.platform.common.config.WmsProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;

/**
 * 认证服务实现。
 *
 * <p>安全口径（每条都对应一种常见事故）：
 * <ul>
 *   <li><b>登录失败不区分"用户不存在"与"口令错误"</b>——否则接口变成用户名枚举器；
 *       但**失败日志里记真实原因**，运维能排障；</li>
 *   <li><b>停用/锁定用户一律拒绝</b>（不能只看口令对不对）；</li>
 *   <li>登录成功才更新 {@code last_login_at}（失败也算活跃会掩盖暴力破解）；</li>
 *   <li>超管标记写进令牌：超管跨仓全权限，因此该标记只应由平台负责人授予（后续复核流覆盖）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** 用户 Mapper。 */
    private final UserMapper userMapper;

    /** 权限 Mapper（角色码/权限码联表查）。 */
    private final PermissionMapper permissionMapper;

    /** 仓库授权 Mapper。 */
    private final UserWarehouseMapper userWarehouseMapper;

    /** 令牌编解码。 */
    private final Hs256TokenCodec tokenCodec;

    /** 配置。 */
    private final WmsProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccessToken login(String username, String password) {
        // 1. 一期只有一个租户入口；多租户时由登录名域名/子域决定（W5 前端接入时明确）
        long tenantId = com.wms.platform.common.context.TenantContext.DEFAULT_TENANT_ID;
        AuUserPo user = userMapper.selectByUsername(tenantId, username);
        // 2. 口令校验（用户不存在也走同一条错误分支，避免用户名枚举）
        if (user == null || !PasswordHasher.matches(password, user.getPasswordHash())) {
            log.warn("登录失败 username={} 原因={}", username, user == null ? "用户不存在" : "口令错误");
            throw new BizException(ErrorCode.FORBIDDEN, "用户名或口令错误");
        }
        // 3. 状态检查（停用/锁定不能靠"口令对"绕过）
        if (!user.isEnabled()) {
            log.warn("登录被拒 username={} status={}", username, user.getStatus());
            throw new BizException(ErrorCode.FORBIDDEN, "账号状态不可用：" + user.getStatus());
        }
        // 4. 记录登录时间（仅成功）
        userMapper.updateLastLogin(tenantId, user.getId());
        // 5. 载入授权并签发
        log.info("登录成功 username={} userId={}", username, user.getId());
        return loadTokenContent(tenantId, user.getId());
    }

    @Override
    public AccessToken refresh(AccessToken current) {
        // 1. 重新查库（**刷新即拿到最新授权**：撤销角色后刷新立刻失效）
        return loadTokenContent(current.tenantId(), current.userId());
    }

    @Override
    @Transactional(readOnly = true)
    public AccessToken loadTokenContent(long tenantId, long userId) {
        // 1. 用户必须存在且可用（授权可能刚被停用）
        AuUserPo user = userMapper.selectByIdSafe(tenantId, userId);
        if (user == null || !user.isEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "用户不存在或已停用");
        }
        // 2. 角色与权限（一次联表查，避免 N+1）
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getSecurity().getTokenTtlMinutes(), ChronoUnit.MINUTES);
        return new AccessToken(user.getId(), tenantId, user.getUsername(), user.isAdmin(),
                new LinkedHashSet<>(permissionMapper.selectRoleCodesByUser(tenantId, userId)),
                new LinkedHashSet<>(permissionMapper.selectPermCodesByUser(tenantId, userId)),
                new LinkedHashSet<>(userWarehouseMapper.selectWarehouseIdsByUser(tenantId, userId)),
                issuedAt, expiresAt);
    }

    @Override
    public String issue(AccessToken token) {
        return tokenCodec.encode(token, properties.getSecurity().getTokenSecret());
    }

    @Override
    @Transactional(readOnly = true)
    public AccessToken loadServiceTokenContent() {
        // 1. 服务账号由配置指定（默认 svc-ops）：**它必须是一个真实的 RBAC 用户**——
        //    权限来自角色授权，才能做到"最小权限 + 可审计 + 可随时撤权"；
        //    绝不用"内部调用一律放行"这种万能钥匙。
        long tenantId = com.wms.platform.common.context.TenantContext.DEFAULT_TENANT_ID;
        String username = properties.getSecurity().getServiceAccountUsername();
        AuUserPo user = userMapper.selectByUsername(tenantId, username);
        if (user == null || !user.isEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "服务账号不可用（需先创建并授权）：" + username);
        }
        // 2. 载入授权（角色/权限/仓库范围），与用户登录同一条路径
        AccessToken full = loadTokenContent(tenantId, user.getId());
        // 3. 换成短 TTL：服务令牌是给后台任务用的，有效期越短越安全（泄露窗口小），
        //    调用方按"TTL − 60s"缓存并在过期前自动续期。
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getSecurity().getServiceTokenTtlMinutes(), ChronoUnit.MINUTES);
        return new AccessToken(full.userId(), full.tenantId(), full.username(), full.adminFlag(),
                full.roleCodes(), full.permissions(), full.warehouseIds(), issuedAt, expiresAt);
    }

    @Override
    public AccessToken verify(String compact) {
        return tokenCodec.decode(compact, properties.getSecurity().getTokenSecret());
    }
}
