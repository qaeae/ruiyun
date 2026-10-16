package com.wms.platform.modules.ops.domain;

import com.wms.platform.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务租约规则测试（01 §3.17）。
 *
 * <p>重点覆盖"租约什么时候可被抢占"——这是多实例正确性的核心判定，
 * 判错了的后果是"两个实例同时跑"（数据被改两遍）或"谁都不跑"（任务静默停摆）。
 */
@DisplayName("任务租约规则（JobLeaseRule）")
class JobLeaseRuleTest {

    @Test
    @DisplayName("租约到期时间：默认 10 分钟；传入非正数时回落到默认值（避免配成 0 导致锁瞬间失效）")
    void leaseUntil() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 10, 0);

        assertThat(JobLeaseRule.leaseUntil(now, 10)).isEqualTo(now.plusMinutes(10));
        assertThat(JobLeaseRule.leaseUntil(now, 0))
                .as("时长 0 会被当成'不需要锁'，必须回落到默认值").isEqualTo(now.plusMinutes(10));
        assertThat(JobLeaseRule.leaseUntil(now, -5)).isEqualTo(now.plusMinutes(10));
    }

    @Test
    @DisplayName("可抢占判定：无租约 / 已过期 → 可抢；仍在租期内 → 不可抢")
    void leaseAvailability() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 10, 0);

        assertThat(JobLeaseRule.isLeaseAvailable(null, now)).as("从未执行过").isTrue();
        assertThat(JobLeaseRule.isLeaseAvailable(now.minusMinutes(1), now)).as("已过期（实例崩溃场景）").isTrue();
        assertThat(JobLeaseRule.isLeaseAvailable(now.plusMinutes(5), now)).as("仍在租期内").isFalse();
        assertThat(JobLeaseRule.isLeaseAvailable(now, now))
                .as("边界：等于当前时刻仍不可抢（与 SQL 条件 lease_until < NOW(3) 保持一致，避免两处口径不一）")
                .isFalse();
    }

    @Test
    @DisplayName("owner 标识：instanceId 缺失时用 unknown 兜底（不能返回空，否则 SQL 条件失效）")
    void ownerFormat() {
        assertThat(JobLeaseRule.owner("wms-host-1", 42L)).isEqualTo("wms-host-1:42");
        assertThat(JobLeaseRule.owner(null, 42L)).isEqualTo("unknown:42");
        assertThat(JobLeaseRule.owner("  ", 7L)).isEqualTo("unknown:7");
    }

    @Test
    @DisplayName("任务名白名单：未知任务名拒绝（任务名是租约的唯一键，写错会导致锁隔离失效）")
    void jobNameWhitelist() {
        assertThat(JobName.of("FALLBACK_SWEEP")).isEqualTo(JobName.FALLBACK_SWEEP);
        assertThat(JobName.of("daily_reconcile")).isEqualTo(JobName.DAILY_RECONCILE);
        assertThat(JobName.FALLBACK_SWEEP.code()).isEqualTo("FALLBACK_SWEEP");
        assertThat(JobName.FALLBACK_SWEEP.getDisplayName()).isEqualTo("兜底扣减扫描");

        assertThatThrownBy(() -> JobName.of("fallbackSweep"))
                .as("大小写与下划线差异会被当成另一个任务 → 两把锁 → 多实例防护失效")
                .isInstanceOf(BizException.class).hasMessageContaining("未知任务名");
        assertThatThrownBy(() -> JobName.of(null)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("执行结果三态：跳过不等于失败（否则多实例下每周期一半告警，运维会去修不存在的故障）")
    void runStatusSemantics() {
        assertThat(JobRunStatus.SUCCESS.getLabel()).isEqualTo("成功");
        assertThat(JobRunStatus.FAILED.getLabel()).isEqualTo("失败");
        assertThat(JobRunStatus.SKIPPED.getLabel()).isEqualTo("跳过");
        assertThat(JobRunStatus.values()).hasSize(3);
    }
}
