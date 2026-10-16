/**
 * 物流：承运商 SPI/取号/面单/作废补打/轨迹与费用回调/单号池（02 M8、03 API-LOG、01 §2.2）
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
package com.wms.platform.modules.logistics;
