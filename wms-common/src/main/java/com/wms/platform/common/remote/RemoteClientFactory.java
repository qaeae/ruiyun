package com.wms.platform.common.remote;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 跨服务 HTTP 客户端工厂（P3.4 踩坑后补）。
 *
 * <p><b>为什么必须有这个类（真实缺陷）</b>：{@code @LoadBalanced} 的 {@code RestClient} 会拦截**所有**请求，
 * 把 URL 里的 host 当作**服务名**交给负载均衡解析。于是：
 * <ul>
 *   <li>{@code http://wms-core}（服务名）→ 能解析实例 ✓；</li>
 *   <li>{@code http://127.0.0.1:8080}（绝对地址）→ 负载均衡去解析名为 {@code 127.0.0.1} 的"服务"
 *       → {@code Service Instance cannot be null, serviceId: 127.0.0.1}</li>
 * </ul>
 * 也就是说：**"服务名解析"与"绝对地址覆盖"不能共用一个 builder**。
 * 而 {@code wms.remote.*-base-url} 这类配置项本来就是为"本地直连调试/灰度"准备的，必须让它真能用。
 *
 * <p><b>因此按 base-url 形态选择</b>：
 * <ul>
 *   <li>形如 <b>服务名</b>（如 {@code http://wms-core}）→ 用 {@code @LoadBalanced} builder；</li>
 *   <li>形如 <b>绝对主机</b>（IP、localhost、含端口的域名）→ 用普通 builder（**同样带超时**）。</li>
 * </ul>
 * 两种都由本类统一创建，避免各客户端各写一套判断（否则迟早出现"有人漏判"）。
 */
@Slf4j
@Component
public class RemoteClientFactory {

    /** 负载均衡 builder（服务名）。 */
    private final RestClient.Builder loadBalancedBuilder;

    /** 直连 builder（绝对地址）。 */
    private final RestClient.Builder directBuilder;

    /**
     * 构造。
     *
     * @param loadBalancedBuilder 负载均衡 builder（服务名解析）
     * @param directBuilder       直连 builder（绝对地址）
     */
    public RemoteClientFactory(@Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedBuilder,
                              @Qualifier("directRestClientBuilder") RestClient.Builder directBuilder) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.directBuilder = directBuilder;
    }

    /**
     * 按 base-url 形态创建客户端。
     *
     * @param baseUrl 基址：服务名（如 http://wms-core）或绝对地址（如 http://127.0.0.1:8080）
     * @return HTTP 客户端
     */
    public RestClient create(String baseUrl) {
        boolean direct = isAbsoluteHost(baseUrl);
        log.info("[跨服务] baseUrl={} 方式={}（服务名走负载均衡；绝对地址直连）",
                baseUrl, direct ? "直连" : "负载均衡");
        return (direct ? directBuilder : loadBalancedBuilder).baseUrl(baseUrl).build();
    }

    /**
     * 判断是否为绝对主机地址（而非服务名）。
     *
     * @param baseUrl 基址
     * @return 绝对地址返回 true
     */
    public static boolean isAbsoluteHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String host = baseUrl.replaceFirst("^https?://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        // IPv4 / localhost / 形如 x.y 的域名（含点）都视为绝对主机；
        // 裸服务名（如 wms-core）不含点 → 走负载均衡
        String hostOnly = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        return hostOnly.equals("localhost") || hostOnly.contains(".");
    }

}
