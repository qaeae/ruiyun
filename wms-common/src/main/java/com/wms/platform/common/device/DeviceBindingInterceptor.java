package com.wms.platform.common.device;

import com.wms.platform.common.api.ApiErrorWriter;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.constant.HeaderConst;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 设备绑定拦截器（03 §1.1）：PDA/扫码枪请求必须携带已登记且启用的 {@code X-Device-Code}。
 *
 * <p><b>为什么把校验放在拦截器而不是每个接口里</b>：设备校验是**入口契约**，和幂等键同一性质。
 * 写在业务接口里，新增一个 PDA 接口就可能漏写一次——而漏写不会报错，只会让"停用设备仍能作业"
 * 这种问题在盘查时才被发现。注册在路径上则天然全覆盖。
 *
 * <p>两条分支刻意区分：
 * <ul>
 *   <li><b>没传头</b> → {@code 400 + 10001}（参数缺失，客户端用自己的 bug 修复）；</li>
 *   <li><b>传了但未登记/已停用</b> → {@code 403 + 10006}（授权失败，需要管理员处理台账）。</li>
 * </ul>
 * 混成一个码会让现场分不清"该升级 App"还是"该找管理员"。
 */
@RequiredArgsConstructor
public class DeviceBindingInterceptor implements HandlerInterceptor {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DeviceBindingInterceptor.class);

    /** 设备校验器（由 wms-core 注入 md 域的实现）。 */
    private final DeviceValidator deviceValidator;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 1. 取设备编号头
        String deviceCode = request.getHeader(HeaderConst.DEVICE_CODE);
        // 2. 缺失 → 400：这是客户端未按契约传参
        if (deviceCode == null || deviceCode.isBlank()) {
            ApiErrorWriter.write(response, HttpStatus.BAD_REQUEST, ErrorCode.PARAM_ERROR,
                    "缺少请求头: " + HeaderConst.DEVICE_CODE);
            return false;
        }
        // 3. 未登记/已停用 → 403 + 10006：这是设备台账问题，需人工处理
        try {
            deviceValidator.validate(deviceCode);
        } catch (BizException ex) {
            log.warn("设备绑定校验失败 deviceCode={} uri={} reason={}", deviceCode, request.getRequestURI(), ex.getMessage());
            ApiErrorWriter.write(response, HttpStatus.FORBIDDEN, ErrorCode.DEVICE_NOT_REGISTERED, ex.getMessage());
            return false;
        }
        // 4. 校验通过：把设备编号放进 request attribute，业务层无需重复解析头
        request.setAttribute(HeaderConst.DEVICE_CODE, deviceCode);
        return true;
    }
}
