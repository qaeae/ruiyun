package com.wms.platform.modules.acct.app;

import com.wms.platform.modules.acct.domain.FeeBizType;

import java.math.BigDecimal;

/**
 * 费用冻结应用服务（本轮最小面：冻结 / 释放 / 查可用余额）。
 *
 * <p><b>为什么返回结果对象而不是抛异常</b>：余额不足是**可预期的业务分支**（03 §1.2 的
 * {@code 20001 余额不足，已拦截下单}），而不是系统故障。由资金域返回"冻不动 + 当前可用额"，
 * 让调用方（出库域）在**自己的边界**决定对外错误码与文案——这样同一个资金能力用于入库、操作费、
 * 仓储费时不会被某个调用方的错误码绑死。
 *
 * <p><b>本轮范围</b>：只有冻结与释放。扣减（冻结转扣减 + 差额释放）、充值、通道适配、红冲、
 * 退款属 M3 轮次，表已建好（{@code ac_channel_order} 等），在域内继续扩展即可。
 */
public interface FeeFreezeService {

    /**
     * 冻结费用（幂等：同一 {@code bizType + bizNo} 只冻一次）。
     *
     * @param command 冻结命令
     * @return 冻结结果（成功/可用不足；失败不抛异常，由调用方决定业务响应）
     */
    FreezeResult freeze(FreezeCommand command);

    /**
     * 释放冻结（取消/作废/兜底差额回退用；幂等：重复释放不会重复退额）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @param bizType    费用类型
     * @param bizNo      业务单号
     * @param reason     释放原因（写入流水备注，便于对账追溯）
     * @param operatorId 操作人
     * @return 本次是否真的释放（{@code false} = 冻结单不存在或已释放，调用方可按"已处理"对待）
     */
    boolean release(long tenantId, long merchantId, FeeBizType bizType, String bizNo, String reason, Long operatorId);

    /**
     * 查可用余额（账面余额 − 冻结额）。
     *
     * @param tenantId   租户
     * @param merchantId 商家
     * @return 可用余额（账户不存在返回 0）
     */
    BigDecimal availableAmount(long tenantId, long merchantId);

    /**
     * 冻结命令。
     *
     * @param merchantId      商家 ID
     * @param warehouseId     仓库 ID（可空）
     * @param bizType         费用类型
     * @param bizNo           业务单号（出库单号）
     * @param estimatedAmount 预估价（元，正数）
     * @param bufferRate      缓冲系数（如 0.1000；可空 = 不缓冲）
     * @param doId            出库单 ID（可空）
     * @param shipmentId      装运单 ID（可空）
     * @param operatorId      操作人（可空）
     */
    record FreezeCommand(long merchantId, Long warehouseId, FeeBizType bizType, String bizNo,
                         BigDecimal estimatedAmount, BigDecimal bufferRate, Long doId, Long shipmentId,
                         Long operatorId) {
    }

    /**
     * 冻结结果。
     *
     * @param success        是否冻结成功
     * @param freezeNo       冻结单号（失败为 {@code null}）
     * @param frozenAmount   冻结额（= 预估价 × (1 + 缓冲系数)）
     * @param availableAmount 冻结后可用余额（失败时为当前可用余额，供前端提示差额）
     * @param message        结论说明（中文）
     */
    record FreezeResult(boolean success, String freezeNo, BigDecimal frozenAmount, BigDecimal availableAmount,
                        String message) {

        /**
         * 冻结成功。
         *
         * @param freezeNo        冻结单号
         * @param frozenAmount    冻结额
         * @param availableAmount 冻结后可用余额
         * @return 成功结果
         */
        public static FreezeResult frozen(String freezeNo, BigDecimal frozenAmount, BigDecimal availableAmount) {
            return new FreezeResult(true, freezeNo, frozenAmount, availableAmount, "冻结成功");
        }

        /**
         * 可用余额不足（拦截下单）。
         *
         * @param frozenAmount    本次应冻金额
         * @param availableAmount 当前可用余额
         * @return 失败结果
         */
        public static FreezeResult insufficient(BigDecimal frozenAmount, BigDecimal availableAmount) {
            return new FreezeResult(false, null, frozenAmount, availableAmount,
                    "可用余额不足：需冻结 " + frozenAmount + "，当前可用 " + availableAmount);
        }

        /**
         * 幂等命中（此前已冻结过同一业务对象）。
         *
         * @param freezeNo        原冻结单号
         * @param frozenAmount    原冻结额
         * @param availableAmount 当前可用余额
         * @return 成功结果（幂等语义）
         */
        public static FreezeResult alreadyFrozen(String freezeNo, BigDecimal frozenAmount,
                                                 BigDecimal availableAmount) {
            return new FreezeResult(true, freezeNo, frozenAmount, availableAmount,
                    "该业务对象已冻结过（幂等命中），未重复冻结");
        }
    }
}
