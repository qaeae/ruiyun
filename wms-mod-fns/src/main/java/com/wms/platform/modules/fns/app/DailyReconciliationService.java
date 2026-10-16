package com.wms.platform.modules.fns.app;

import com.wms.platform.modules.fns.api.dto.ChannelReconcileFile;
import com.wms.platform.modules.fns.api.vo.DailyReconciliationVo;

import java.time.LocalDate;
import java.util.List;

/**
 * 日终勾稽服务（03 API-FNS-07，`02` M9-05，{@code 04} §5.2）。
 *
 * <p><b>四层的分工（缺一层就留一个盲区）</b>：
 * <table border="1">
 *   <caption>四层</caption>
 *   <tr><th>层</th><th>对什么</th><th>发现什么问题</th></tr>
 *   <tr><td>资金层</td><td>平台账 vs 存管账户文件</td><td>钱少了/多了、冻结额不一致</td></tr>
 *   <tr><td>业务层</td><td>费用单 vs 资金流水扣减</td><td>该扣的没扣、重复扣</td></tr>
 *   <tr><td>三方层</td><td>平台运费 vs 承运商账单</td><td>对方多计/少计、我方漏计费</td></tr>
 *   <tr><td>库存层</td><td>三态账 vs 两级账（+实物）</td><td>货对不上账</td></tr>
 * </table>
 *
 * <p><b>幂等</b>：{@code uk_daily_layer(tenant, biz_date, layer)} —— 同一天同一层只有一条记录，
 * 补跑/重跑不会产生第二份结论；已是「已处理」的层不会被重跑覆盖（人工结论要留住）。
 */
public interface DailyReconciliationService {

    /**
     * 执行某天的四层勾稽（幂等，可重跑）。
     *
     * @param bizDate     业务日期
     * @param channelFile 通道/承运商侧数据（可空 → 相关层为「待接入」）
     * @return 四层结果
     */
    List<DailyReconciliationVo> run(LocalDate bizDate, ChannelReconcileFile channelFile);

    /**
     * 查询某天的勾稽结果。
     *
     * @param bizDate 业务日期
     * @return 结果列表（按层排序）
     */
    List<DailyReconciliationVo> query(LocalDate bizDate);

    /**
     * 标记某层差异已处理（须填说明；仅「有差异」可标记）。
     *
     * @param id     记录 ID
     * @param remark 处理说明
     */
    void handle(long id, String remark);
}
