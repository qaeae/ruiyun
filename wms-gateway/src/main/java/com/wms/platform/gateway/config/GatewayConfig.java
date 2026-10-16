package com.wms.platform.gateway.config;

import com.wms.platform.gateway.filter.GatewayAuthFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * 网关装配：限流键解析器 + 配置绑定。
 *
 * <p>限流的键选择体现优先级：**已登录按用户**（同一个人的并发才该共享额度），
 * 未登录按 IP（登录接口等公开路径也要防刷）。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    /*
     * 身份头名称统一引用共享内核的 HeaderConst，**不再在本类里另写一份字面量**：
     * 网关（WebFlux）与服务（Servlet）分别硬编码同一批头名时，任何一处拼写差异
     * 都会表现为"鉴权失效/一直 403"，且编译期发现不了。详见 HeaderConst 的注释。
     */

    /**
     * 限流键：优先按令牌里的用户，退化为客户端 IP。
     *
     * @return 键解析器
     */
    @Bean
    @Primary
    public KeyResolver userOrIpKeyResolver(GatewayProperties properties) {
        return exchange -> {
            // 1. 先看网关鉴权过滤器解出的令牌（放入请求属性，避免重复验签）
            Object token = exchange.getAttribute(GatewayAuthFilter.ATTR_ACCESS_TOKEN);
            if (token instanceof com.wms.platform.common.security.token.AccessToken accessToken) {
                return Mono.just("user:" + accessToken.userId());
            }
            // 2. 退化：按客户端 IP（登录等未认证路径）
            String ip = exchange.getRequest().getRemoteAddress() == null
                    ? "unknown"
                    : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            return Mono.just("ip:" + ip);
        };
    }
}
