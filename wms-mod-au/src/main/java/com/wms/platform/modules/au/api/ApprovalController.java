package com.wms.platform.modules.au.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.modules.au.api.ro.ApprovalRejectRo;
import com.wms.platform.modules.au.api.ro.ApprovalSubmitRo;
import com.wms.platform.modules.au.api.vo.ApprovalVo;
import com.wms.platform.modules.au.app.ApprovalService;
import com.wms.platform.common.approval.ApprovalBizType;
import com.wms.platform.common.security.PermissionCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 敏感操作双人复核接口（03 API-AUTH-04/05）。
 *
 * <p><b>权限分权（不相容职责）</b>：
 * <ul>
 *   <li>{@code auth:approval:apply}：发起复核申请；</li>
 *   <li>{@code auth:approval:review}：复核他人申请。</li>
 * </ul>
 * 两个权限**分开授予**是复核制度的前提：如果一个人既有发起权又有复核权，
 * 制度上"必须两个人"就变成了"技术上一个人也能做完"。
 * 服务层还会再挡一次（复核人 ≠ 发起人，{@code 80002}）——**双重保险**，因为权限可能被配错。
 */
@Validated
@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    /** 复核服务。 */
    private final ApprovalService approvalService;

    /**
     * 提交复核申请。
     *
     * @param ro 入参
     * @return 审批单号
     */
    @RequiresPermission(PermissionCodes.APPROVAL_APPLY)
    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@Valid @RequestBody ApprovalSubmitRo ro) {
        String approvalNo = approvalService.submit(ApprovalBizType.of(ro.bizType()), ro.bizId(), ro.bizNo(),
                ro.warehouseId(), ro.merchantId(), ro.amount(), ro.payloadJson(), ro.reason());
        return ApiResponse.ok(Map.of("approvalNo", approvalNo));
    }

    /**
     * 待复核列表（复核人的工作台）。
     *
     * @param bizType 业务类型（可空）
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.APPROVAL_REVIEW)
    @GetMapping("/pending")
    public ApiResponse<List<ApprovalVo>> pending(@RequestParam(required = false) String bizType) {
        return ApiResponse.ok(approvalService.pending(bizType));
    }

    /**
     * 我发起的申请。
     *
     * @return 列表
     */
    @RequiresPermission(PermissionCodes.APPROVAL_APPLY)
    @GetMapping("/mine")
    public ApiResponse<List<ApprovalVo>> mine() {
        return ApiResponse.ok(approvalService.myApplications(TenantContext.userId()));
    }

    /**
     * 复核详情（含内容快照，复核人据此判断）。
     *
     * @param id 审批单 ID
     * @return 详情
     */
    @RequiresPermission(PermissionCodes.APPROVAL_REVIEW)
    @GetMapping("/{id}")
    public ApiResponse<ApprovalVo> detail(@PathVariable long id) {
        return ApiResponse.ok(approvalService.detail(id));
    }

    /**
     * 复核通过（**复核人取自令牌身份；必须 ≠ 发起人**）。
     *
     * @param id 审批单 ID
     * @return 审批单号
     */
    @RequiresPermission(PermissionCodes.APPROVAL_REVIEW)
    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable long id) {
        return ApiResponse.ok(Map.of("approvalNo", approvalService.approve(id, TenantContext.userId())));
    }

    /**
     * 驳回（须填原因）。
     *
     * @param id 审批单 ID
     * @param ro 入参
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.APPROVAL_REVIEW)
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable long id, @Valid @RequestBody ApprovalRejectRo ro) {
        approvalService.reject(id, ro.reason(), TenantContext.userId());
        return ApiResponse.ok();
    }

    /**
     * 撤回（仅发起人本人）。
     *
     * @param id 审批单 ID
     * @return 操作结果
     */
    @RequiresPermission(PermissionCodes.APPROVAL_APPLY)
    @PostMapping("/{id}/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable long id) {
        approvalService.withdraw(id, TenantContext.userId());
        return ApiResponse.ok();
    }
}
