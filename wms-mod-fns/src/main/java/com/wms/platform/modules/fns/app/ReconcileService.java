package com.wms.platform.modules.fns.app;

import com.wms.platform.modules.fns.api.ro.ReconcileCreateRo;
import com.wms.platform.modules.fns.api.vo.ReconcileVo;
import com.wms.platform.modules.fns.domain.Liability;

import java.util.List;

/**
 * 对账服务（03 API-FNS-01/02）。
 *
 * <p><b>链路</b>：导入承运商对账文件 → 与平台费用单逐单比对 → 按已确认的阈值口径三档分流
 * （≤1 元或 ≤1% 自动平账 / &gt;1% 且 ≤20% 挂账待处理 / &gt;20% 强制人工）→ 人工逐条判定责任方
 * → 差异清零后确认对账单 → （后续）生成结算批次划款。
 *
 * <p><b>阈值口径已于 2026-10-14 由张三确认，照 PoC-5 默认执行</b>（分母 = 平台费用）。
 * 常量在 {@code ReconcileDiffRule}，改口径只改常量与该说明，不动流程。
 *
 * <p><b>两条不可绕过的约束</b>：
 * <ol>
 *   <li><b>生成幂等</b>：同一（承运商，账期）只有一张对账单，重复导入返回同一张——
 *       否则同一笔钱会被结算两次；</li>
 *   <li><b>有未处理差异时不能确认</b>：确认意味着"这账我认了"，之后就会付款；
 *       强行放行会让差异条目留在库里没人处理，而钱已经付出去了。</li>
 * </ol>
 */
public interface ReconcileService {

    /**
     * 生成（或返回已有）对账单。
     *
     * @param ro 入参（承运商侧明细）
     * @return 对账单号
     */
    String createBill(ReconcileCreateRo ro);

    /**
     * 对账单详情（含明细）。
     *
     * @param rclNo 对账单号
     * @return 详情
     */
    ReconcileVo detail(String rclNo);

    /**
     * 对账单列表（不含明细）。
     *
     * @param carrierCode 承运商（可空）
     * @param period      账期（可空）
     * @return 列表
     */
    List<ReconcileVo> list(String carrierCode, String period);

    /**
     * 人工处理差异条目（判定责任方 + 说明）。
     *
     * @param rclNo     对账单号
     * @param itemId    明细 ID
     * @param liability 责任方
     * @param remark    说明
     * @return 处理后该账单剩余未处理条数
     */
    long handleItem(String rclNo, long itemId, Liability liability, String remark);

    /**
     * 确认对账单（要求差异已全部处理完）。
     *
     * @param rclNo      对账单号
     * @param operatorId 确认人
     */
    void confirm(String rclNo, long operatorId);
}
