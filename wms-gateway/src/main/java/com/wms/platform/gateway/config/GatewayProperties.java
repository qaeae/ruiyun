package com.wms.platform.gateway.config;

import com.wms.platform.common.security.token.AccessToken;
import com.wms.platform.common.security.token.Hs256TokenCodec;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关配置（{@code wms.gateway.*}）。
 *
 * <p>密钥相关字段**只从环境变量/密钥管理注入**，不写进 Nacos（网关自己也要连 Nacos 拉配置，
 * 但密钥属于"Nacos 里不放"的那一类，口径与 README §40.3 一致）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "wms.gateway")
public class GatewayProperties {

    /** 令牌签名密钥（必须与业务服务一致，否则验签全部失败）。 */
    private String tokenSecret = "dev-only-change-me";

    /**
     * 内部调用凭据：网关转发给业务服务时带上 {@code X-Gateway-Token}。
     *
     * <p>业务服务用它判断"这个身份头是网关注入的、还是客户端伪造的"。
     * 为空表示未启用防伪造校验（仅开发期可接受）。
     */
    private String internalToken = "";

    /** 是否开启限流。 */
    private boolean rateLimitEnabled = true;

    /** 令牌桶容量（每秒允许的突发请求数）。 */
    private int rateLimitReplenishRate = 200;

    /** 令牌桶突发上限。 */
    private int rateLimitBurstCapacity = 400;

    /** 是否在日志里打印每个请求的鉴权结论（排查用，生产建议关）。 */
    private boolean authLogEnabled = false;

    /**
     * 便捷方法：解出访问令牌（不抛异常，失败返回 {@code null}）。
     *
     * <p>{@link Hs256TokenCodec} 是**无状态**的（密钥作为方法参数传入，实例本身不持有密钥），
     * 因此这里每次新建即可，不需要缓存实例。
     *
     * @param compact 紧凑令牌串
     * @return 访问令牌，无效返回 {@code null}
     */
    public AccessToken decodeQuietly(String compact) {
        if (compact == null || compact.isBlank()) {
            return null;
        }
        try {
            return new Hs256TokenCodec().decode(compact, tokenSecret);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
