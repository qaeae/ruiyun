/**
 * 出库：DO 全流程（下单冻结→拣货→复核→称重→打包→交接→装车出账→取消回滚）（02 M7、03 API-OUT、01 §2.1）
 *
 * <p><b>包结构与依赖方向（由 wms-archtest 的 ArchUnit 规则守护）</b>：
 * <pre>
 *   api     →  Controller / DTO / 装配      （仅可依赖 app 与本域 domain）
 *   app     →  应用服务、编排、事务边界      （可依赖本域 domain 与 infrastructure 的接口实现）
 *   domain  →  领域模型、状态机、领域规则    （不得依赖 api/app/infrastructure 的 Mapper）
 *   infrastructure → Mapper / 通道适配 / 设备网关实现
 * </pre>
 *
 * <p><b>跨域协作规则</b>：只允许通过对方的 {@code app} 层接口（或事件）协作，
 * <b>禁止跨域直接使用 Mapper</b>（防止绕过对方的状态机与不变量校验）。
 */
package com.wms.platform.modules.outbound;
