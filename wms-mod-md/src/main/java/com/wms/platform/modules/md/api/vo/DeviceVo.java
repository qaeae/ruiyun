package com.wms.platform.modules.md.api.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设备档案视图（规范条目 B2/B3：响应用 {@code Vo}、落在 {@code ..vo..} 包）。
 *
 * <p>为什么独立声明字段而不继承 {@code DevicePo}：本项目的 Po 位于 {@code infrastructure} 层，
 * 而架构规则禁止 {@code api} 依赖 {@code infrastructure}。因此字段需与 Po 保持同步
 * （给 Po 加字段时同步本类，已登记在 {@code STANDARDS-MAPPING.md} §二 B3）。
 */
@Getter
@Setter
public class DeviceVo {

    /** 设备编号。 */
    private String deviceCode;

    /** 设备类型枚举码。 */
    private String deviceType;

    /** 设备类型中文名（由服务层从 {@code DeviceType} 回填，便于运营界面直接展示）。 */
    private String deviceTypeLabel;

    /** 设备名称。 */
    private String name;

    /** 归属仓库 ID。 */
    private Long warehouseId;

    /** 摆放位置。 */
    private String locationDesc;

    /** 状态：启用/停用。 */
    private String status;

    /** 最近在线时间。 */
    private LocalDateTime lastOnlineAt;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
