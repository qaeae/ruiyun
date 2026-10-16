package com.wms.platform.modules.md.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品主数据（表 {@code md_sku}，DDL 见 {@code 01} §3.12）。
 *
 * <p>{@code weight_kg}/{@code volume_ratio} 是本次补建时按 M4-03「运费预估」的需要补上的字段
 * （{@code 01} §3.9 只列了"关键字段"，不是完整列清单）——没有它们，预估运费与抛比计算无据可依。
 */
@Getter
@Setter
@TableName(value = "md_sku", autoResultMap = true)
public class SkuPo {

    /** 启用。 */
    public static final String STATUS_ENABLED = "启用";

    /** 停用。 */
    public static final String STATUS_DISABLED = "停用";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 商家 ID。 */
    private Long merchantId;

    /** 商品条码（现场扫码值）。 */
    private String skuCode;

    /** 商品名称。 */
    private String name;

    /** 品类（禁入品类校验用）。 */
    private String category;

    /** 基本单位。 */
    private String unit;

    /** 单件重量（kg）。 */
    private BigDecimal weightKg;

    /** 体积（或抛比基准）。 */
    private BigDecimal volumeRatio;

    /** 保质期天数。 */
    private Integer shelfLifeDays;

    /** 是否禁入品类。 */
    private Integer isForbidden;

    /** 禁入原因。 */
    private String forbiddenReason;

    /** 状态。 */
    private String status;

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
     * 是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return STATUS_ENABLED.equals(status);
    }

    /**
     * 是否禁入品类。
     *
     * @return 禁入返回 {@code true}
     */
    public boolean isForbiddenCategory() {
        return isForbidden != null && isForbidden == 1;
    }
    /**
     * 静态工厂（B4：禁止 new + setXxx）。
     *
     * @param id              雪花 ID
     * @param tenantId        租户
     * @param merchantId      商家
     * @param skuCode         条码
     * @param name            名称
     * @param category        品类
     * @param unit            基本单位
     * @param weightKg        重量
     * @param volumeRatio     体积
     * @param forbidden       是否禁入品类
     * @param forbiddenReason 禁入原因
     * @return 商品
     */
    public static SkuPo of(long id, long tenantId, long merchantId, String skuCode, String name, String category,
                           String unit, java.math.BigDecimal weightKg, java.math.BigDecimal volumeRatio,
                           boolean forbidden, String forbiddenReason) {
        SkuPo po = new SkuPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setMerchantId(merchantId);
        po.setSkuCode(skuCode);
        po.setName(name);
        po.setCategory(category);
        po.setUnit(unit);
        po.setWeightKg(weightKg);
        po.setVolumeRatio(volumeRatio);
        po.setIsForbidden(forbidden ? 1 : 0);
        po.setForbiddenReason(forbiddenReason);
        po.setStatus(STATUS_ENABLED);
        po.setDeleted(0);
        return po;
    }
}
