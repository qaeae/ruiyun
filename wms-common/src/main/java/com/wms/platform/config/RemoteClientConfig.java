package com.wms.platform.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 服务间调用客户端配置（P3.4）。
 *
 * <p><b>为什么需要 {@code @LoadBalanced}</b>：客户端写的是 {@code http://wms-core} 这样的
 * **服务名**，由 Spring Cloud LoadBalancer 结合 Nacos 解析成真实实例——
 * 这样实例扩缩容、换 IP、多副本轮流都不用改配置（写死 IP 的"伪服务调用"上线后必然出问题）。
 */
@Configuration
public class RemoteClientConfig {

    /**
     * 带负载均衡的 RestClient 构建器。
     *
     * @return 构建器
     */
    /**
     * 请求工厂：**必须带超时**（无超时=远端一慢就永久占线程）。
     *
     * @return 请求工厂
     */
    @Bean
    public ClientHttpRequestFactory remoteClientRequestFactory() {
        // 用 JDK HttpClient：**HttpURLConnection（SimpleClientHttpRequestFactory）对带 body 的 POST 会先发
        // Expect: 100-continue 并等服务端回 100**，等不到就卡到读超时——实测正好 10s，
        // 而 core 侧同一接口用 curl（不发该头）只要 13ms。JDK 客户端不发这个头。
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    /**
     * 负载均衡 builder（base-url 是**服务名**时使用）。
     *
     * @param factory 请求工厂
     * @return builder
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(ClientHttpRequestFactory factory) {
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * 直连 builder（base-url 是**绝对地址**时使用：本地调试/灰度）。
     *
     * <p>不能共用负载均衡 builder——它会把 IP 当服务名解析并直接报错（真实踩坑）。
     *
     * @param factory 请求工厂
     * @return builder
     */
    @Bean
    public RestClient.Builder directRestClientBuilder(ClientHttpRequestFactory factory) {
        return RestClient.builder().requestFactory(factory);
    }
}
