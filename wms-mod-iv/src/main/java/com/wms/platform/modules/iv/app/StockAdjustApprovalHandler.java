package com.wms.platform.modules.iv.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.approval.ApprovalActionHandler;
import com.wms.platform.common.approval.ApprovalContext;
import com.wms.platform.common.approval.ApprovalBizType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 库存调整的复核执行器（{@code 02} M2-05 复核矩阵项「库存调整」）。
 *
 * <p><b>为什么库存调整必须在复核矩阵里</b>：调整是"账实差异合法化"的直接手段之一
 * （另一条是盘点）。能单人改库存的人，等于能把货凭空消掉且不留下任何"不平等"的痕迹——
 * 因为调整本来就会造成不等。
 *
 * <p><b>执行时只用审批内容快照</b>（{@link ApprovalContext#payloadJson()}），
 * 不用执行时的实时参数：否则"批的是 −5 件、执行的是 −5000 件"。
 * 快照由提交方（接口层）序列化，字段口径见 {@code StockAdjustPayload}。
 *
 * <p>幂等：调整走的 {@code StockOpCommand.idemKey} 取自快照（{@code APPROVAL:{审批单号}}），
 * 因此即便审批被执行两次，库存也只动一次（`iv_movement.uk_movement_idem` 兜底）。
 */
@Component
@RequiredArgsConstructor
public class StockAdjustApprovalHandler implements ApprovalActionHandler {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(StockAdjustApprovalHandler.class);

    /** 库存服务。 */
    private final InventoryService inventoryService;

    /** JSON 解析。 */
    private final ObjectMapper objectMapper;

    @Override
    public ApprovalBizType supportType() {
        return ApprovalBizType.STOCK_ADJUST;
    }

    @Override
    public void execute(ApprovalContext context) {
        // 1. 解析审批内容快照（执行参数的唯一来源）
        JsonNode payload = parse(context.payloadJson());
        // 2. 组装命令（幂等键含审批单号：同一审批单重复执行不会二次调整库存）
        StockOpCommand command = new StockOpCommand(
                required(payload, "warehouseId").asLong(),
                required(payload, "merchantId").asLong(),
                required(payload, "skuId").asLong(),
                new BigDecimal(required(payload, "qty").asText()),
                "ADJUST",
                context.bizId() == null ? 0L : context.bizId(),
                context.bizNo() == null ? context.approvalNo() : context.bizNo(),
                "APPROVAL:" + context.approvalNo(),
                payload.hasNonNull("locationId") ? payload.get("locationId").asLong() : null,
                context.approverId(),
                null);
        // 3. 执行调整（异常 → 整个审批通过事务回滚，不会出现"已通过但没执行"）
        inventoryService.adjust(command);
        log.warn("库存调整已按复核执行 approvalNo={} skuId={} qty={} 复核人={}", context.approvalNo(),
                command.skuId(), command.qty(), context.approverId());
    }

    /**
     * 解析快照。
     *
     * @param payloadJson 快照
     * @return JSON 节点
     */
    private JsonNode parse(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审批内容快照不是合法 JSON，无法执行：" + payloadJson, ex);
        }
    }

    /**
     * 取必填字段。
     *
     * @param payload JSON 节点
     * @param field   字段名
     * @return 子节点
     */
    private JsonNode required(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "审批内容快照缺少必填字段 " + field + "，无法执行库存调整（快照必须完整记录执行参数）");
        }
        return node;
    }
}
