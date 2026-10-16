package com.wms.platform.common.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.ApiErrorWriter;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.constant.HeaderConst;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 幂等拦截器：**只做入口校验**（是否存在合法 {@code Idempotency-Key}），不做判重。
 *
 * <p>判重由 {@link IdempotentService} 在服务层完成——因为"同键不同体"的判定需要请求体哈希，
 * 而请求体在拦截器阶段只能靠包装 request 读到，代价高且对文件上传等场景不友好。
 *
 * <p>排除路径：外部回调（{@code /api/v1/callback/**}）用**验签 + 业务键**保证幂等（{@code 03} §六），
 * 不要求 {@code Idempotency-Key}；健康检查与查询接口（GET）天然幂等，无需校验。
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    /** 供 Controller/Service 从 request attribute 取值，避免多处重复解析头。 */
    public static final String IDEM_KEY_ATTRIBUTE = "wms.idempotencyKey";

    /** 写方法集合（只有写操作才要求幂等键）。 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** 幂等记录 Mapper 依赖（B9：构造注入，禁止字段 @Autowired）。 */
    private final ObjectMapper objectMapper;

    public IdempotencyInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 1. 非写操作直接放行（GET 天然幂等）
        if (!WRITE_METHODS.contains(request.getMethod())) {
            return true;
        }
        // 2. 校验幂等键存在（头名与长度上限取自常量类，B6）
        String idemKey = request.getHeader(HeaderConst.IDEMPOTENCY_KEY);
        if (idemKey == null || idemKey.isBlank()) {
            writeError(response, HttpStatus.BAD_REQUEST, ErrorCode.PARAM_ERROR,
                    "缺少请求头: " + HeaderConst.IDEMPOTENCY_KEY);
            return false;
        }
        // 3. 校验长度上限，与 pf_idempotent.idem_key VARCHAR(96) 对齐
        if (idemKey.length() > HeaderConst.IDEMPOTENCY_KEY_MAX_LENGTH) {
            writeError(response, HttpStatus.BAD_REQUEST, ErrorCode.PARAM_ERROR,
                    HeaderConst.IDEMPOTENCY_KEY + " 长度不得超过 " + HeaderConst.IDEMPOTENCY_KEY_MAX_LENGTH);
            return false;
        }
        request.setAttribute(IDEM_KEY_ATTRIBUTE, idemKey);
        return true;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorCode code, String message)
            throws Exception {
        // 统一走 ApiErrorWriter，保证与设备绑定拦截器等其它"提前拒绝"场景的响应结构完全一致
        ApiErrorWriter.write(response, status, code, message);
    }
}
