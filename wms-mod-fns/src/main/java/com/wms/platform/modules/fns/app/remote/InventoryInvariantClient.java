package com.wms.platform.modules.fns.app.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 库存服务客户端（P3.4：替代原来的进程内 {@code iv.app.InventoryService} 调用）。
 *
 * <p><b>为什么必须改</b>：fns 拆成独立进程后进程内调用不存在；实测它还会**直接让 fns 启动失败**
 * （`Consider defining a bean of type 'InventoryService'`）——所以这不是优化，是拆分的前置条件。
 *
 * <p><b>为什么只依赖对方接口、不依赖对方的类</b>：调 `03` 已定义的
 * `POST /api/v1/inventory/invariant-check`，只映射返回结构（allPassed/failedCount/items）。
 * 若依赖 iv 模块的 VO 类，服务边界在**编译期**又粘回去了。
 *
 * <p><b>失败语义</b>：日终对账不允许静默跳过库存层——查不到就抛业务异常让本次对账失败，
 * 而不是给出一份"看起来通过"的结果。
 */
@Slf4j
@Component
public class InventoryInvariantClient {

    /** 库存服务名（Nacos 注册名，由负载均衡解析成真实实例）。 */
    private static final String SERVICE = "wms-core";

    /** HTTP 客户端（baseUrl 用服务名，不写死 IP）。 */
    private final RestClient restClient;

    /** 服务间调用支撑（内部令牌 + 身份转发 + 幂等键）。 */
    private final RemoteCallSupport remoteCallSupport;

    /**
     * 构造。
     *
     * @param remoteClientFactory 客户端工厂（按 base-url 形态自动选负载均衡或直连）
     * @param remoteCallSupport             服务间调用支撑
     * @param baseUrl                       基址（默认按服务名解析）
     */
    public InventoryInvariantClient(RemoteClientFactory remoteClientFactory,
                                    RemoteCallSupport remoteCallSupport,
                                    @Value("${wms.remote.inventory-base-url:http://" + SERVICE + "}") String baseUrl) {
        this.restClient = remoteClientFactory.create(baseUrl);
        this.remoteCallSupport = remoteCallSupport;
    }

    /**
     * 查询库存不变量违反明细（日终对账的"库存层"）。
     *
     * @return 违反明细（每行一条可读说明；全部通过时为空列表）
     */
    public List<String> describeViolations() {
        InvariantData data = call();
        if (data.items() == null) {
            return List.of();
        }
        return data.items().stream()
                .filter(item -> !item.passed())
                .map(item -> item.name() + "（差异 " + item.violation() + " 条）")
                .toList();
    }

    /**
     * 调用库存服务查不变量（每次重查：对账要的是当前状态，不能用缓存）。
     *
     * @return 数据
     */
    private InvariantData call() {
        HttpHeaders headers = remoteCallSupport.internalHeaders();
        headers.set(HeaderConst.IDEMPOTENCY_KEY, remoteCallSupport.idempotencyKey("INVARIANT-CHECK",
                String.valueOf(System.currentTimeMillis() / 1000)));
        log.info("[对账] 调用库存服务不变量检查（服务名 {}）", SERVICE);
        ApiResponse<InvariantData> response = restClient.post()
                .uri("/api/v1/inventory/invariant-check")
                .headers(h -> h.addAll(headers))
                // **必须显式给一个（空）JSON 体**：不带 body 的 POST 在 HttpURLConnection 下
                // 会让客户端与服务端互相等待（服务端等请求体、客户端等响应）→ 表现为 10s 读超时。
                // 踩坑记录：core 侧同一接口用 curl（自带 Content-Length: 0）只要 13ms。
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<InvariantData>>() {
                });
        if (response == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "库存服务无响应（对账不允许跳过库存层）");
        }
        if (response.getCode() != 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "库存不变量检查失败：code=" + response.getCode() + " msg=" + response.getMessage());
        }
        return response.getData();
    }

    /**
     * 不变量检查数据（忽略未知字段：被调方加字段不应让调用方反序列化失败）。
     *
     * @param allPassed   是否全部通过
     * @param failedCount 失败条数
     * @param items       明细
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvariantData(boolean allPassed, Integer failedCount, List<Item> items) {

        /**
         * 是否全部通过（null 视为未通过：宁可报问题也不要漏报）。
         *
         * @return 全部通过返回 true
         */
        public boolean allPassed() {
            return Boolean.TRUE.equals(allPassed);
        }
    }

    /**
     * 单条不变量结果。
     *
     * @param name      名称
     * @param violation 差异条数
     * @param passed    是否通过
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String name, Integer violation, boolean passed) {

        /**
         * 差异条数（null 视为 0）。
         *
         * @return 条数
         */
        public Integer violation() {
            return violation == null ? 0 : violation;
        }
    }
}
