package com.wms.platform.modules.fns.app.remote;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.approval.ApprovalBizType;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.common.remote.RemoteCallSupport;
import com.wms.platform.common.remote.RemoteClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批服务客户端（P3.4：替代原来的进程内 {@code au.app.ApprovalService} 调用）。
 *
 * <p><b>为什么必须改</b>：审批（双人复核）属鉴权域，由 `wms-auth` 服务承载；
 * fns 拆成独立进程后无法进程内调用，且缺失会**直接导致 fns 启动失败**。
 *
 * <p><b>为什么"提交"仍要同步</b>：审批通过后的执行本来就是事件驱动（发件箱 + MQ，见 README §46），
 * 但"提交"必须同步拿到审批单号返回给调用方；因此这里失败**必须让业务请求失败**，
 * 否则业务以为"已在复核中"，实际没人复核。
 *
 * <p><b>服务间调用的两个必需件</b>（拆分才出现的问题，见 README §53.8）：
 * ① 内部令牌 + 身份头（否则被调接口的 {@code @RequiresPermission} 会 401）；
 * ② 派生幂等键（写接口缺 {@code Idempotency-Key} 会 400，顺带让重试幂等）。
 */
@Slf4j
@Component
public class ApprovalSubmitClient {

    /** 审批服务名（Nacos 注册名）。 */
    private static final String SERVICE = "wms-auth";

    /** HTTP 客户端。 */
    private final RestClient restClient;

    /** JSON 序列化（把 body 预序列化成字符串：避开流式 body 在 JDK 客户端下的取消行为）。 */
    private final ObjectMapper objectMapper;

    /** 服务间调用支撑。 */
    private final RemoteCallSupport remoteCallSupport;

    /**
     * 构造。
     *
     * @param remoteClientFactory 客户端工厂（按 base-url 形态自动选负载均衡或直连）
     * @param remoteCallSupport             服务间调用支撑
     * @param baseUrl                       基址（默认按服务名解析）
     */
    public ApprovalSubmitClient(RemoteClientFactory remoteClientFactory,
                                RemoteCallSupport remoteCallSupport,
                                ObjectMapper objectMapper,
                                @Value("${wms.remote.auth-base-url:http://" + SERVICE + "}") String baseUrl) {
        this.restClient = remoteClientFactory.create(baseUrl);
        this.remoteCallSupport = remoteCallSupport;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交审批。
     *
     * @param bizType     业务类型（取 code 传给审批服务，避免调用方拼字符串）
     * @param bizId       业务 ID（可空）
     * @param bizNo       业务单号
     * @param warehouseId 仓库（可空）
     * @param merchantId  商家（可空）
     * @param amount      金额（可空）
     * @param payloadJson 业务载荷快照（复核通过后按它执行，必须与提交时一致）
     * @param reason      申请理由
     * @return 审批单号
     */
    @SuppressWarnings("unchecked")
    public String submit(ApprovalBizType bizType, Long bizId, String bizNo, Long warehouseId, Long merchantId,
                         BigDecimal amount, String payloadJson, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizType", bizType.name());
        body.put("bizId", bizId);
        body.put("bizNo", bizNo);
        body.put("warehouseId", warehouseId);
        body.put("merchantId", merchantId);
        body.put("amount", amount);
        body.put("payloadJson", payloadJson);
        body.put("reason", reason);

        HttpHeaders headers = remoteCallSupport.internalHeaders();
        // 幂等键由"业务单号 + 载荷"派生：同一次业务的重试命中同一个键，不会提交出两张审批单
        headers.set(HeaderConst.IDEMPOTENCY_KEY, remoteCallSupport.idempotencyKey(bizNo, payloadJson));

        log.info("[审批] 提交到 {} 服务 bizType={} bizNo={}", SERVICE, bizType, bizNo);
        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri("/api/v1/approvals")
                .headers(h -> h.addAll(headers))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(toJson(body))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                });
        if (response == null || response.getCode() != 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "提交审批失败："
                    + (response == null ? "无响应" : "code=" + response.getCode() + " msg=" + response.getMessage()));
        }
        Object approvalNo = response.getData() == null ? null : response.getData().get("approvalNo");
        return approvalNo == null ? null : String.valueOf(approvalNo);
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @param body 载荷
     * @return JSON 字符串
     */
    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "审批载荷序列化失败", ex);
        }
    }
}
