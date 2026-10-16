package com.wms.platform.common.device.offline;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PDA 离线补传队列（**客户端参考实现**，仅供 W5 客户端移植与端到端验证）。
 *
 * <p><b>它为什么放在服务端仓库里</b>：离线补传的语义（哪些结果要出队、哪些必须保留）是
 * **服务端与客户端的共同契约**。如果只把它写在接口文档里，W5 实现时很容易"看起来像"却漏掉关键行为：
 * 例如把 `conflict` 也当失败出队——现场做完的作业就此丢失，等发现时货已经发走了。
 * 放在这里可以被真实测试驱动，且 W5 可逐方法对照移植（React Native / Android 用本地 SQLite 承载同一语义）。
 *
 * <p><b>三条固化的行为（都有单测）</b>：
 * <ol>
 *   <li><b>断网入队只增不减</b>：任何异常都必须保留本地记录（宁可重复上传，绝不丢作业）；</li>
 *   <li><b>`accepted`/`duplicated` 才出队</b>：`duplicated` 表示服务端已有该数据，必须出队，
 *       否则每次补传都会把同一批数据再传一遍，队列永远清不空；</li>
 *   <li><b>快照/恢复</b>：模拟 App 被杀或重启后队列不丢（P3 步骤 6"断网 2 小时数据零丢失"的验证点）。</li>
 * </ol>
 *
 * <p>本类**不参与服务端运行时**：无 Spring 注解、无状态外部依赖，纯内存 + 简单文本快照。
 */
public class OfflineSyncQueue {

    /** 单批最大条数（与 03 的"一次 ≤200 条"一致）。 */
    public static final int MAX_BATCH_SIZE = 200;

    /** 快照字段分隔符（本实现字段值不含该字符）。 */
    private static final String SEP = "|";

    /** 待同步队列（保持扫码顺序：现场先扫的先同步）。 */
    private final List<QueuedScan> items = new ArrayList<>();

    /** 本地批次序号（自增）。 */
    private int batchSeq;

    /** 是否断网。 */
    private boolean offline;

    /**
     * 扫码入队（断网时调用；在线时也应先入队再上传——失败即可留在本地）。
     *
     * @param taskId       任务号
     * @param action       动作
     * @param seq          序号
     * @param bizNo        业务单号
     * @param skuCode      商品条码
     * @param locationCode 库位编码
     * @param qty          数量
     * @param scannedAt    扫码时间（本地时间）
     */
    public void enqueue(String taskId, String action, int seq, String bizNo, String skuCode, String locationCode,
                        BigDecimal qty, LocalDateTime scannedAt) {
        items.add(new QueuedScan(taskId, action, seq, bizNo, skuCode, locationCode, qty, scannedAt, 0, null, null));
    }

    /**
     * 取待同步项（不超过单批上限）。
     *
     * @return 待同步项（只读）
     */
    public List<QueuedScan> pendingForUpload() {
        return Collections.unmodifiableList(items.subList(0, Math.min(items.size(), MAX_BATCH_SIZE)));
    }

    /**
     * 取下一批序号。
     *
     * @return 批次序号
     */
    public int nextBatchSeq() {
        return ++batchSeq;
    }

    /**
     * 应用服务端逐条结果：只有 {@code accepted}/{@code duplicated} 出队。
     *
     * @param outcomes 逐条结果
     * @return 出队条数
     */
    public int applyResults(List<ItemOutcome> outcomes) {
        int removed = 0;
        for (ItemOutcome outcome : outcomes) {
            if (!outcome.canLeaveQueue()) {
                // 冲突/拒绝：保留本地并记录原因，现场据此决定重扫或走纸质兜底
                markRetained(outcome);
                continue;
            }
            if (items.removeIf(scan -> scan.matches(outcome))) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 队列长度。
     *
     * @return 待同步条数
     */
    public int size() {
        return items.size();
    }

    /**
     * 是否断网。
     *
     * @return 断网返回 {@code true}
     */
    public boolean isOffline() {
        return offline;
    }

    /**
     * 设置网络状态。
     *
     * @param offline 是否断网
     */
    public void setOffline(boolean offline) {
        this.offline = offline;
    }

    /**
     * 导出快照（模拟 App 被杀后本地持久化）。
     *
     * @return 文本快照
     */
    public String snapshot() {
        StringBuilder builder = new StringBuilder();
        builder.append("batchSeq=").append(batchSeq).append('\n');
        for (QueuedScan scan : items) {
            builder.append(scan.toSnapshotLine(SEP)).append('\n');
        }
        return builder.toString();
    }

    /**
     * 从快照恢复（模拟 App 重启后读取本地存储）。
     *
     * @param snapshot 文本快照
     * @return 队列实例
     */
    public static OfflineSyncQueue restore(String snapshot) {
        OfflineSyncQueue queue = new OfflineSyncQueue();
        if (snapshot == null || snapshot.isBlank()) {
            return queue;
        }
        for (String line : snapshot.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("batchSeq=")) {
                queue.batchSeq = Integer.parseInt(line.substring("batchSeq=".length()).trim());
                continue;
            }
            queue.items.add(QueuedScan.fromSnapshotLine(line, SEP));
        }
        return queue;
    }

    /**
     * 标记保留项（冲突/拒绝）及其原因。
     *
     * @param outcome 结果
     */
    private void markRetained(ItemOutcome outcome) {
        for (int index = 0; index < items.size(); index++) {
            QueuedScan scan = items.get(index);
            if (scan.matches(outcome)) {
                items.set(index, scan.retained(outcome.status(), outcome.message()));
                return;
            }
        }
    }

    /**
     * 本地队列项。
     *
     * @param taskId       任务号
     * @param action       动作
     * @param seq          序号
     * @param bizNo        业务单号
     * @param skuCode      商品条码
     * @param locationCode 库位编码
     * @param qty          数量
     * @param scannedAt    扫码时间
     * @param retryCount   重试次数
     * @param lastStatus   最近一次服务端结果
     * @param lastMessage  最近一次说明
     */
    public record QueuedScan(String taskId, String action, int seq, String bizNo, String skuCode, String locationCode,
                             BigDecimal qty, LocalDateTime scannedAt, int retryCount, String lastStatus,
                             String lastMessage) {

        /**
         * 是否匹配服务端结果（taskId + seq 定位一条作业）。
         *
         * @param outcome 结果
         * @return 匹配返回 {@code true}
         */
        boolean matches(ItemOutcome outcome) {
            return taskId.equals(outcome.taskId()) && seq == outcome.seq();
        }

        /**
         * 保留（冲突/拒绝）并累加重试次数。
         *
         * @param status  服务端结果码
         * @param message 说明
         * @return 新项
         */
        QueuedScan retained(String status, String message) {
            return new QueuedScan(taskId, action, seq, bizNo, skuCode, locationCode, qty, scannedAt,
                    retryCount + 1, status, message);
        }

        /**
         * 序列化为快照行。
         *
         * @param sep 分隔符
         * @return 快照行
         */
        String toSnapshotLine(String sep) {
            return String.join(sep, taskId, action, String.valueOf(seq), bizNo,
                    skuCode == null ? "" : skuCode, locationCode == null ? "" : locationCode,
                    qty == null ? "" : qty.toPlainString(),
                    scannedAt == null ? "" : scannedAt.toString(),
                    String.valueOf(retryCount), lastStatus == null ? "" : lastStatus,
                    lastMessage == null ? "" : lastMessage);
        }

        /**
         * 从快照行解析。
         *
         * @param line 快照行
         * @param sep  分隔符
         * @return 队列项
         */
        static QueuedScan fromSnapshotLine(String line, String sep) {
            String[] parts = line.split("\\" + sep, -1);
            return new QueuedScan(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3],
                    parts[4].isEmpty() ? null : parts[4],
                    parts[5].isEmpty() ? null : parts[5],
                    parts[6].isEmpty() ? null : new BigDecimal(parts[6]),
                    parts[7].isEmpty() ? null : LocalDateTime.parse(parts[7]),
                    Integer.parseInt(parts[8]),
                    parts[9].isEmpty() ? null : parts[9],
                    parts[10].isEmpty() ? null : parts[10]);
        }
    }

    /**
     * 服务端逐条结果（客户端视角）。
     *
     * @param taskId       任务号
     * @param seq          序号
     * @param status       结果码：accepted/duplicated/conflict/rejected
     * @param serverStatus 服务端当前状态
     * @param message      说明
     */
    public record ItemOutcome(String taskId, int seq, String status, String serverStatus, String message) {

        /**
         * 是否可以出队（仅 accepted/duplicated 可以）。
         *
         * @return 可出队返回 {@code true}
         */
        public boolean canLeaveQueue() {
            return "accepted".equals(status) || "duplicated".equals(status);
        }
    }
}
