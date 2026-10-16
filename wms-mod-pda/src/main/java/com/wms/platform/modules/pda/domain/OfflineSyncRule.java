package com.wms.platform.modules.pda.domain;

/**
 * 离线补传判定规则（{@code 01} §6 + {@code 03} §3.12）。
 *
 * <p>做成**纯函数**的原因：补传判定要在两个地方复用——实时接口（网络好时 PDA 逐条上传）
 * 与离线批处理（网络恢复后批量补传）。若判据写在 Service 里，两条路径很快就会不一致，
 * 而"同样的作业在两种网络状态下得到不同结果"是现场最难解释的问题。
 *
 * <p>判定顺序是有讲究的（先错扫、再状态、再库存）：
 * 错扫的单号根本不属于这个单据，先报"状态不对"会把现场引向错误的排查方向。
 */
public final class OfflineSyncRule {

    /** 幂等键分隔符（与 {@code iv_movement.idem_key} 同源同值）。 */
    private static final String SEPARATOR = ":";

    private OfflineSyncRule() {
    }

    /**
     * 构造幂等键：{@code deviceCode:taskId:action:seq}（{@code 01} §6）。
     *
     * <p><b>为什么必须带 deviceCode</b>：不同 PDA 可能持有同一个 taskId（任务跨设备重派、
     * 或两台设备重复领到同一任务）。若不带设备号，第二台设备的作业会被当作第一台的重复而**静默丢弃**，
     * 现场表现为"我明明扫了，系统说没扫"。
     *
     * @param deviceCode 设备编号
     * @param taskId     任务号
     * @param action     动作
     * @param seq        动作内序号
     * @return 幂等键
     */
    public static String idemKey(String deviceCode, String taskId, PdaAction action, int seq) {
        return String.join(SEPARATOR, deviceCode, taskId, action.name(), String.valueOf(seq));
    }

    /**
     * 判定一条补传。
     *
     * @param action        动作
     * @param currentStatus 服务端当前单据状态（单据不存在时传 {@code null}）
     * @param codeKnown     扫码号码是否可识别（单据/库位/商品存在且格式合法）
     * @param stockEnough   库存是否足够（预占/扣减前的可用量校验结果）
     * @return 判定结论
     */
    public static Verdict judge(PdaAction action, String currentStatus, boolean codeKnown, boolean stockEnough) {
        // 1. 号码不可识别（错扫/单据不存在）：拒绝并让现场重扫——状态判定对这类输入没有意义
        if (!codeKnown || currentStatus == null) {
            return new Verdict(SyncItemStatus.REJECTED, ConflictType.CODE_UNKNOWN, action.getTargetStatus(),
                    "号码不可识别：单据/库位/商品不存在或已失效，请重扫");
        }
        // 2. 单据状态已流转：转冲突，绝不覆盖服务端状态（01 §6）
        if (!action.getAllowedCurrentStatuses().contains(currentStatus)) {
            return new Verdict(SyncItemStatus.CONFLICT, ConflictType.STATUS_CHANGED, action.getTargetStatus(),
                    "单据状态已流转（当前「" + currentStatus + "」，补传动作「" + action.getLabel()
                            + "」要求「" + String.join("/", action.getAllowedCurrentStatuses()) + "」），转人工处理");
        }
        // 3. 库存已被占用：同样转冲突（人工判断是缺货还是重复占用）
        if (!stockEnough) {
            return new Verdict(SyncItemStatus.CONFLICT, ConflictType.STOCK_OCCUPIED, action.getTargetStatus(),
                    "库存不足或已被其他任务占用，转人工处理");
        }
        // 4. 通过
        return new Verdict(SyncItemStatus.ACCEPTED, null, action.getTargetStatus(),
                "受理成功（目标状态：" + action.getTargetStatus() + "）");
    }

    /**
     * 判定结论。
     *
     * @param status       逐条结果
     * @param conflictType 冲突类型（非冲突时为 {@code null}）
     * @param targetStatus 期望推进到的状态
     * @param message      说明（中文，回推 PDA 展示）
     */
    public record Verdict(SyncItemStatus status, ConflictType conflictType, String targetStatus, String message) {

        /**
         * 是否需要在服务端建"待人工处理"任务（冲突）或记误扫（拒绝）。
         *
         * @return 冲突返回 {@code true}
         */
        public boolean isConflict() {
            return status == SyncItemStatus.CONFLICT;
        }
    }
}
