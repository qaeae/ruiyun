package com.wms.platform.modules.fns.app;

import com.wms.platform.modules.fns.api.vo.SettleBatchVo;

import java.util.List;

/**
 * 结算批次服务（03 API-FNS-03/04）。
 *
 * <p><b>链路</b>：对账单**确认后** → 生成结算批次（待下发）→ 下发支付指令（持牌机构直付承运商）
 * → 机构受理 → 已下发 → （机构回调/日终对账）→ 已结算。
 *
 * <p><b>三道防重复付款的防线</b>：
 * <ol>
 *   <li>结构防线：{@code uk_bill(tenant_id, bill_id)}——一张对账单只能有一个批次；</li>
 *   <li>并发防线：下发用 CAS（仅「待下发」→「已下发」+ 写机构指令号），影响 0 行即已下发，
 *       调用方**必须报冲突**（不能当成功返回，否则调用方以为"刚发出去"，实际早已发过）；</li>
 *   <li>机构侧幂等号 {@code SETTLE:{批次号}}——挡的是"我方发了但没收到响应、又重试一次"。</li>
 * </ol>
 * 三层都不能省：前两层挡我方重复，第三层挡网络层的重复。
 */
public interface SettlementService {

    /**
     * 按对账单生成结算批次（要求对账单已确认）。
     *
     * @param rclNo 对账单号
     * @return 批次号
     */
    String generate(String rclNo);

    /**
     * 下发支付指令。
     *
     * @param stbNo      批次号
     * @param operatorId 操作人
     * @return 机构侧指令号
     */
    String dispatch(String stbNo, long operatorId);

    /**
     * 批次列表。
     *
     * @param carrierCode 承运商（可空）
     * @param period      账期（可空）
     * @return 列表
     */
    List<SettleBatchVo> list(String carrierCode, String period);

    /**
     * 批次详情。
     *
     * @param stbNo 批次号
     * @return 详情
     */
    SettleBatchVo detail(String stbNo);
}
