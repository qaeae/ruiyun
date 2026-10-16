package com.wms.platform.common.device;

import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.constant.HeaderConst;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备绑定拦截器测试（03 §1.1）。
 *
 * <p>断言的三个分支对应现场三种真实诉求：客户端漏传头（要升级 App）、
 * 设备停用（要找管理员）、校验通过（放行并透传设备号）。
 */
@DisplayName("设备绑定拦截器")
class DeviceBindingInterceptorTest {

    /** 被校验的设备编号（通过场景）。 */
    private static final String CODE_OK = "PDA-A-007";

    /** 响应体（测试用）。 */
    private MockHttpServletResponse response;

    /** 记录校验器被调用的设备编号。 */
    private String validatedCode;

    /** 被测拦截器：校验器只认 {@link #CODE_OK}。 */
    private DeviceBindingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        response = new MockHttpServletResponse();
        validatedCode = null;
        interceptor = new DeviceBindingInterceptor(code -> {
            validatedCode = code;
            if (!CODE_OK.equals(code)) {
                throw new com.wms.platform.common.api.BizException(
                        ErrorCode.DEVICE_NOT_REGISTERED, "设备未登记：" + code);
            }
        });
    }

    @Test
    @DisplayName("缺少设备头 → 400 + 10001（客户端未按契约传参）")
    void missingHeaderShouldReturn400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pda/sync/batch");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":10001");
    }

    @Test
    @DisplayName("设备未登记/已停用 → 403 + 10006（设备台账问题，需人工处理）")
    void disabledDeviceShouldReturn403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pda/sync/batch");
        request.addHeader(HeaderConst.DEVICE_CODE, "PDA-Z-999");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":10006");
        assertThat(validatedCode).isEqualTo("PDA-Z-999");
    }

    @Test
    @DisplayName("校验通过 → 放行，并把设备号放进 request attribute 供业务层复用")
    void validDeviceShouldPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pda/sync/batch");
        request.addHeader(HeaderConst.DEVICE_CODE, CODE_OK);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute(HeaderConst.DEVICE_CODE)).isEqualTo(CODE_OK);
        assertThat(validatedCode).isEqualTo(CODE_OK);
    }
}
