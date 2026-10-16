package com.wms.platform.common.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等拦截器自检（03 §1.3 第 1 条 + 全局约定）。
 *
 * <p>只验证"入口是否强制要求 Idempotency-Key"，不验证判重（判重在 {@code DbIdempotentServiceTest}，
 * 需要真实数据库，属集成测试范围）。
 */
class IdempotencyInterceptorTest {

    private final IdempotencyInterceptor interceptor = new IdempotencyInterceptor(new ObjectMapper());

    @Test
    @DisplayName("GET 无需幂等键（天然幂等）")
    void getDoesNotRequireKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/outbound/orders");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("POST 缺少 Idempotency-Key → 400 + 10001，且不进入业务")
    void postWithoutKeyShouldBeRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/outbound/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":10001");
    }

    @Test
    @DisplayName("幂等键超过 96 字符 → 400（与 pf_idempotent.idem_key VARCHAR(96) 对齐）")
    void tooLongKeyShouldBeRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/outbound/orders");
        request.addHeader("Idempotency-Key", "x".repeat(97));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("96");
    }

    @Test
    @DisplayName("合法幂等键 → 放行并写入 request attribute，业务层直接取用")
    void validKeyShouldPass() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/outbound/orders");
        request.addHeader("Idempotency-Key", "DO20261019000123-CREATE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(IdempotencyInterceptor.IDEM_KEY_ATTRIBUTE))
                .isEqualTo("DO20261019000123-CREATE");
    }

    @Test
    @DisplayName("PUT / DELETE 同样强制幂等键")
    void otherWriteMethodsShouldAlsoBeChecked() throws Exception {
        for (String method : new String[]{"PUT", "PATCH", "DELETE"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/outbound/orders/DO1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(request, response, new Object()))
                    .as("%s 应要求幂等键", method)
                    .isFalse();
        }
    }
}
