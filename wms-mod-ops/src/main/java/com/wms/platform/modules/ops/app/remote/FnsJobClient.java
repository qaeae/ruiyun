package com.wms.platform.modules.ops.app.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.remote.RemoteCallSupport;
import com.wms.platform.common.remote.RemoteClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 财务服务任务客户端（P3.4：把 ops 对 fns 的**进程内调用**改成服务调用）。
 *
 * <p><b>为什么必须改</b>：要把 `wms-mod-fns` 从单体里移除（代码归属收敛），
 * ops 就不能再直接注入 `DailyReconciliationService` / `FallbackDeductionService`；
 * 而任务调度（ops）与财务（fns）本来就该是两个可独立发布的服务。
 *
 * <p><b>为什么返回 Map 而不是对方的 VO</b>：依赖被调方的类 = 编译期耦合，
 * 那样"服务边界"只在部署时成立、在编译期是假的。这里只依据 `03` 已定义的 HTTP 契约取字段。
 *
 * <p><b>身份从哪来（关键）</b>：任务是**后台触发**，没有用户请求上下文，
 * 因此 `RemoteCallSupport` 会用**服务账号令牌**（`wms.security.service-token`）。
 * 没配服务账号令牌时会走"内部令牌 + 身份头"，权限集为空 → 被调方 403
 * —— 这是**明确的失败**，而不是默默放行（安全优先）。
 */
@Slf4j
@Component
public class FnsJobClient implements FnsTaskPort {

    /** 财务服务名（Nacos 注册名）。 */
    private static final String SERVICE = "wms-fns";

    /** HTTP 客户端。 */
    private final RestClient restClient;

    /** 服务间调用支撑（服务账号令牌 + 幂等键）。 */
    private final RemoteCallSupport remoteCallSupport;

    /** JSON 序列化（body 预序列化：避免流式 body 的坑）。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造。
     *
     * @param remoteClientFactory 客户端工厂（按 base-url 形态选负载均衡或直连）
     * @param remoteCallSupport   服务间调用支撑
     * @param objectMapper        JSON
     * @param baseUrl             财务服务基址（默认按服务名解析）
     */
    public FnsJobClient(RemoteClientFactory remoteClientFactory,
                        RemoteCallSupport remoteCallSupport,
                        ObjectMapper objectMapper,
                        @Value("${wms.remote.fns-base-url:http://" + SERVICE + "}") String baseUrl) {
        this.restClient = remoteClientFactory.create(baseUrl);
        this.remoteCallSupport = remoteCallSupport;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行日终四层勾稽。
     *
     * @param bizDate 业务日期（T-1）
     * @return 各层结果（按 HTTP 契约的 JSON 字段取用）
     */
    @Override
    public List<Map<String, Object>> runDailyReconciliation(LocalDate bizDate) {
        return postForList("/api/v1/finance/daily-reconciliation/run?bizDate=" + bizDate,
                "DAILY-RECONCILE-" + bizDate);
    }

    /**
     * 执行兜底扣减扫描。
     *
     * @return 候选运单列表
     */
    @Override
    public List<Map<String, Object>> sweepFallback() {
        return postForList("/api/v1/finance/fallback-deductions/sweep",
                "FALLBACK-SWEEP-" + System.currentTimeMillis() / 1000);
    }

    /**
     * 调用财务服务并取列表结果。
     *
     * @param path    路径
     * @param bizNo   幂等键业务号（同一次调度重试用同一个键 → 下游幂等拦住重复扣款）
     * @return 列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> postForList(String path, String bizNo) {
        HttpHeaders headers = remoteCallSupport.internalHeaders();
        headers.set(HeaderConst.IDEMPOTENCY_KEY, remoteCallSupport.idempotencyKey(bizNo, path));
        headers.setContentType(MediaType.APPLICATION_JSON);
        log.info("[任务] 调用 {} 服务 path={}", SERVICE, path);
        ApiResponse<List<Map<String, Object>>> response = restClient.post()
                .uri(path)
                .headers(h -> h.addAll(headers))
                .body(toJson(Map.of()))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<Map<String, Object>>>>() {
                });
        if (response == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "财务服务无响应（任务不允许静默跳过）");
        }
        if (response.getCode() != 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "财务服务调用失败：code=" + response.getCode() + " msg=" + response.getMessage());
        }
        return response.getData() == null ? List.of() : response.getData();
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @param body 载荷
     * @return JSON
     */
    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "任务载荷序列化失败", ex);
        }
    }
}
