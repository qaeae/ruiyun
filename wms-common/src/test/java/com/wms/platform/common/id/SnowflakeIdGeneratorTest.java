package com.wms.platform.common.id;

import com.wms.platform.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 雪花 ID 自检：并发唯一性、位段可解析、参数校验。
 *
 * <p>为什么值得写：主键重复是**数据损坏级**问题（覆盖写入、外键错乱），
 * 且只在并发/时钟回拨时才暴露——正是最难在联调中发现的一类缺陷。
 */
class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("单线程连续生成 1 万个 ID 全部唯一且递增")
    void sequentialIdsShouldBeUniqueAndIncreasing() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        Set<Long> ids = new HashSet<>();
        long previous = 0L;
        for (int i = 0; i < 10_000; i++) {
            long id = generator.nextId();
            assertThat(id).isPositive();
            assertThat(ids.add(id)).as("第 %d 个 ID 重复", i).isTrue();
            assertThat(id).as("同线程内应单调递增").isGreaterThan(previous);
            previous = id;
        }
    }

    @Test
    @DisplayName("4 线程并发生成 2 万个 ID 无重复（同机不同 worker 由调用方保证）")
    void concurrentIdsShouldBeUnique() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        int threads = 4;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<Long>>> tasks = IntStream.range(0, threads)
                    .<Callable<List<Long>>>mapToObj(t -> () ->
                            IntStream.range(0, perThread).mapToObj(i -> generator.nextId()).toList())
                    .toList();
            List<Future<List<Long>>> futures = pool.invokeAll(tasks);
            Set<Long> all = Collections.synchronizedSet(new HashSet<>());
            int total = 0;
            for (Future<List<Long>> future : futures) {
                for (Long id : future.get()) {
                    assertThat(all.add(id)).as("并发下 ID 重复: %d", id).isTrue();
                    total++;
                }
            }
            assertThat(all).hasSize(total).hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("可从 ID 反解生成时间，便于排障定位写入时刻")
    void timestampShouldBeDecodable() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0);
        Instant before = Instant.now().minusSeconds(1);
        long id = generator.nextId();
        Instant decoded = SnowflakeIdGenerator.timestampOf(id);
        assertThat(decoded).isAfter(before);
        assertThat(Duration.between(decoded, Instant.now())).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("不同 worker/datacenter 组合生成互不相同的 ID")
    void differentWorkerProduceDifferentIds() {
        SnowflakeIdGenerator a = new SnowflakeIdGenerator(1, 1);
        SnowflakeIdGenerator b = new SnowflakeIdGenerator(1, 2);
        assertThat(a.nextId()).isNotEqualTo(b.nextId());
    }

    @Test
    @DisplayName("非法的 worker/datacenter 入参在构造期即失败（B5：抛带状态码的业务异常）")
    void invalidWorkerShouldFailFast() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1, 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("datacenterId");
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, 32))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("workerId");
    }
}
