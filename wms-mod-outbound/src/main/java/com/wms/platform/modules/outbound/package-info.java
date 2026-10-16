/**
 * 出库：DO 全流程（下单冻结→拣货→复核→称重→打包→交接→装车出账→取消回滚）（02 M7、03 API-OUT、01 §2.1）
 *
 * <p><b>包结构与依赖方向（由 wms-archtest 的 ArchUnit 规则守护）</b>：
 * <pre>
 *   api     →  Controller / Ro / Vo         （仅可依赖 app 与本域 domain）
 *   app     →  应用服务、编排、事务边界       （可依赖本域 domain 与 infrastructure 端口实现）
 *   domain  →  领域模型、状态机、领域规则      （不得依赖 api/app/infrastructure 的 Mapper）
 *   infrastructure → Mapper / Po / 端口适配实现
 * </pre>
 *
 * <p><b>跨域协作规则</b>：只允许通过对方的 {@code app} 层接口（或事件）协作，
 * <b>禁止跨域直接使用 Mapper</b>（防止绕过对方的状态机与不变量校验）。本域实际用到：
 * <ul>
 *   <li>{@code iv} 的 {@code InventoryService}——预占/释放/分配/拣货/出库扣减（库位账与三态不变量由它保证）；</li>
 *   <li>{@code md} 的 {@code MasterDataQueryService}——SKU 解析、禁入品类校验、库位可拣校验；</li>
 *   <li>{@code acct} 的费用冻结——经本域 {@code app.remote.FeeFreezePort} 端口；</li>
 *   <li>{@code logistics} 的装运单创建——经 {@code app.remote.ShipmentPort} 端口。</li>
 * </ul>
 *
 * <p><b>为什么冻结与装运单要走端口而不是直接注入对方服务</b>：这两件事在后续轮次会分别由
 * acct/fns 与物流独立服务承载（见 {@code 11-微服务拆分方案（分期）.md}），端口让我们只换适配实现、
 * 不动应用服务；同时把「跨域调用」显式登记在 {@code app.remote} 一处，评审时一眼可见有几个越界依赖。
 */
package com.wms.platform.modules.outbound;
