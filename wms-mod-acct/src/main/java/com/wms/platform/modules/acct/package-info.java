/**
 * 账户与资金领域（本轮为最小面：费用冻结 / 释放 / 查可用余额）。
 *
 * <p><b>为什么本轮只做这一小块</b>：出库单创建（{@code 03} API-OUT-01 / {@code 01} §2.1 状态机 #1）的
 * 前置条件是「余额足 + 冻结成功」。若冻结不落地，出库链路就是「能发货但没冻住钱」——
 * 资金防线从内部被绕开，而这正是 {@code 04-资金合规落地方案} 最要紧的一条。
 *
 * <p><b>本轮不做</b>（留给 M3 轮次，表已建好）：充值（{@code ac_merchant_account} 入账）、
 * 扣减（冻结转扣减 + 差额释放）、通道适配（{@code ac_channel_order}）、余额退款、红冲。
 * 因此本模块本轮**不暴露 HTTP**：没有对应的 API-ACCT-* 接口，只提供域内服务接口给出库域使用。
 *
 * <p>包结构（由 {@code wms-archtest} 守护）：{@code app} 服务与事务边界、{@code domain} 规则、
 * {@code infrastructure} 的 Mapper 与 Po。跨域只走 {@code app} 层接口，禁止直接读写 {@code ac_*} 表。
 */
package com.wms.platform.modules.acct;
