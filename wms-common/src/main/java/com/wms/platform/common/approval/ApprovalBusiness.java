package com.wms.platform.common.approval;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审批执行方注册表：按业务类型找到对应的 {@link ApprovalActionHandler}。
 *
 * <p>从 au 的 Service 里抽出来，是因为它现在有两个使用方：
 * <b>进程内执行</b>（{@link com.wms.platform.common.approval.exec.InProcessApprovalExecutor}）与
 * <b>MQ 消费执行</b>（{@code ApprovalEventConsumer}）。放在共享公共层后，
 * 两边用同一份查找逻辑，不会出现"进程内能找到、消费时找不到"的不一致。
 */
@Component
@RequiredArgsConstructor
public class ApprovalBusiness {

    /** 所有执行方（由 iv/fns 等模块提供实现，Spring 自动注入）。 */
    private final List<ApprovalActionHandler> handlers;

    /**
     * 已登记的执行方数量（启动自检用：消费方必须至少有一个执行器，否则不该进队列竞争组）。
     *
     * @return 数量
     */
    public int handlerCount() {
        return handlers == null ? 0 : handlers.size();
    }

    /**
     * 按业务类型查找执行方。
     *
     * @param bizType 业务类型
     * @return 执行方
     * @throws BizException 未登记执行方时抛 {@code 80007}
     */
    public ApprovalActionHandler findHandler(ApprovalBizType bizType) {
        for (ApprovalActionHandler handler : handlers) {
            if (handler.supportType() == bizType) {
                return handler;
            }
        }
        throw new BizException(ErrorCode.APPROVAL_NO_HANDLER,
                "业务类型「" + bizType.getLabel() + "」尚未接入执行处理器，不能复核通过"
                        + "（否则会出现「审批已通过但什么都没发生」的空批单）");
    }
}
