package com.wms.platform.modules.md.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 商家主数据（表 {@code md_merchant}，DDL 见 {@code 01} §3.12）。
 *
 * <p>{@code contact_phone_enc} 存**密文**、{@code contact_phone_masked} 存脱敏值（{@code 01} §7 PII 约定）：
 * 列表展示只读脱敏列，因此日常运营看不到完整手机号——不是"能不能解密"的问题，
 * 而是"默认看不到"能省掉绝大多数越权与泄漏。
 */
@Getter
@Setter
@TableName(value = "md_merchant", autoResultMap = true)
public class MdMerchantPo {

    /** 正常。 */
    public static final String STATUS_ACTIVE = "正常";

    /** 停用。 */
    public static final String STATUS_DISABLED = "停用";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 商家编号。 */
    private String merchantNo;

    /** 名称。 */
    private String name;

    /** 联系人。 */
    private String contactName;

    /** 联系人手机号密文。 */
    private String contactPhoneEnc;

    /** 联系人手机号脱敏。 */
    private String contactPhoneMasked;

    /** 状态。 */
    private String status;

    /** 信用分。 */
    private Integer creditScore;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂（B4）。
     *
     * @param id                雪花 ID
     * @param tenantId          租户
     * @param merchantNo        商家编号
     * @param name              名称
     * @param contactName       联系人
     * @param contactPhoneEnc   手机号密文
     * @param contactPhoneMasked 手机号脱敏
     * @return 商家
     */
    public static MdMerchantPo of(long id, long tenantId, String merchantNo, String name, String contactName,
                                  String contactPhoneEnc, String contactPhoneMasked) {
        MdMerchantPo po = new MdMerchantPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setMerchantNo(merchantNo);
        po.setName(name);
        po.setContactName(contactName);
        po.setContactPhoneEnc(contactPhoneEnc);
        po.setContactPhoneMasked(contactPhoneMasked);
        po.setStatus(STATUS_ACTIVE);
        po.setCreditScore(100);
        po.setDeleted(0);
        return po;
    }
}
