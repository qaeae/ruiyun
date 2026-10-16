package com.wms.platform.modules.pda.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.util.Set;

/**
 * PDA 现场动作（03 §3.12 API-PDA-01 的 {@code action} 取值）。
 *
 * <p>每个动作把三件事绑在一起，这是它存在的价值：
 * <ol>
 *   <li><b>归属单据类型</b>（DO/ASN）——错扫时用来判断"这个单号根本不该在这个动作里出现"；</li>
 *   <li><b>允许的当前状态集合</b>——补传冲突判定的依据（{@code 01} §6「单据状态已流转」）；</li>
 *   <li><b>目标状态</b>——与 {@code 01} 状态机表逐行对应（拣货中→已复核、待装车→已出库、待上架→已入库）。</li>
 * </ol>
 *
 * <p>允许的状态集合**只含"下一步就是目标状态"的那一个前置状态**：例如装载只允许从 `待装车` 出发。
 * 若写成宽松的"任意未出库状态都行"，离线补传就会把一张还停在`拣货中`的单直接推进到`已出库`，
 * 跳过复核、打包、称重——这类"静默跳状态"是离线场景最难查的事故。
 */
public enum PdaAction {

    /** 拣货完成（{@code 01} §3 状态机第 3 行）：拣货中 → 已复核。 */
    PICK("拣货", "DO", Set.of("拣货中"), "已复核"),

    /** 装车扫码确认（{@code 01} §3 状态机第 9 行）：待装车 → 已出库。 */
    LOAD("装车", "DO", Set.of("待装车"), "已出库"),

    /** 上架（入库域）：待上架 → 已入库。 */
    PUTAWAY("上架", "ASN", Set.of("待上架"), "已入库");

    /** 中文名。 */
    private final String label;

    /** 归属单据类型：DO（出库单）/ASN（入库通知单）。 */
    private final String bizType;

    /** 允许的当前状态集合。 */
    private final Set<String> allowedCurrentStatuses;

    /** 目标状态。 */
    private final String targetStatus;

    PdaAction(String label, String bizType, Set<String> allowedCurrentStatuses, String targetStatus) {
        this.label = label;
        this.bizType = bizType;
        this.allowedCurrentStatuses = allowedCurrentStatuses;
        this.targetStatus = targetStatus;
    }

    /**
     * 中文名。
     *
     * @return 中文名
     */
    public String getLabel() {
        return label;
    }

    /**
     * 归属单据类型。
     *
     * @return {@code DO} 或 {@code ASN}
     */
    public String getBizType() {
        return bizType;
    }

    /**
     * 允许的当前状态集合。
     *
     * @return 状态集合
     */
    public Set<String> getAllowedCurrentStatuses() {
        return allowedCurrentStatuses;
    }

    /**
     * 目标状态。
     *
     * @return 目标状态
     */
    public String getTargetStatus() {
        return targetStatus;
    }

    /**
     * 反查（不区分大小写；非法值抛带码异常，避免脏动作进库）。
     *
     * @param action 动作码
     * @return 动作
     * @throws BizException 动作非法时抛 {@code 10001}
     */
    public static PdaAction of(String action) {
        if (action == null || action.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "补传动作不能为空");
        }
        for (PdaAction value : values()) {
            if (value.name().equalsIgnoreCase(action.trim())) {
                return value;
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR, "非法的补传动作：" + action + "（允许 PICK/LOAD/PUTAWAY）");
    }
}
