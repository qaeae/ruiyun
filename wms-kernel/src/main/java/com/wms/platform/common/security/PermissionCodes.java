package com.wms.platform.common.security;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.util.List;

/**
 * 权限码目录（唯一出处）。
 *
 * <p><b>为什么权限码必须先定义再用</b>：权限码是字符串，写错（{@code stock:adjust} 写成
 * {@code stock:adjusts}）不会编译报错，表现是"配了权限但还是 403"——现场只会认为系统坏了。
 * 因此本类：
 * <ol>
 *   <li>收敛全部权限码常量；</li>
 *   <li>提供 {@link #of(String)} 校验，用于「角色授权」接口：**拒绝授予系统中不存在的权限码**，
 *       避免生成一堆永远不生效的僵尸权限。</li>
 * </ol>
 *
 * <p>一期只登记骨架真正会用到的部分（其余按域逐步补齐），已登记的都有对应的接口标注。
 */
public final class PermissionCodes {

    /** 主数据-商家-新增。 */
    public static final String MASTER_MERCHANT_CREATE = "master:merchant:create";

    /** 主数据-仓库/库区/库位-新增。 */
    public static final String MASTER_WAREHOUSE_CREATE = "master:warehouse:create";

    /** 主数据-SKU-新增。 */
    public static final String MASTER_SKU_CREATE = "master:sku:create";

    /** 主数据-箱规-维护。 */
    public static final String MASTER_SKU_UNIT_CONV = "master:sku:unit-conv";

    /** 主数据-启停用（高风险：影响现场可作业范围）。 */
    public static final String MASTER_STATUS_CHANGE = "master:status:change";

    /** 主数据-查询。 */
    public static final String MASTER_QUERY = "master:query";

    /** 设备档案-维护。 */
    public static final String DEVICE_MANAGE = "master:device:manage";

    /** 库存-查询。 */
    public static final String STOCK_QUERY = "stock:query";

    /** 库存-预占/释放（单据流程内部调用）。 */
    public static final String STOCK_RESERVE = "stock:reserve";

    /** 库存-调整（盘点/差异处理，最高风险）。 */
    public static final String STOCK_ADJUST = "stock:adjust";

    /** 库存-移库。 */
    public static final String STOCK_MOVE = "stock:move";

    /** 库存-不变量校验（日终任务与财务）。 */
    public static final String STOCK_INVARIANT_CHECK = "stock:invariant:check";

    /** 财务-对账管理（生成/差异判定/确认，直接触发后续付款）。 */
    public static final String FINANCE_RECONCILE_MANAGE = "finance:reconcile:manage";

    /** 财务-结算管理（生成批次 / 兜底扫描 / 垫付登记核销）。 */
    public static final String FINANCE_SETTLE_MANAGE = "finance:settle:manage";

    /** 财务-下发支付指令（**真正动钱**，与"准备付款"分离）。 */
    public static final String FINANCE_SETTLE_DISPATCH = "finance:settle:dispatch";

    /** 财务-查询（对账单/账单/结算批次/垫付）。 */
    public static final String FINANCE_QUERY = "finance:query";

    /** 权限-角色与授权管理。 */
    public static final String AUTH_ROLE_MANAGE = "auth:role:manage";

    /** 权限-仓库数据授权。 */
    public static final String AUTH_WAREHOUSE_GRANT = "auth:warehouse:grant";

    /** 权限-用户管理。 */
    public static final String AUTH_USER_MANAGE = "auth:user:manage";

    /** 审计日志查询。 */
    public static final String AUTH_AUDIT_QUERY = "auth:audit:query";

    /** 运维-任务查询。 */
    public static final String OPS_JOB_QUERY = "ops:job:query";

    /** 运维-任务补跑与启停（会触发真实业务动作）。 */
    public static final String OPS_JOB_RUN = "ops:job:run";

    /** 复核-发起申请（谁都能发起，但发起人不能自批）。 */
    public static final String APPROVAL_APPLY = "auth:approval:apply";

    /** 复核-复核他人申请（**与发起权限分离**：不相容职责）。 */
    public static final String APPROVAL_REVIEW = "auth:approval:review";

    /** 运费试算（`03` API-CS-04）：价目表属资金口径数据，能算价的都能看到成本结构，需授权。 */
    public static final String PRICING_ESTIMATE = "pricing:estimate";

    /** 上传发货单影像（回单/异常照片）：涉及结算举证材料，需授权。 */
    public static final String LOGISTICS_PROOF_UPLOAD = "logistics:proof:upload";

    // ==========================================================================================
    // 出库域（W6 / 03 API-OUT）。按"作业动作"分权，不按"角色"分权——
    // 角色（库管/分拣/装卸工/安全员）是**现场编制**，会随班次与仓库调整；
    // 而"谁能派发、谁能装车"是职责边界，用权限码表达才能把不相容职责真正分开
    // （例如"分拣"不应能直接装车出账，否则可绕过复核与交接两道卡点）。
    // ==========================================================================================

    /** 出库-建单（商家/系统）。 */
    public static final String OUTBOUND_ORDER_CREATE = "outbound:order:create";

    /** 出库-查询与轨迹（全角色按权限）。 */
    public static final String OUTBOUND_ORDER_QUERY = "outbound:order:query";

    /** 出库-派发拣货（库管）。 */
    public static final String OUTBOUND_ORDER_DISPATCH = "outbound:order:dispatch";

    /** 出库-拣货完成/缺货上报（分拣）。 */
    public static final String OUTBOUND_ORDER_PICK = "outbound:order:pick";

    /** 出库-称重量方（分拣）。 */
    public static final String OUTBOUND_ORDER_MEASURE = "outbound:order:measure";

    /** 出库-打包与面单绑定（分拣）。 */
    public static final String OUTBOUND_ORDER_PACK = "outbound:order:pack";

    /** 出库-扫码交接装卸工（分拣）。 */
    public static final String OUTBOUND_ORDER_HANDOVER = "outbound:order:handover";

    /** 出库-装车确认（装卸工；**库存出账**，与分拣职责分离）。 */
    public static final String OUTBOUND_ORDER_LOAD = "outbound:order:load";

    /** 出库-取消（商家/库管；按阶段回滚库存与资金）。 */
    public static final String OUTBOUND_ORDER_CANCEL = "outbound:order:cancel";

    /** 出库-异常拦截（安全员/库管）。 */
    public static final String OUTBOUND_ORDER_HOLD = "outbound:order:hold";

    /** 出库-部分发货（库管；余量生成新单）。 */
    public static final String OUTBOUND_ORDER_PARTIAL = "outbound:order:partial";

    /** 查询/下载发货单影像。 */
    public static final String LOGISTICS_PROOF_QUERY = "logistics:proof:query";

    /**
     * 全部已登记权限码。
     *
     * <p><b>新增权限码必须同时登记到这里</b>，否则 {@link #of(String)} 会拒绝授予它——
     * 症状是"给角色配了权限但接口一直 403"（配置界面还显示成功）。这是本类存在的意义，
     * 也是最容易漏的一步：加了常量却忘了登记，编译与单测都不会报错。
     *
     * <p>2026-10-16 补齐了{@code PRICING_ESTIMATE}、{@code LOGISTICS_PROOF_*} 三项——
     * 它们此前只声明了常量、没进本清单，因此那两个接口的权限**实际上永远授不出去**（只声明未登记）。
     */
    private static final List<String> ALL = List.of(MASTER_MERCHANT_CREATE, MASTER_WAREHOUSE_CREATE,
            MASTER_SKU_CREATE, MASTER_SKU_UNIT_CONV, MASTER_STATUS_CHANGE, MASTER_QUERY, DEVICE_MANAGE, STOCK_QUERY,
            STOCK_RESERVE, STOCK_ADJUST, FINANCE_RECONCILE_MANAGE, FINANCE_SETTLE_MANAGE,
            FINANCE_SETTLE_DISPATCH, FINANCE_QUERY, APPROVAL_APPLY, APPROVAL_REVIEW, OPS_JOB_QUERY, OPS_JOB_RUN,
            STOCK_MOVE, STOCK_INVARIANT_CHECK, AUTH_ROLE_MANAGE, AUTH_WAREHOUSE_GRANT, AUTH_USER_MANAGE,
            AUTH_AUDIT_QUERY, PRICING_ESTIMATE, LOGISTICS_PROOF_UPLOAD, LOGISTICS_PROOF_QUERY,
            OUTBOUND_ORDER_CREATE, OUTBOUND_ORDER_QUERY, OUTBOUND_ORDER_DISPATCH, OUTBOUND_ORDER_PICK,
            OUTBOUND_ORDER_MEASURE, OUTBOUND_ORDER_PACK, OUTBOUND_ORDER_HANDOVER, OUTBOUND_ORDER_LOAD,
            OUTBOUND_ORDER_CANCEL, OUTBOUND_ORDER_HOLD, OUTBOUND_ORDER_PARTIAL);

    private PermissionCodes() {
    }

    /**
     * 全部已登记权限码。
     *
     * @return 权限码列表
     */
    public static List<String> all() {
        return ALL;
    }

    /**
     * 校验权限码已登记。
     *
     * @param code 权限码
     * @return 规范化后的权限码
     * @throws BizException 未登记时抛 {@code 10001}
     */
    public static String of(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (!ALL.contains(trimmed)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "未登记的权限码：" + code + "（权限码必须先在 PermissionCodes 中定义，否则配了也不生效）");
        }
        return trimmed;
    }
}
