package com.wms.platform.modules.fns.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.modules.fns.app.remote.ApprovalSubmitClient;
import com.wms.platform.common.approval.ApprovalBizType;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.modules.fns.api.ro.AdvanceRegisterRo;
import com.wms.platform.modules.fns.api.vo.AdvanceLimitVo;
import com.wms.platform.modules.fns.api.vo.AdvanceVo;
import com.wms.platform.modules.fns.api.vo.FallbackCandidateVo;
import com.wms.platform.modules.fns.api.vo.SettleBatchVo;
import com.wms.platform.modules.fns.api.dto.ChannelReconcileFile;
import com.wms.platform.modules.fns.api.vo.DailyReconciliationVo;
import com.wms.platform.modules.fns.app.AdvancePaymentService;
import com.wms.platform.modules.fns.app.DailyReconciliationService;
import com.wms.platform.modules.fns.app.FallbackDeductionService;
import com.wms.platform.modules.fns.app.SettlementService;
import com.wms.platform.modules.fns.domain.FallbackDeductionRule;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 结算与垫付接口（03 API-FNS-03/04/05/06）。
 *
 * <p><b>权限分权（不相容职责）</b>：
 * <ul>
 *   <li>{@code finance:settle:manage}：生成批次、触发兜底扫描、登记/核销垫付；</li>
 *   <li>{@code finance:settle:dispatch}：**下发支付指令**（真正动钱的动作，单独一个权限）。
 *       为什么单独拆出来：能"准备付款"与能"按下付款按钮"应该是两个人——
 *       财务主管审批了金额，出纳才能下指令，这是最基础的资金内控。</li>
 * </ul>
 */
@Validated
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class FinanceSettleController {

    /** 结算服务。 */
    private final SettlementService settlementService;

    /** 兜底扣减服务。 */
    private final FallbackDeductionService fallbackDeductionService;

    /** 垫付服务。 */
    private final AdvancePaymentService advancePaymentService;

    /** 日终勾稽服务。 */
    private final DailyReconciliationService dailyReconciliationService;

    /** 双人复核服务（结算下发为复核矩阵项，入口层强制）。 */
    private final ApprovalSubmitClient approvalSubmitClient;

    // ===================== 结算批次 =====================

    /**
     * 按对账单生成结算批次（要求对账单已确认）。
     *
     * @param rclNo 对账单号
     * @return 批次号
     */
    @RequiresPermission(PermissionCodes.FINANCE_SETTLE_MANAGE)
    @PostMapping("/settle-batches")
    public ApiResponse<Map<String, Object>> generateBatch(@RequestParam String rclNo) {
        return ApiResponse.ok(Map.of("stbNo", settlementService.generate(rclNo)));
    }

    /**
     * 下发支付指令（**真正动钱**：独立权限）。
     *
     * @param stbNo 批次号
     * @return 机构侧指令号
     */
    @RequiresPermission(PermissionCodes.FINANCE_SETTLE_DISPATCH)
    @PostMapping("/settle-batches/{stbNo}/dispatch")
    public ApiResponse<Map<String, Object>> dispatch(@PathVariable String stbNo) {
        // ★ 入口层强制双人复核（02 M2-05 复核矩阵项「结算下发」= 真正动钱的动作）：
        //   不直接下发，而是登记复核单；复核通过后由 SettleDispatchApprovalHandler 依快照执行。
        SettleBatchVo batch = settlementService.detail(stbNo);
        // bizId 传 null（批次号就是业务对象标识）：
        //   **不能传占位值 0**——0 是同一个值，所有批次会被当成同一个业务对象，
        //   第二次下发直接报 80003（联调实测踩到）。判重由 bizNo=stbNo 承担（uk_approval_biz_no）。
        String approvalNo = approvalSubmitClient.submit(ApprovalBizType.SETTLE_DISPATCH, null,
                stbNo, null, null, batch.totalAmount(),
                "{\"stbNo\":\"" + stbNo + "\"}",
                "结算下发 承运商=" + batch.carrierCode() + " 账期=" + batch.period()
                        + " 金额=" + batch.totalAmount());
        return ApiResponse.ok(Map.of("approvalNo", approvalNo,
                "message", "已提交双人复核，复核通过后下发支付指令"));
    }

    /**
     * 结算批次列表。
     *
     * @param carrierCode 承运商（可空）
     * @param period      账期（可空）
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.FINANCE_QUERY)
    @GetMapping("/settle-batches")
    public ApiResponse<List<SettleBatchVo>> listBatches(@RequestParam(required = false) String carrierCode,
                                                        @RequestParam(required = false) String period) {
        return ApiResponse.ok(settlementService.list(carrierCode, period));
    }

    /**
     * 结算批次详情。
     *
     * @param stbNo 批次号
     * @return 详情
     */
    @RequiresPermission(PermissionCodes.FINANCE_QUERY)
    @GetMapping("/settle-batches/{stbNo}")
    public ApiResponse<SettleBatchVo> batchDetail(@PathVariable String stbNo) {
        return ApiResponse.ok(settlementService.detail(stbNo));
    }

    // ===================== 兜底扣减 =====================

    /**
     * 触发兜底扣减扫描（幂等，可重复执行；定时任务与人工补跑走同一入口）。
     *
     * @param now 当前时间（可空 = 系统时间；补跑历史时传入）
     * @return 候选清单
     */
    @RequiresPermission(PermissionCodes.FINANCE_SETTLE_MANAGE)
    @PostMapping("/fallback-deductions/sweep")
    public ApiResponse<List<FallbackCandidateVo>> sweepFallback(
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime now) {
        return ApiResponse.ok(fallbackDeductionService.sweep(now == null ? LocalDateTime.now() : now));
    }

    /**
     * 承运商回传实际运费后结算（多退少补）。
     *
     * @param waybillNo 运单号
     * @param actualFee 实际运费
     * @return 调整结论
     */
    @RequiresPermission(PermissionCodes.FINANCE_SETTLE_MANAGE)
    @PostMapping("/fallback-deductions/{waybillNo}/settle")
    public ApiResponse<FallbackDeductionRule.Adjustment> settleLateReturn(@PathVariable String waybillNo,
                                                                        @RequestParam BigDecimal actualFee) {
        return ApiResponse.ok(fallbackDeductionService.settleLateReturn(waybillNo, actualFee));
    }

    // ===================== 日终勾稽 =====================

    /**
     * 执行某天四层勾稽（资金/业务/三方/库存；幂等，可重跑）。
     *
     * @param bizDate 业务日期（yyyy-MM-dd）
     * @param file    通道/承运商侧对账数据（可空 → 相关层为「待接入」）
     * @return 四层结果
     */
    @RequiresPermission(PermissionCodes.FINANCE_RECONCILE_MANAGE)
    @PostMapping("/daily-reconciliation/run")
    public ApiResponse<List<DailyReconciliationVo>> runDailyReconciliation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestBody(required = false) ChannelReconcileFile file) {
        return ApiResponse.ok(dailyReconciliationService.run(bizDate, file));
    }

    /**
     * 查询某天勾稽结果。
     *
     * @param bizDate 业务日期
     * @return 四层结果
     */
    @RequiresPermission(PermissionCodes.FINANCE_QUERY)
    @GetMapping("/daily-reconciliation")
    public ApiResponse<List<DailyReconciliationVo>> queryDailyReconciliation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(dailyReconciliationService.query(bizDate));
    }

    /**
     * 标记某层差异已处理（须填说明）。
     *
     * @param id     记录 ID
     * @param remark 处理说明
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.FINANCE_RECONCILE_MANAGE)
    @PostMapping("/daily-reconciliation/{id}/handle")
    public ApiResponse<Void> handleDailyReconciliation(@PathVariable long id, @RequestParam String remark) {
        dailyReconciliationService.handle(id, remark);
        return ApiResponse.ok();
    }

    // ===================== 垫付台账 =====================

    /**
     * 登记垫付（同运单幂等；超限拦截转人工）。
     *
     * @param ro 入参
     * @return 运单号
     */
    @RequiresPermission(PermissionCodes.FINANCE_SETTLE_MANAGE)
    @PostMapping("/advance-payments")
    public ApiResponse<Map<String, Object>> registerAdvance(@Valid @RequestBody AdvanceRegisterRo ro) {
        return ApiResponse.ok(Map.of("waybillNo", advancePaymentService.register(ro)));
    }

    /**
     * 限额预检（下单前询问"该商家还能不能垫"）。
     *
     * @param merchantId 商家
     * @return 预检结果
     */
    @RequiresPermission(PermissionCodes.FINANCE_QUERY)
    @GetMapping("/advance-payments/limit")
    public ApiResponse<AdvanceLimitVo> checkAdvanceLimit(@RequestParam long merchantId) {
        return ApiResponse.ok(advancePaymentService.checkLimit(merchantId));
    }

    /**
     * 核销垫付。
     *
     * @param waybillNo 运单号
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.FINANCE_SETTLE_MANAGE)
    @PostMapping("/advance-payments/{waybillNo}/clear")
    public ApiResponse<Void> clearAdvance(@PathVariable String waybillNo) {
        advancePaymentService.clear(waybillNo);
        return ApiResponse.ok();
    }

    /**
     * 垫付台账查询。
     *
     * @param merchantId 商家（可空）
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.FINANCE_QUERY)
    @GetMapping("/advance-payments")
    public ApiResponse<List<AdvanceVo>> listAdvances(@RequestParam(required = false) Long merchantId) {
        return ApiResponse.ok(advancePaymentService.list(merchantId));
    }
}
