package com.wms.platform.modules.md.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.device.DeviceType;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.mapstruct.MapStructExt;
import com.wms.platform.modules.md.api.ro.DeviceEditRo;
import com.wms.platform.modules.md.api.ro.DeviceEnableRo;
import com.wms.platform.modules.md.api.ro.DeviceRegisterRo;
import com.wms.platform.modules.md.api.vo.DeviceVo;
import com.wms.platform.modules.md.app.DeviceService;
import com.wms.platform.modules.md.infrastructure.mapper.DeviceMapper;
import com.wms.platform.modules.md.infrastructure.mapstruct.DeviceMS;
import com.wms.platform.modules.md.infrastructure.po.DevicePo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 设备档案应用服务实现。
 *
 * <p>规范落地：B1（不出现 Wrapper，SQL 收口在 Mapper）、B5（异常一律带错误码）、
 * B7（方法体内编号步骤注释）、B8（Po→Vo 走 MapStruct，批量走 {@code MapStructExt.toPojoList}）、
 * B9（构造注入）。
 */
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DeviceServiceImpl.class);

    /** 设备档案 Mapper。 */
    private final DeviceMapper deviceMapper;

    /** 雪花 ID 生成器。 */
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceVo register(DeviceRegisterRo ro) {
        // 1. 校验设备类型：非法枚举码直接抛 10001（避免脏值进库后到处判空）
        DeviceType type = DeviceType.of(ro.deviceType());
        long tenantId = TenantContext.tenantId();

        // 2. 先查重以给出友好提示（唯一键 uk_md_device 是并发下的最后防线）
        if (deviceMapper.selectByCode(tenantId, ro.deviceCode()) != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "设备编号已存在：" + ro.deviceCode());
        }

        // 3. 静态工厂装配 Po（B4）并落库
        DevicePo po = DevicePo.of(idGenerator.nextId(), tenantId, ro.deviceCode(), type.name(), ro.name(),
                ro.warehouseId(), ro.locationDesc(), ro.remark(), TenantContext.userId());
        try {
            deviceMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            // 3.1 并发登记同一编号：转成可读的业务错误，而不是把 500 抛给运营
            log.warn("设备编号并发登记冲突 deviceCode={}", ro.deviceCode());
            throw new BizException(ErrorCode.PARAM_ERROR, "设备编号已存在（并发登记）：" + ro.deviceCode());
        }

        // 4. 转 Vo 并回填类型中文名（前端无需再查字典）
        return toVo(po);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceVo edit(DeviceEditRo ro) {
        // 1. 取数并校验存在性（不存在 → 10001，避免"编辑成功但什么都没改"）
        long tenantId = TenantContext.tenantId();
        DevicePo existing = deviceMapper.selectByCode(tenantId, ro.deviceCode());
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "设备不存在：" + ro.deviceCode());
        }

        // 2. 更新（仅名称/仓库/位置/备注；设备编号不可改）
        deviceMapper.updateProfile(tenantId, ro.deviceCode(), ro.name(), ro.warehouseId(),
                ro.locationDesc(), ro.remark(), TenantContext.userId());

        // 3. 回读并转 Vo
        return toVo(deviceMapper.selectByCode(tenantId, ro.deviceCode()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceVo enableOpt(DeviceEnableRo ro) {
        // 1. 计算目标状态（用 Po 内常量，禁止散落中文字面量）
        String target = Boolean.TRUE.equals(ro.enabled()) ? DevicePo.STATUS_ENABLED : DevicePo.STATUS_DISABLED;
        long tenantId = TenantContext.tenantId();

        // 2. 更新状态（设备档案是主数据，不走单据状态机；SQL 收口在 Mapper）
        int affected = deviceMapper.updateStatus(tenantId, ro.deviceCode(), target, TenantContext.userId());
        if (affected == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "设备不存在：" + ro.deviceCode());
        }

        // 3. 回读并转 Vo
        return toVo(deviceMapper.selectByCode(tenantId, ro.deviceCode()));
    }

    @Override
    public DeviceVo findByCode(String deviceCode) {
        // 1. 查询
        DevicePo po = deviceMapper.selectByCode(TenantContext.tenantId(), deviceCode);
        // 2. 不存在直接抛错（避免调用方拿到 null 再 NPE）
        if (po == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "设备不存在：" + deviceCode);
        }
        // 3. 转 Vo
        return toVo(po);
    }

    @Override
    public List<DeviceVo> list(Long warehouseId, String deviceType) {
        // 1. 查询列表（条件为空即不限）
        List<DevicePo> list = deviceMapper.selectByCondition(TenantContext.tenantId(), warehouseId, deviceType);
        // 2. 批量转换：统一走 MapStructExt.toPojoList（B8：禁止在调用方写 stream/循环）
        List<DeviceVo> vos = MapStructExt.toPojoList(list, DeviceMS.INSTANCE::toVo);
        // 3. 回填类型中文名
        vos.forEach(vo -> vo.setDeviceTypeLabel(labelOf(vo.getDeviceType())));
        // 4. 返回
        return vos;
    }

    @Override
    public void assertEnabled(String deviceCode) {
        // 1. 查设备档案：未登记 → 10006（403，属设备台账问题，需人工处理）
        long tenantId = TenantContext.tenantId();
        DevicePo po = deviceMapper.selectByCode(tenantId, deviceCode);
        if (po == null) {
            throw new BizException(ErrorCode.DEVICE_NOT_REGISTERED, "设备未登记：" + deviceCode);
        }
        // 2. 已停用 → 同样 10006（停用是"临时不允许作业"的运营手段）
        if (!po.isEnabled()) {
            throw new BizException(ErrorCode.DEVICE_NOT_REGISTERED, "设备已停用：" + deviceCode);
        }
        // 3. 类型校验：只有 PDA/扫码枪可作现场作业入口，避免把打印机编码当 PDA 用
        DeviceType type = DeviceType.of(po.getDeviceType());
        if (!type.requiresBinding()) {
            throw new BizException(ErrorCode.DEVICE_NOT_REGISTERED,
                    "设备类型不支持现场作业入口：" + deviceCode + "（" + type.getLabel() + "）");
        }
        // 4. 顺带刷新在线时间：不做主动心跳，但每次真实请求都是一次"在线证据"
        deviceMapper.touchOnline(tenantId, deviceCode);
    }

    /**
     * Po → Vo（含类型中文名回填）。
     *
     * @param po 设备记录
     * @return 设备视图
     */
    private DeviceVo toVo(DevicePo po) {
        // 1. MapStruct 映射基础字段（B8）
        DeviceVo vo = DeviceMS.INSTANCE.toVo(po);
        // 2. 回填类型中文名（映射器不做业务翻译，避免字典逻辑散落）
        vo.setDeviceTypeLabel(labelOf(po.getDeviceType()));
        return vo;
    }

    /**
     * 类型码 → 中文名（查不到时原样返回，避免把已登记但枚举未覆盖的脏值变成 null）。
     *
     * @param deviceType 类型码
     * @return 中文名
     */
    private String labelOf(String deviceType) {
        try {
            return DeviceType.of(deviceType).getLabel();
        } catch (BizException ex) {
            log.warn("设备类型枚举未覆盖，原样返回 deviceType={}", deviceType);
            return deviceType;
        }
    }
}
