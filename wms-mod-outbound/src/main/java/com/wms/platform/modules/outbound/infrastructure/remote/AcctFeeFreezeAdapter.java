package com.wms.platform.modules.outbound.infrastructure.remote;

import com.wms.platform.modules.acct.app.FeeFreezeService;
import com.wms.platform.modules.acct.domain.FeeBizType;
import com.wms.platform.modules.outbound.app.remote.FeeFreezePort;

import java.math.BigDecimal;

/**
 * 费用冻结端口的本地适配实现（出库 → acct，同进程直调）。
 *
 * <p>这是"端口 → 实现"的**唯一替换点**：资金域独立部署后，只需把本类换成
 * "HTTP 调 wms-acct" 的实现，出库应用服务与领域规则都不用动
 * （{@code 11-微服务拆分方案（分期）.md} §4.2 的跨服务交互规则）。
 *
 * <p>由 {@code OutboundConfig} 显式注册为 Bean（而不是加 {@code @Component}）：
 * 让"越界依赖一共就这几处"在配置类里一眼可见，评审时不必满仓库找。
 */
public class AcctFeeFreezeAdapter implements FeeFreezePort {

    /** 资金域费用冻结服务。 */
    private final FeeFreezeService feeFreezeService;

    public AcctFeeFreezeAdapter(FeeFreezeService feeFreezeService) {
        this.feeFreezeService = feeFreezeService;
    }

    @Override
    public FreezeOutcome freeze(FeeFreezeRequest request) {
        // 1. 出库的物流费冻结固定用 LOGISTICS 类型（同一 DO 只冻一次由 uk_freeze_biz 兜底）
        FeeFreezeService.FreezeResult result = feeFreezeService.freeze(new FeeFreezeService.FreezeCommand(
                request.merchantId(), request.warehouseId(), FeeBizType.LOGISTICS, request.bizNo(),
                request.estimatedAmount(), request.bufferRate(), request.doId(), null, request.operatorId()));
        // 2. 结果语义一一对应（资金侧不抛异常，错误码在出库边界决定）
        return new FreezeOutcome(result.success(), result.freezeNo(), result.frozenAmount(),
                result.availableAmount(), result.message());
    }

    @Override
    public boolean release(long merchantId, String bizNo, String reason, Long operatorId) {
        return feeFreezeService.release(com.wms.platform.common.context.TenantContext.tenantId(), merchantId,
                FeeBizType.LOGISTICS, bizNo, reason, operatorId);
    }

    /**
     * 查询可用余额（出库建单失败时给前端提示差额；不在端口上，仅本域内部用）。
     *
     * @param merchantId 商家
     * @return 可用余额
     */
    public BigDecimal availableAmount(long merchantId) {
        return feeFreezeService.availableAmount(com.wms.platform.common.context.TenantContext.tenantId(),
                merchantId);
    }
}
