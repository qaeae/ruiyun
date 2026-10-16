package com.wms.platform.common.id;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 雪花 ID 生成器（{@code 01-数据模型DDL与状态机.md} §1.1：主键由应用生成，不用自增）。
 *
 * <p>结构（64 bit，正数）：符号位 0 + 41 位毫秒时间戳 + 5 位机房 + 5 位机器 + 12 位序列。
 * <ul>
 *   <li>起始纪元：{@code 2026-01-01T00:00:00+08:00}，可用 69 年</li>
 *   <li>单机每毫秒 4096 个 ID；时间回拨时**自旋等待**而非抛错（仓库现场 NTP 校时常见）</li>
 * </ul>
 *
 * <p>说明：ID 只用于技术主键与内部关联，**对外的可读单号**（DO/SHP/FRZ...）另由单号生成器产出。
 */
public class SnowflakeIdGenerator {

    private static final long EPOCH = LocalDateTime.of(2026, 1, 1, 0, 0, 0)
            .atZone(ZoneId.of("+08:00")).toInstant().toEpochMilli();

    private static final int WORKER_BITS = 5;
    private static final int DATACENTER_BITS = 5;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;
    private static final long MAX_DATACENTER = (1L << DATACENTER_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    private static final int DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS;

    private final long workerId;
    private final long datacenterId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        // 1. 参数校验：非法 worker/datacenter 必须在构造期失败（避免运行期才出现重复 ID）
        //    B5：抛带状态码的业务异常，不用无状态码的 IllegalArgumentException
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER) {
            throw new BizException(ErrorCode.PARAM_ERROR, "datacenterId 必须落在 [0, " + MAX_DATACENTER + "]");
        }
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new BizException(ErrorCode.PARAM_ERROR, "workerId 必须落在 [0, " + MAX_WORKER + "]");
        }
        // 2. 赋值为不可变字段
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        // 1. 取当前毫秒
        long timestamp = currentMillis();
        // 2. 时间回拨处理：小幅回拨自旋等待追上；超过 5 秒直接拒绝，避免生成重复 ID
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > 5000L) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "时钟回拨超过 5 秒，拒绝生成 ID，offset=" + offset);
            }
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_SHIFT)
                | (workerId << (long) SEQUENCE_BITS)
                | sequence;
    }

    /** 从 ID 反解生成时间，便于排障（如"这条记录是哪台机器什么时候写的"）。 */
    public static Instant timestampOf(long id) {
        return Instant.ofEpochMilli((id >> TIMESTAMP_SHIFT) + EPOCH);
    }

    private long waitUntil(long targetMillis) {
        long timestamp = currentMillis();
        while (timestamp < targetMillis) {
            Thread.onSpinWait();
            timestamp = currentMillis();
        }
        return timestamp;
    }

    private long currentMillis() {
        return System.currentTimeMillis();
    }
}
