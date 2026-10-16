package com.wms.platform.modules.iv.app;

import com.wms.platform.modules.iv.api.ro.StocktakeCreateRo;
import com.wms.platform.modules.iv.api.ro.StocktakeRecordRo;
import com.wms.platform.modules.iv.api.vo.StocktakeVo;

import java.util.List;

/**
 * 盘点服务（03 API-IV-03：创建 / 录入 / 审批）。
 *
 * <p><b>为什么盘点值得单独做一套状态机与双人复核</b>：盘点是"账实差异合法化"的**唯一入口**——
 * 审批通过后系统会按差异直接改库存（生成 `ADJUST` 流水），财务随后按调整后的账结算。
 * 也就是说：能把差异写成盘亏的人，等于能把货"凭空消掉"。因此：
 * <ol>
 *   <li>录入前固定**账面快照**（差异基准不受录入期间作业影响）；</li>
 *   <li>提交后**明细不可再改**（否则审批看到的内容与执行的内容不一致）；</li>
 *   <li>**审批人必须 ≠ 创建人**（`80002`，双人复核）；</li>
 *   <li>驳回即终态（需重新建单，避免"驳回单反复提交"把审批轨迹搅浑）。</li>
 * </ol>
 */
public interface StocktakeService {

    /**
     * 创建盘点单并生成明细（按库区/库位取账面快照）。
     *
     * @param ro 入参
     * @return 盘点单号
     */
    String create(StocktakeCreateRo ro);

    /**
     * 录入实盘数（仅「待录入」可录入；差异 = 实盘 − 账面快照）。
     *
     * @param stocktakeNo 盘点单号
     * @param ro          入参（多条明细）
     * @return 本次成功录入条数
     */
    int record(String stocktakeNo, StocktakeRecordRo ro);

    /**
     * 提交审批（状态 → 待审批，并统计有差异条数）。
     *
     * @param stocktakeNo 盘点单号
     * @return 有差异条数
     */
    int submit(String stocktakeNo);

    /**
     * 审批通过：**对每条有差异的明细生成 ADJUST 流水**（幂等键 = {@code STOCKTAKE:{单号}:{SKU}:{库位}}）。
     *
     * @param stocktakeNo 盘点单号
     * @param approverId  审批人（必须 ≠ 创建人）
     * @return 实际生成的调整流水条数
     */
    int approve(String stocktakeNo, long approverId);

    /**
     * 驳回（状态 → 已驳回，终态）。
     *
     * @param stocktakeNo 盘点单号
     * @param reason      驳回原因
     * @param operatorId  操作人
     */
    void reject(String stocktakeNo, String reason, long operatorId);

    /**
     * 盘点单列表。
     *
     * @param warehouseId 仓库（可空）
     * @param status      状态（可空）
     * @return 列表
     */
    List<StocktakeVo> list(Long warehouseId, String status);

    /**
     * 盘点单详情（含明细与差异）。
     *
     * @param stocktakeNo 盘点单号
     * @return 详情
     */
    StocktakeVo detail(String stocktakeNo);
}
