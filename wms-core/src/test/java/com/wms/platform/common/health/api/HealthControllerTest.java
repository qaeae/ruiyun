package com.wms.platform.common.health.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.GlobalExceptionHandler;
import com.wms.platform.common.idempotent.IdempotencyInterceptor;
import com.wms.platform.common.nacos.NacosServiceRegistrar;
import com.wms.platform.common.nacos.NacosSettings;
import com.wms.platform.common.trace.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 骨架横切能力自检：统一响应结构、traceId 透传、幂等键强制、参数校验、业务错误码。
 *
 * <p>用 MockMvc <b>standalone</b> 而非 {@code @WebMvcTest}：不启动 Spring 上下文、不连数据库，
 * 因此可以在无 MySQL/Docker 的机器上作为 CI 的第一道门。真实数据库行为见 {@code SkeletonIT}。
 */
class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 构造注入 Environment（替代原字段 @Value），使 standalone 组装无需启动 Spring 上下文
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "wms-platform");
        // Nacos 注册器：单元测试里传"未启用"的设置（不连任何外部服务，注册器不会真的去注册）
        NacosSettings settings = new NacosSettings();
        NacosServiceRegistrar registrar = new NacosServiceRegistrar(settings, environment);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController(environment, registrar))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .addInterceptors(new IdempotencyInterceptor(new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("GET /health → 统一响应 + code=0 + 返回 traceId 头")
    void healthShouldReturnUnifiedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("调用方传入的 X-Trace-Id 被透传（跨系统串联）")
    void incomingTraceIdShouldBePropagated() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Trace-Id", "trace-from-upstream"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-from-upstream"))
                .andExpect(jsonPath("$.traceId").value("trace-from-upstream"));
    }

    @Test
    @DisplayName("写接口缺幂等键 → 400 + 10001（不进业务）")
    void writeWithoutIdempotencyKeyShouldFail() throws Exception {
        mockMvc.perform(post("/api/v1/health/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    @DisplayName("写接口带幂等键 → 正常返回（幂等键写入 request attribute）")
    void writeWithIdempotencyKeyShouldSucceed() throws Exception {
        mockMvc.perform(post("/api/v1/health/check")
                        .header("Idempotency-Key", "HEALTH-20261019-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.echo").value("hello"));
    }

    @Test
    @DisplayName("参数校验失败 → 400 + 10001，且带字段名")
    void validationFailureShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/health/check")
                        .header("Idempotency-Key", "HEALTH-20261019-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("payload")));
    }

    @Test
    @DisplayName("业务规则失败 → HTTP 200 + 业务码（03 §1.2 约定：业务失败不改 HTTP 语义）")
    void businessFailureShouldStayHttp200() throws Exception {
        mockMvc.perform(post("/api/v1/health/check")
                        .header("Idempotency-Key", "HEALTH-20261019-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"boom\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("boom")));
    }
}
