package com.wms.platform.modules.md.config;

import com.wms.platform.common.device.DeviceBindingInterceptor;
import com.wms.platform.modules.md.app.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * md 域的 Web 装配：**设备绑定校验**（PDA 路径必须带已登记且启用的设备，03 §1.1）。
 *
 * <p><b>为什么放在 md 模块（P1.2）</b>：校验依赖 {@code DeviceService}（md 域的设备档案），
 * 因此只有依赖 md 的服务才会（也应该）获得这个拦截器；放在公共模块会让不关心设备的服务
 * 被迫依赖 md。顺序 2：在鉴权（0）与幂等（1）之后。
 */
@Configuration
@RequiredArgsConstructor
public class MdWebConfig implements WebMvcConfigurer {

    /** PDA 路径：必须带已登记且启用的设备。 */
    private static final String[] PDA_PATHS = {"/api/v1/pda/**"};

    /** 设备档案服务。 */
    private final DeviceService deviceService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DeviceBindingInterceptor(deviceService::assertEnabled))
                .addPathPatterns(PDA_PATHS)
                .order(2);
    }
}
