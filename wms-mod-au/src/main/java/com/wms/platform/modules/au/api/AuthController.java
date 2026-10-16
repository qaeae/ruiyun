package com.wms.platform.modules.au.api;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.security.PublicApi;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.modules.au.api.ro.CreateUserRo;
import com.wms.platform.modules.au.api.ro.GrantRo;
import com.wms.platform.modules.au.api.ro.LoginRo;
import com.wms.platform.modules.au.api.ro.RoleCreateRo;
import com.wms.platform.common.security.AuthorizationInterceptor;
import com.wms.platform.modules.au.api.vo.IdVo;
import com.wms.platform.modules.au.api.vo.LoginVo;
import com.wms.platform.modules.au.api.vo.RoleVo;
import com.wms.platform.modules.au.api.vo.UserVo;
import com.wms.platform.modules.au.app.AuthService;
import com.wms.platform.modules.au.app.RbacAdminService;
import com.wms.platform.common.security.token.AccessToken;
import com.wms.platform.common.security.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 认证与授权接口（03 API-AUTH-01 / 02 / 03）。
 *
 * <p>权限标注规则：**管理类接口全部要求权限**；登录接口是唯一公开的（{@link PublicApi}）。
 * 授权类接口（角色/仓库授权）单独用 {@code auth:*} 权限，避免"能改别人权限的人"与
 * "能做业务的人"混成一个角色（这是权限系统最常见的越权来源）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 认证服务。 */
    private final AuthService authService;

    /** 配置（用于校验内部凭据）。 */
    private final com.wms.platform.common.config.WmsProperties wmsProperties;

    /** RBAC 管理服务。 */
    private final RbacAdminService rbacAdminService;

    /**
     * 登录（API-AUTH-01）。
     *
     * @param ro 入参
     * @return 令牌与身份信息
     */
    @PublicApi("登录接口本身不可能要求已认证；失败信息不区分用户名/口令，防用户名枚举")
    @PostMapping("/login")
    public ApiResponse<LoginVo> login(@Valid @RequestBody LoginRo ro) {
        return ApiResponse.ok(toLoginVo(authService.login(ro.username(), ro.password())));
    }

    /**
     * 用**内部凭据换取服务令牌**（P3.4 服务间调用；）。
     *
     * <p><b>为什么是 @PublicApi 而不是 @RequiresPermission</b>：调用方是**服务**，它没有用户令牌；
     * 而它也不可能持有"某个用户"的权限。这里的安全边界是**内部凭据**（X-Gateway-Token），
     * 因此在方法内**显式校验**它——用户令牌无法调用本接口，
     * 而服务拿到的是**服务账号**的令牌（权限来自该账号的 RBAC 角色）。
     *
     * <p><b>绝不能</b>把这条路径做成"跳过权限校验"：那等于给所有内部调用一把万能钥匙。
     *
     * @param internalToken 内部凭据请求头
     * @return 服务令牌与身份信息
     */
    @PublicApi("内部凭据换取服务令牌：供服务间后台任务使用；用户令牌不可调用（本方法内显式校验内部凭据）")
    @PostMapping("/service-token")
    public ApiResponse<LoginVo> serviceToken(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Gateway-Token", required = false)
            String internalToken) {
        String expected = wmsProperties.getSecurity().getGatewayInternalToken();
        if (expected == null || expected.isBlank() || !expected.equals(internalToken)) {
            throw new BizException(ErrorCode.FORBIDDEN, "内部凭据无效：服务令牌只签发给持有内部凭据的服务");
        }
        return ApiResponse.ok(toLoginVo(authService.loadServiceTokenContent()));
    }

    /**
     * 刷新令牌（API-AUTH-01；**授权信息重新查库**，撤销角色后刷新即失效）。
     *
     * @param request 请求
     * @return 新令牌与身份信息
     */
    @PublicApi("用即将过期的令牌换新令牌；令牌本身即凭证，无需额外权限")
    @PostMapping("/refresh")
    public ApiResponse<LoginVo> refresh(HttpServletRequest request) {
        AccessToken current = (AccessToken) request.getAttribute(
                AuthorizationInterceptor.AttributeNames.ACCESS_TOKEN);
        if (current == null) {
            // 未带令牌时无法刷新——此时前端应走登录
            current = authService.verify(request.getHeader(com.wms.platform.common.constant.HeaderConst.AUTHORIZATION)
                    == null ? "" : request.getHeader(com.wms.platform.common.constant.HeaderConst.AUTHORIZATION)
                    .replace("Bearer ", ""));
        }
        return ApiResponse.ok(toLoginVo(authService.refresh(current)));
    }

    /**
     * 当前登录人信息（前端刷新页面后恢复菜单/按钮状态用）。
     *
     * @param request 请求
     * @return 身份信息
     */
    @PublicApi("仅回显当前令牌内容（令牌即凭证），用于前端恢复界面状态")
    @PostMapping("/me")
    public ApiResponse<LoginVo> me(HttpServletRequest request) {
        AccessToken token = (AccessToken) request.getAttribute(
                AuthorizationInterceptor.AttributeNames.ACCESS_TOKEN);
        return ApiResponse.ok(toLoginVo(token));
    }

    /**
     * 新增用户（API-AUTH-02）。
     *
     * @param ro 入参
     * @return 用户 ID
     */
    @RequiresPermission(PermissionCodes.AUTH_USER_MANAGE)
    @PostMapping("/users")
    public ApiResponse<IdVo> createUser(@Valid @RequestBody CreateUserRo ro) {
        return ApiResponse.ok(new IdVo(rbacAdminService.createUser(ro)));
    }

    /**
     * 用户列表。
     *
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.AUTH_USER_MANAGE)
    @GetMapping("/users")
    public ApiResponse<List<UserVo>> listUsers() {
        return ApiResponse.ok(rbacAdminService.listUsers());
    }

    /**
     * 用户启停用。
     *
     * @param userId  用户 ID
     * @param enabled 是否启用
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.AUTH_USER_MANAGE)
    @PostMapping("/users/{userId}/status")
    public ApiResponse<Void> changeUserStatus(@PathVariable long userId, @RequestParam boolean enabled) {
        rbacAdminService.changeUserStatus(userId, enabled);
        return ApiResponse.ok();
    }

    /**
     * 新增角色（API-AUTH-02）。
     *
     * @param ro 入参
     * @return 角色 ID
     */
    @RequiresPermission(PermissionCodes.AUTH_ROLE_MANAGE)
    @PostMapping("/roles")
    public ApiResponse<IdVo> createRole(@Valid @RequestBody RoleCreateRo ro) {
        return ApiResponse.ok(new IdVo(rbacAdminService.createRole(ro)));
    }

    /**
     * 角色列表（含权限码）。
     *
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.AUTH_ROLE_MANAGE)
    @GetMapping("/roles")
    public ApiResponse<List<RoleVo>> listRoles() {
        return ApiResponse.ok(rbacAdminService.listRoles());
    }

    /**
     * 角色授权限（整批替换）。
     *
     * @param roleId 角色 ID
     * @param ro     入参（{@code codes} 为权限码集合）
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.AUTH_ROLE_MANAGE)
    @PostMapping("/roles/{roleId}/permissions")
    public ApiResponse<Void> grantRolePermissions(@PathVariable long roleId, @Valid @RequestBody GrantRo ro) {
        rbacAdminService.grantRolePermissions(roleId, ro.codes());
        return ApiResponse.ok();
    }

    /**
     * 用户分配角色（整批替换；支持一人多角色）。
     *
     * @param userId 用户 ID
     * @param ro     入参（{@code codes} 为角色码集合）
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.AUTH_ROLE_MANAGE)
    @PostMapping("/users/{userId}/roles")
    public ApiResponse<Void> assignUserRoles(@PathVariable long userId, @Valid @RequestBody GrantRo ro) {
        rbacAdminService.assignUserRoles(userId, ro.codes());
        return ApiResponse.ok();
    }

    /**
     * 用户仓库数据授权（API-AUTH-03；整批替换，跨仓任职需显式授权）。
     *
     * @param userId 用户 ID
     * @param ro     入参
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.AUTH_WAREHOUSE_GRANT)
    @PostMapping("/users/{userId}/warehouses")
    public ApiResponse<Void> assignUserWarehouses(@PathVariable long userId, @Valid @RequestBody GrantRo ro) {
        rbacAdminService.assignUserWarehouses(userId, ro.warehouseIds());
        return ApiResponse.ok();
    }

    /**
     * 全部权限点（授权界面用）。
     *
     * @return 权限码列表
     */
    @RequiresPermission(PermissionCodes.AUTH_ROLE_MANAGE)
    @GetMapping("/permissions")
    public ApiResponse<List<String>> listPermissions() {
        return ApiResponse.ok(rbacAdminService.listPermissionCodes());
    }

    /**
     * 同步权限目录（把代码里登记的权限码补齐到权限表，幂等）。
     *
     * @return 本次新增数量
     */
    @RequiresPermission(PermissionCodes.AUTH_ROLE_MANAGE)
    @PostMapping("/permissions/sync")
    public ApiResponse<Integer> syncPermissions() {
        return ApiResponse.ok(rbacAdminService.syncPermissionCatalog());
    }

    /**
     * 令牌内容 → 登录视图。
     *
     * @param token 令牌内容
     * @return 视图
     */
    private LoginVo toLoginVo(AccessToken token) {
        return new LoginVo(authService.issue(token), token.expiresAt(), token.userId(), token.username(),
                token.adminFlag(), new ArrayList<>(token.roleCodes()), new ArrayList<>(token.permissions()),
                new ArrayList<>(token.warehouseIds()));
    }
}
