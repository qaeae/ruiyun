package com.wms.platform.common.device.offline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线补传队列测试（P3 步骤 1/3/6 的客户端侧语义）。
 *
 * <p>三条断言都对应"现场会真的丢数据"的场景：
 * 断网扫码、重复补传、App 重启。
 */
@DisplayName("PDA 离线补传队列（客户端参考实现）")
class OfflineSyncQueueTest {

    @Test
    @DisplayName("断网连续扫码 20 次：全部入队，一条不丢")
    void offlineScansShouldAllBeQueued() {
        OfflineSyncQueue queue = new OfflineSyncQueue();
        queue.setOffline(true);

        for (int i = 1; i <= 20; i++) {
            queue.enqueue("T20261014001", i % 3 == 0 ? "LOAD" : "PICK", i, "DO20261014000001",
                    "1001", "2001", new BigDecimal("2.0000"), LocalDateTime.now());
        }

        assertThat(queue.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("App 重启（快照 → 恢复）后队列不丢：这是断网 2 小时零丢失的验证点")
    void snapshotRestoreShouldKeepAllItems() {
        OfflineSyncQueue queue = new OfflineSyncQueue();
        queue.setOffline(true);
        queue.nextBatchSeq();
        for (int i = 1; i <= 20; i++) {
            queue.enqueue("T20261014001", "PICK", i, "DO20261014000001", "1001", "2001",
                    new BigDecimal("1.0000"), LocalDateTime.now());
        }
        String snapshot = queue.snapshot();

        // 模拟 App 被杀后重启：新实例从本地存储恢复
        OfflineSyncQueue restored = OfflineSyncQueue.restore(snapshot);

        assertThat(restored.size()).isEqualTo(20);
        assertThat(restored.pendingForUpload()).hasSize(20);
        assertThat(restored.pendingForUpload().get(0).taskId()).isEqualTo("T20261014001");
        assertThat(restored.pendingForUpload().get(19).seq()).isEqualTo(20);
    }

    @Test
    @DisplayName("accepted/duplicated 出队；conflict/rejected 必须保留（否则现场作业真丢了）")
    void onlySuccessfulItemsShouldLeaveQueue() {
        OfflineSyncQueue queue = new OfflineSyncQueue();
        for (int i = 1; i <= 4; i++) {
            queue.enqueue("T1", "PICK", i, "DO1", "1001", "2001", new BigDecimal("1.0000"), LocalDateTime.now());
        }

        int removed = queue.applyResults(List.of(
                new OfflineSyncQueue.ItemOutcome("T1", 1, "accepted", "已复核", "ok"),
                new OfflineSyncQueue.ItemOutcome("T1", 2, "duplicated", "已复核", "幂等重复"),
                new OfflineSyncQueue.ItemOutcome("T1", 3, "conflict", "已出库", "状态已流转"),
                new OfflineSyncQueue.ItemOutcome("T1", 4, "rejected", null, "错扫")));

        assertThat(removed).isEqualTo(2);
        assertThat(queue.size()).as("冲突与拒绝必须留在本地").isEqualTo(2);
        assertThat(queue.pendingForUpload()).extracting(OfflineSyncQueue.QueuedScan::seq)
                .containsExactly(3, 4);
        assertThat(queue.pendingForUpload().get(0).lastStatus()).isEqualTo("conflict");
        assertThat(queue.pendingForUpload().get(0).retryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("组批不超过 200 条（一次上传过多在弱网下重传代价指数级放大）")
    void uploadBatchShouldBeCapped() {
        OfflineSyncQueue queue = new OfflineSyncQueue();
        for (int i = 1; i <= 250; i++) {
            queue.enqueue("T1", "PICK", i, "DO1", "1001", "2001", BigDecimal.ONE, LocalDateTime.now());
        }

        assertThat(queue.pendingForUpload()).hasSize(OfflineSyncQueue.MAX_BATCH_SIZE);
        assertThat(queue.size()).isEqualTo(250);
    }
}
