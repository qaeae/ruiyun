package com.wms.platform.modules.pda.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 离线补传判定规则测试（{@code 01} §6 + {@code 03} §3.12）。
 *
 * <p>重点是**判定顺序**与**幂等键构成**：
 * 先错扫再状态、幂等键必须含设备号——这两条错了，现场会出现
 * "我明明扫了系统说没扫"和"报错信息把我引向错误的排查方向"。
 */
@DisplayName("离线补传判定规则")
class OfflineSyncRuleTest {

    @Test
    @DisplayName("幂等键含设备号：不同设备做同一任务不会互相顶掉")
    void idemKeyShouldContainDeviceCode() {
        String keyA = OfflineSyncRule.idemKey("PDA-A-007", "T1", PdaAction.PICK, 1);
        String keyB = OfflineSyncRule.idemKey("PDA-B-001", "T1", PdaAction.PICK, 1);

        assertThat(keyA).isEqualTo("PDA-A-007:T1:PICK:1");
        assertThat(keyB).as("不同设备的同一任务必须是不同幂等键，否则第二台的作业会被静默丢弃")
                .isNotEqualTo(keyA);
    }

    @Test
    @DisplayName("号码不可识别（错扫/单据不存在）→ rejected，且优先于状态判定")
    void unknownCodeShouldBeRejected() {
        OfflineSyncRule.Verdict verdict = OfflineSyncRule.judge(PdaAction.PICK, null, false, false);

        assertThat(verdict.status()).isEqualTo(SyncItemStatus.REJECTED);
        assertThat(verdict.conflictType()).isEqualTo(ConflictType.CODE_UNKNOWN);
        assertThat(verdict.isConflict()).isFalse();
    }

    @Test
    @DisplayName("状态已流转 → conflict，目标状态照常回推（供人工判断）")
    void statusChangedShouldConflict() {
        OfflineSyncRule.Verdict verdict = OfflineSyncRule.judge(PdaAction.PICK, "已出库", true, true);

        assertThat(verdict.status()).isEqualTo(SyncItemStatus.CONFLICT);
        assertThat(verdict.conflictType()).isEqualTo(ConflictType.STATUS_CHANGED);
        assertThat(verdict.targetStatus()).isEqualTo("已复核");
        assertThat(verdict.message()).contains("已出库").contains("拣货中");
    }

    @Test
    @DisplayName("库存不足/被占用 → conflict（不是 rejected：货没丢，是要人判断）")
    void insufficientStockShouldConflict() {
        OfflineSyncRule.Verdict verdict = OfflineSyncRule.judge(PdaAction.LOAD, "待装车", true, false);

        assertThat(verdict.status()).isEqualTo(SyncItemStatus.CONFLICT);
        assertThat(verdict.conflictType()).isEqualTo(ConflictType.STOCK_OCCUPIED);
    }

    @Test
    @DisplayName("一切正常 → accepted，目标状态与 01 状态机一致")
    void normalShouldBeAccepted() {
        assertThat(OfflineSyncRule.judge(PdaAction.PICK, "拣货中", true, true).targetStatus()).isEqualTo("已复核");
        assertThat(OfflineSyncRule.judge(PdaAction.LOAD, "待装车", true, true).targetStatus()).isEqualTo("已出库");
        assertThat(OfflineSyncRule.judge(PdaAction.PUTAWAY, "待上架", true, true).targetStatus()).isEqualTo("已入库");
        assertThat(OfflineSyncRule.judge(PdaAction.PICK, "拣货中", true, true).status())
                .isEqualTo(SyncItemStatus.ACCEPTED);
    }

    @Test
    @DisplayName("只允许从紧邻的前置状态推进：跳过复核、打包直接出库必须被拦")
    void onlyAdjacentStatusShouldBeAllowed() {
        // 拣货中 → 已出库 是"跳状态"，装载动作只允许从 待装车 出发
        OfflineSyncRule.Verdict skip = OfflineSyncRule.judge(PdaAction.LOAD, "拣货中", true, true);

        assertThat(skip.status()).as("静默跳状态是离线场景最难查的事故，必须拦").isEqualTo(SyncItemStatus.CONFLICT);
        assertThat(skip.conflictType()).isEqualTo(ConflictType.STATUS_CHANGED);
    }

    @Test
    @DisplayName("PDA 出队语义：只有 accepted/duplicated 不保留本地")
    void keepLocalSemantics() {
        assertThat(SyncItemStatus.ACCEPTED.shouldKeepLocal()).isFalse();
        assertThat(SyncItemStatus.DUPLICATED.shouldKeepLocal()).isFalse();
        assertThat(SyncItemStatus.CONFLICT.shouldKeepLocal()).isTrue();
        assertThat(SyncItemStatus.REJECTED.shouldKeepLocal()).isTrue();
    }

    @Test
    @DisplayName("非法动作抛带码异常")
    void illegalActionShouldThrow() {
        assertThatThrownBy(() -> PdaAction.of("EAT")).hasMessageContaining("非法的补传动作");
        assertThat(PdaAction.of("pick")).isEqualTo(PdaAction.PICK);
    }
}
