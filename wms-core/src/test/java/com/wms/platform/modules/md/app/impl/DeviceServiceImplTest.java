package com.wms.platform.modules.md.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.md.api.ro.DeviceEditRo;
import com.wms.platform.modules.md.api.ro.DeviceEnableRo;
import com.wms.platform.modules.md.api.ro.DeviceRegisterRo;
import com.wms.platform.modules.md.api.vo.DeviceVo;
import com.wms.platform.modules.md.infrastructure.mapper.DeviceMapper;
import com.wms.platform.modules.md.infrastructure.po.DevicePo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备档案服务测试（03 API-MD-05 + 03 §1.1 设备绑定校验）。
 *
 * <p>绑定校验的四种失败分支是重点：未登记、已停用、类型不对（把打印机编码当 PDA 用）、
 * 正常放行。这四条直接决定"停用设备还能不能继续作业"。
 */
@DisplayName("设备档案服务")
class DeviceServiceImplTest {

    /** 设备 Mapper（mock）。 */
    private DeviceMapper deviceMapper;

    /** 被测服务。 */
    private DeviceServiceImpl service;

    @BeforeEach
    void setUp() {
        deviceMapper = mock(DeviceMapper.class);
        service = new DeviceServiceImpl(deviceMapper, new SnowflakeIdGenerator(0, 0));
    }

    @Test
    @DisplayName("登记：静态工厂装配落库，返回带类型中文名的 Vo")
    void registerShouldInsertWithStaticFactory() {
        when(deviceMapper.selectByCode(anyLong(), anyString())).thenReturn(null);
        when(deviceMapper.insert(any(DevicePo.class))).thenReturn(1);

        DeviceVo vo = service.register(new DeviceRegisterRo("PDA-A-007", "pda", "打包区PDA", 1L, "A 区", null));

        assertThat(vo.getDeviceCode()).isEqualTo("PDA-A-007");
        assertThat(vo.getDeviceType()).as("类型码统一大写").isEqualTo("PDA");
        assertThat(vo.getDeviceTypeLabel()).isEqualTo("手持终端");
        assertThat(vo.getStatus()).isEqualTo(DevicePo.STATUS_ENABLED);

        ArgumentCaptor<DevicePo> captor = ArgumentCaptor.forClass(DevicePo.class);
        verify(deviceMapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).as("雪花 ID 由应用生成").isNotNull();
        assertThat(captor.getValue().getDeleted()).isZero();
    }

    @Test
    @DisplayName("登记：非法设备类型 → 10001（不让脏枚举进库）")
    void registerWithInvalidTypeShouldFail() {
        assertThatThrownBy(() -> service.register(new DeviceRegisterRo("X-1", "ROBOT", null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法的设备类型");
        verify(deviceMapper, never()).insert(any(DevicePo.class));
    }

    @Test
    @DisplayName("登记：编号已存在 → 10001（先查重给友好提示）")
    void registerDuplicateShouldFail() {
        when(deviceMapper.selectByCode(anyLong(), anyString())).thenReturn(existing("PDA-A-007", "PDA", "启用"));

        assertThatThrownBy(() -> service.register(new DeviceRegisterRo("PDA-A-007", "PDA", null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("编辑：设备不存在 → 10001（避免「编辑成功但什么都没改」）")
    void editMissingShouldFail() {
        when(deviceMapper.selectByCode(anyLong(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.edit(new DeviceEditRo("PDA-X-1", "n", null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        verify(deviceMapper, never()).updateProfile(anyLong(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("启停：受影响 0 行 → 10001；成功则状态写入目标值（SQL 收口在 Mapper）")
    void enableOptShouldUpdateStatus() {
        when(deviceMapper.updateStatus(anyLong(), anyString(), anyString(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.enableOpt(new DeviceEnableRo("PDA-X-1", false)))
                .isInstanceOf(BizException.class);

        when(deviceMapper.updateStatus(anyLong(), eq("PDA-A-007"), eq(DevicePo.STATUS_DISABLED), any()))
                .thenReturn(1);
        when(deviceMapper.selectByCode(anyLong(), eq("PDA-A-007")))
                .thenReturn(existing("PDA-A-007", "PDA", DevicePo.STATUS_DISABLED));

        DeviceVo vo = service.enableOpt(new DeviceEnableRo("PDA-A-007", false));

        assertThat(vo.getStatus()).isEqualTo(DevicePo.STATUS_DISABLED);
    }

    @Test
    @DisplayName("绑定校验：未登记 → 10006（403，需人工处理台账）")
    void assertEnabledMissingShouldFail() {
        when(deviceMapper.selectByCode(anyLong(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.assertEnabled("PDA-Z-999"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEVICE_NOT_REGISTERED))
                .hasMessageContaining("未登记");
    }

    @Test
    @DisplayName("绑定校验：已停用 → 10006")
    void assertEnabledDisabledShouldFail() {
        when(deviceMapper.selectByCode(anyLong(), anyString())).thenReturn(existing("PDA-A-007", "PDA", "停用"));

        assertThatThrownBy(() -> service.assertEnabled("PDA-A-007"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已停用");
        verify(deviceMapper, never()).touchOnline(anyLong(), anyString());
    }

    @Test
    @DisplayName("绑定校验：把打印机编码当 PDA 用 → 10006（类型不支持现场作业入口）")
    void assertEnabledWithWrongTypeShouldFail() {
        when(deviceMapper.selectByCode(anyLong(), anyString())).thenReturn(existing("PRINTER-A-01", "PRINTER", "启用"));

        assertThatThrownBy(() -> service.assertEnabled("PRINTER-A-01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持现场作业入口");
    }

    @Test
    @DisplayName("绑定校验：通过并顺带刷新在线时间（不引入主动心跳）")
    void assertEnabledShouldTouchOnline() {
        when(deviceMapper.selectByCode(anyLong(), anyString())).thenReturn(existing("PDA-A-007", "PDA", "启用"));

        service.assertEnabled("PDA-A-007");

        verify(deviceMapper).touchOnline(anyLong(), eq("PDA-A-007"));
    }

    @Test
    @DisplayName("列表：MapStruct 批量映射 + 回填类型中文名")
    void listShouldMapAndFillLabel() {
        when(deviceMapper.selectByCondition(anyLong(), any(), any()))
                .thenReturn(List.of(existing("PDA-A-007", "PDA", "启用"),
                        existing("SCALE-A-01", "SCALE", "启用")));

        List<DeviceVo> vos = service.list(null, null);

        assertThat(vos).hasSize(2);
        assertThat(vos.get(0).getDeviceTypeLabel()).isEqualTo("手持终端");
        assertThat(vos.get(1).getDeviceTypeLabel()).isEqualTo("电子秤");
        assertThat(vos).extracting(DeviceVo::getDeviceCode).containsExactly("PDA-A-007", "SCALE-A-01");
    }

    /**
     * 造一条设备记录。
     *
     * @param code   编号
     * @param type   类型
     * @param status 状态
     * @return 设备记录
     */
    private DevicePo existing(String code, String type, String status) {
        DevicePo po = DevicePo.of(2001L, 1L, code, type, "设备" + code, 1L, "A 区", null, 1L);
        po.setStatus(status);
        return po;
    }
}
