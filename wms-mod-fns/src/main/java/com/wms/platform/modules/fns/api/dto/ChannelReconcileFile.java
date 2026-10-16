package com.wms.platform.modules.fns.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 通道/承运商侧对账数据（日终勾稽的"对方账"）。
 *
 * <p><b>为什么必须由外部传入，而不是我们自己算</b>：日终勾稽的意义就是**拿对方的账跟我们的账对**。
 * 如果"对方侧"也从我们自己的表里推，就变成自己跟自己对，永远平——对账也就失去了意义。
 *
 * <p>一期：资金层/三方层的对方账由运营从机构对账文件、承运商账单导入（K2 到位后改由接口自动拉取）。
 * 传 {@code null} 或对应字段为 {@code null} 表示"这层没有对方数据"，
 * 勾稽结果会是**待接入**而不是"有差异"（理由见 {@code DailyReconcileRule}）。
 *
 * @param merchantBalances 资金层：逐商家「子账户余额 + 冻结额」
 * @param carrierFees      三方层：逐运单承运商账单金额
 */
public record ChannelReconcileFile(Map<Long, BigDecimal> merchantBalances, Map<String, BigDecimal> carrierFees) {
}
