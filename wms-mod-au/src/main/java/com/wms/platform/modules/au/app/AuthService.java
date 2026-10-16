package com.wms.platform.modules.au.app;

import com.wms.platform.common.security.token.AccessToken;

/**
 * 认证服务（03 API-AUTH-01）。
 *
 * <p>一期采用**签名令牌 + 短期有效期**，不做服务端会话表：令牌自带身份与授权信息，
 * 校验不查库（性能好、也无"会话表挂了全员登出"的风险）。代价是**改授权后需刷新令牌才生效**，
 * 这个取舍与理由写在 {@link AccessToken} 的类注释里。
 */
public interface AuthService {

    /**
     * 登录。
     *
     * @param username 登录名
     * @param password 明文口令
     * @return 令牌与身份信息
     */
    AccessToken login(String username, String password);

    /**
     * 刷新令牌（用现有令牌换新令牌；授权信息**重新查库**，因此刷新即可拿到最新授权）。
     *
     * @param current 现有令牌
     * @return 新令牌
     */
    AccessToken refresh(AccessToken current);

    /**
     * 重新加载某用户的令牌内容（登录与刷新共用）。
     *
     * @param tenantId 租户
     * @param userId   用户
     * @return 令牌内容（不含签发时间语义，由调用方补）
     */
    AccessToken loadTokenContent(long tenantId, long userId);

    /**
     * 签发紧凑令牌字符串。
     *
     * @param token 令牌内容
     * @return 字符串
     */
    String issue(AccessToken token);

    /**
     * 载入**服务账号**的令牌内容（P3.4 服务间调用：后台任务的身份）。
     *
     * <p>为什么要有它：后台任务没有用户请求上下文，无法转发用户令牌；
     * 而"内部令牌 + 身份头"的权限集为空，会被 @RequiresPermission 拒绝。
     * 服务账号走与用户完全相同的 RBAC 授权路径（最小权限、可审计、可撤权）。
     *
     * @return 令牌内容（TTL 为服务令牌专用配置，通常远短于用户令牌）
     */
    AccessToken loadServiceTokenContent();

    /**
     * 校验并解析令牌字符串。
     *
     * @param compact 字符串
     * @return 令牌内容
     */
    AccessToken verify(String compact);
}
