package com.wms.platform.modules.outbound.app.remote;

import java.math.BigDecimal;

/**
 * 费用冻结端口（越界依赖的**唯一出口之一**：出库 → 资金）。
 *
 * <p><b>为什么要有这个端口</b>：{@code 03} API-OUT-01 的前置条件是"余额足 + 冻结成功"。
 * 本轮 acct 与出库同进程，直接注入 {@code FeeFreezeService} 也能跑；但按
 * {@code 11-微服务拆分方案（分期）.md} 的资金域最终会独立部署，届时只要把适配实现从
 * "本地调用"换成"HTTP 调 wms-fns/acct"，应用服务一行都不用改。
 *
 * <p>端口自带请求/结果类型（不复用 acct 的 DTO）：这样才能保证换成远程实现时不需要把
 * acct 的领域类型引入出库模块——跨域只交换"词汇表"，不交换实现。
 */
public interface FeeFreezePort {

    /**
     * 冻结物流费。
     *
     * @param request 冻结请求
     * @return 冻结结果（余额不足是**业务结果**，不抛异常，由出库在自己的边界转 20001）
     */
    FreezeOutcome freeze(FeeFreezeRequest request);

    /**
     * 释放冻结（取消/作废/拦截时全额释放）。
     *
     * @param merchantId 商家
     * @param bizNo      业务单号（出库单号）
     * @param reason     释放原因
     * @param operatorId 操作人
     * @return 本次是否真的释放
     */
    boolean release(long merchantId, String bizNo, String reason, Long operatorId);

    /**
     * 冻结请求。
     *
     * @param merchantId      商家
     * @param warehouseId     仓库（可空）
     * @param bizNo           业务单号（出库单号）
     * @param estimatedAmount 预估价（元，正数）
     * @param bufferRate      缓冲系数（可空 = 不缓冲）
     * @param doId            出库单 ID（可空，便于资金侧按单检索）
     * @param operatorId      操作人（可空）
     */
    record FeeFreezeRequest(long merchantId, Long warehouseId, String bizNo, BigDecimal estimatedAmount,
                            BigDecimal bufferRate, Long doId, Long operatorId) {
    }

    /**
     * 冻结结果。
     *
     * @param success         是否成功
     * @param freezeNo        冻结单号（失败为 {@code null}）
     * @param frozenAmount    冻结额
     * @param availableAmount 冻结后可用余额（失败时为当前可用额）
     * @param message         说明
     */
    record FreezeOutcome(boolean success, String freezeNo, BigDecimal frozenAmount, BigDecimal availableAmount,
                         String message) {
    }
}
