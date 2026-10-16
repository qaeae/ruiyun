package com.wms.platform.common.device;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备类型（与 {@code md_device.device_type} 取值一一对应，见 {@code 01} §3.10）。
 *
 * <p>取值用英文枚举码——与既有做法一致（{@code biz_type}、{@code movement_type} 同为英文），
 * 中文保留给 {@code status} 之类的状态类字段（{@code 01} §1.1）。
 *
 * <p>注意：**设备档案是主数据、不是单据**，其启停不写状态流水、不走状态机
 * （{@code 01} §1.2 的范围限定为单据表）。
 */
public enum DeviceType {

    /** 手持终端：现场作业主入口，请求必须带 {@code X-Device-Code}。 */
    PDA("手持终端"),

    /** 扫码枪：多为 HID 键盘仿真，扫码结果由客户端上报、服务端负责解析。 */
    SCANNER("扫码枪"),

    /** 打印机：面单/标签打印，任务由服务端下发、客户端执行。 */
    PRINTER("打印机"),

    /** 电子秤：复核称重，读数由客户端采集并上报，服务端做偏差拦截。 */
    SCALE("电子秤"),

    /** 闸机/道闸：一期不做硬件联控，仅登记放行记录（{@code pf_gate_record}）。 */
    GATE("闸机");

    /** 枚举码 → 枚举 的索引（固定顺序，便于错误信息稳定输出）。 */
    private static final Map<String, DeviceType> INDEX = Arrays.stream(values())
            .collect(Collectors.toMap(Enum::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));

    /** 中文名（日志与运营界面展示用）。 */
    private final String label;

    DeviceType(String label) {
        this.label = label;
    }

    /**
     * 中文名。
     *
     * @return 设备类型中文名
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按枚举码反查（不区分大小写）。
     *
     * @param code 枚举码
     * @return 设备类型
     * @throws BizException 码值非法时抛 {@code 10001}（B5：异常必须带状态码与中文消息）
     */
    public static DeviceType of(String code) {
        DeviceType type = code == null ? null : INDEX.get(code.trim().toUpperCase());
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "非法的设备类型：" + code + "，可选值 " + INDEX.keySet());
        }
        return type;
    }

    /**
     * 是否需要"设备绑定校验"。
     *
     * <p>只有现场作业入口（PDA/扫码枪）需要；打印机/电子秤/闸机由 PDA 侧调用，
     * 不对其单独做入口校验（避免同一个作业动作被校验两次）。
     *
     * @return 需要绑定校验返回 {@code true}
     */
    public boolean requiresBinding() {
        return this == PDA || this == SCANNER;
    }
}
