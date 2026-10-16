package com.wms.platform.common.idempotent;

import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.idempotent.mapper.IdempotentMapper;
import com.wms.platform.common.idempotent.po.IdempotentPo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 {@code pf_idempotent} 的幂等实现（{@code 03-接口定义.md} §1.3）。
 *
 * <p>规范落地：
 * <ul>
 *   <li><b>B1</b>：本类不出现 {@code Wrappers}/{@code LambdaQueryWrapper}，SQL 全部收口在
 *       {@link IdempotentMapper} 的 {@code default} 方法里；</li>
 *   <li><b>B4</b>：记录构造走静态工厂 {@link IdempotentPo#of}；</li>
 *   <li><b>B7</b>：方法体内逐条编号步骤注释；</li>
 *   <li><b>B9</b>：{@code @RequiredArgsConstructor} + {@code private final} 构造注入。</li>
 * </ul>
 *
 * <p>关键取舍：
 * <ul>
 *   <li>「处理中」记录用**独立事务**写入（{@link Propagation#REQUIRES_NEW}），保证业务事务回滚后
 *       幂等占位仍然存在（否则并发重入会双写）；</li>
 *   <li>并发重入靠**唯一键**兜底：插入撞 {@link DuplicateKeyException} 时回读已有记录再裁决，
 *       不抛错（避免把正常并发变成 500）；</li>
 *   <li>TTL 7 天：{@code expire_at} 由定时任务清理，避免表无限增长（`01` §7 归档策略）。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class DbIdempotentService implements IdempotentService {

    /** 幂等记录存活天数（仅本类实现使用，按 B6 留在类内，不外提）。 */
    private static final int TTL_DAYS = 7;

    /** 幂等记录 Mapper（SQL 收口处）。 */
    private final IdempotentMapper idempotentMapper;

    /** 雪花 ID 生成器。 */
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Decision begin(String idemKey, String bizType, String requestHash) {
        // 1. 取当前租户：幂等维度是 (tenantId, idemKey)，一期租户固定为 1
        long tenantId = TenantContext.tenantId();

        // 2. 先查是否已存在同键记录（正常重放走这条快路径）
        IdempotentPo existing = idempotentMapper.selectByKey(tenantId, idemKey);
        if (existing != null) {
            // 2.1 已有记录：按"请求体是否一致 + 当前状态"裁决，不再插库
            return evaluate(existing, requestHash);
        }

        // 3. 无记录：以静态工厂建「处理中」占位（B4），独立事务保证业务回滚后占位仍存在
        IdempotentPo po = IdempotentPo.of(
                idGenerator.nextId(), tenantId, idemKey, bizType, requestHash,
                LocalDateTime.now().plusDays(TTL_DAYS), TenantContext.userId());
        try {
            idempotentMapper.insert(po);
            // 3.1 插入成功 = 首个请求，交由调用方执行业务
            return Decision.PROCEED;
        } catch (DuplicateKeyException ex) {
            // 3.2 撞唯一键 = 多实例并发（或同实例重入）：回读胜出者的记录再裁决，不把并发变成 500
            log.debug("幂等键并发写入，转为回读裁决 idemKey={}", idemKey);
            IdempotentPo winner = idempotentMapper.selectByKey(tenantId, idemKey);
            return winner == null ? Decision.IN_PROGRESS : evaluate(winner, requestHash);
        }
    }

    @Override
    public void complete(String idemKey, String responseJson) {
        // 1. 缓存首次成功响应，状态置「成功」——后续重放直接返回该响应体
        // 2. 更新未命中属正常情况（记录可能已被 TTL 任务清理），仅告警不抛错
        int affected = idempotentMapper.updateStatus(
                TenantContext.tenantId(), idemKey, IdempotentPo.STATUS_SUCCESS, responseJson);
        if (affected == 0) {
            log.warn("幂等记录更新未命中 status={} idemKey={}（可能已被 TTL 清理）",
                    IdempotentPo.STATUS_SUCCESS, idemKey);
        }
    }

    @Override
    public void fail(String idemKey) {
        // 1. 置「失败」；不动 responseJson，便于排障时看到残留响应
        idempotentMapper.updateStatus(TenantContext.tenantId(), idemKey, IdempotentPo.STATUS_FAILED, null);
    }

    @Override
    public Optional<String> cachedResponse(String idemKey) {
        // 1. 回读记录
        IdempotentPo po = idempotentMapper.selectByKey(TenantContext.tenantId(), idemKey);
        // 2. 抽出响应缓存（可能为空：失败或处理中）
        return po == null ? Optional.empty() : Optional.ofNullable(po.getResponseJson());
    }

    /**
     * 裁决已有记录：请求体不一致 → 冲突；成功 → 重放；失败 → 允许复用重试；其余 → 处理中。
     *
     * @param po          已有记录
     * @param requestHash 本次请求体规范化哈希
     * @return 裁决结果
     */
    private Decision evaluate(IdempotentPo po, String requestHash) {
        // 1. 请求体哈希不一致：调用方用错了幂等键，必须显式报冲突而不是静默返回旧结果
        if (!Objects.equals(po.getRequestHash(), requestHash)) {
            return Decision.MISMATCH;
        }
        // 2. 已成功：直接重放首次响应
        if (IdempotentPo.STATUS_SUCCESS.equals(po.getStatus())) {
            return Decision.REPLAY;
        }
        // 3. 曾失败：允许调用方复用同一幂等键重试，重置为处理中
        if (IdempotentPo.STATUS_FAILED.equals(po.getStatus())) {
            idempotentMapper.resetToProcessing(po.getTenantId(), po.getIdemKey());
            return Decision.PROCEED;
        }
        // 4. 仍有请求在处理中：调用方稍后重试
        return Decision.IN_PROGRESS;
    }
}
