package com.wms.platform.modules.md.api;

import com.wms.platform.common.api.GlobalExceptionHandler;
import com.wms.platform.modules.md.api.vo.DeviceVo;
import com.wms.platform.modules.md.app.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备档案接口测试（MockMvc standalone）。
 *
 * <p>只验证"接口契约层"的三件事：参数校验是否被触发、响应结构是否统一、路径是否可达。
 * 业务逻辑由 {@code DeviceServiceImplTest} 覆盖——分层测试的好处是这里的失败必然指向接口层，
 * 而不是"服务里某个分支写错了"。
 */
@DisplayName("设备档案接口")
class DeviceControllerTest {

    /** 设备服务（mock）。 */
    private DeviceService deviceService;

    /** MockMvc。 */
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DeviceController(deviceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("登记：缺设备编号 → 400 + 10001（Bean Validation 生效）")
    void registerWithoutCodeShouldFailValidation() throws Exception {
        mockMvc.perform(post("/api/v1/master/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceType\":\"PDA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("设备编号不能为空")));
    }

    @Test
    @DisplayName("登记：编号含小写字母 → 400 + 10001（贴码规则约束）")
    void registerWithLowercaseCodeShouldFailValidation() throws Exception {
        mockMvc.perform(post("/api/v1/master/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\"pda-1\",\"deviceType\":\"PDA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    @DisplayName("登记成功：返回统一结构 + data")
    void registerShouldReturnUnifiedResponse() throws Exception {
        DeviceVo vo = new DeviceVo();
        vo.setDeviceCode("PDA-A-007");
        vo.setDeviceType("PDA");
        vo.setDeviceTypeLabel("手持终端");
        vo.setStatus("启用");
        when(deviceService.register(any())).thenReturn(vo);

        mockMvc.perform(post("/api/v1/master/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\"PDA-A-007\",\"deviceType\":\"PDA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deviceCode").value("PDA-A-007"))
                .andExpect(jsonPath("$.data.deviceTypeLabel").value("手持终端"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("按编号查询成功")
    void findByCodeShouldReturnDevice() throws Exception {
        DeviceVo vo = new DeviceVo();
        vo.setDeviceCode("SCALE-A-01");
        vo.setStatus("启用");
        when(deviceService.findByCode("SCALE-A-01")).thenReturn(vo);

        mockMvc.perform(get("/api/v1/master/devices/SCALE-A-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deviceCode").value("SCALE-A-01"));
    }
}
