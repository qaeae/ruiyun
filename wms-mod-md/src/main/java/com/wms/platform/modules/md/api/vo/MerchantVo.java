package com.wms.platform.modules.md.api.vo;

/**
 * 商家视图（03 API-MD-01/07）。
 *
 * <p><b>只出脱敏手机号</b>：完整号码存密文列，接口默认不返回。这不是"技术上做不到解密"，
 * 而是"默认看不到"能省掉绝大多数越权与误泄漏（{@code 01} §7）。
 *
 * @param id                 商家 ID
 * @param merchantNo         商家编号
 * @param name               名称
 * @param contactName        联系人
 * @param contactPhoneMasked 脱敏手机号
 * @param status             状态
 * @param creditScore        信用分
 */
public record MerchantVo(Long id, String merchantNo, String name, String contactName, String contactPhoneMasked,
                         String status, Integer creditScore) {
}
