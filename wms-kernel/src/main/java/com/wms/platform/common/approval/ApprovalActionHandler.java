package com.wms.platform.common.approval;


/**
 * 审批执行处理器（**扩展点**：各业务域实现"复核通过后到底做什么"）。
 *
 * <p><b>为什么要有这个扩展点</b>：复核流程（提交/待办/双人校验/留痕/防改内容）对所有业务都一样，
 * 但"通过后执行什么"各不相同（库存调整要改库存、结算下发要动钱、押金退还要动账）。
 * 若把这些差异塞进审批服务里，`au` 域会依赖全部业务域，成为"上帝服务"；
 * 反过来用扩展点，`au` 域只认识一个接口，业务域各自实现——**依赖方向反过来，`au` 不需要认识业务**。
 *
 * <p><b>约定</b>：
 * <ol>
 *   <li>执行必须是**幂等**的：审批单可能因重试被重复执行（`executed` 标记会挡一层，
 *       但网络重试/人工补跑仍可能到达），实现要自备业务幂等键；</li>
 *   <li>执行**必须使用 {@link ApprovalContext#payloadJson()}**（审批内容快照），
 *       不能用实时参数——否则"批的是一份、执行的是另一份"；</li>
 *   <li>执行抛异常 → 整个审批通过操作回滚（**不允许出现"状态已通过但没执行"**）。</li>
 * </ol>
 */
public interface ApprovalActionHandler {

    /**
     * 支持的业务类型。
     *
     * @return 业务类型
     */
    ApprovalBizType supportType();

    /**
     * 执行副作用（在同一事务内，抛异常即整体回滚）。
     *
     * @param context 审批上下文（含内容快照）
     */
    void execute(ApprovalContext context);
}
