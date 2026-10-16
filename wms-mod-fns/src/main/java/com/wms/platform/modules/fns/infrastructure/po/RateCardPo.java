package com.wms.platform.modules.fns.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 价目表（版本化）持久化对象。
 *
 * <p>状态流转：草稿 → 启用 → 停用。**调价 = 新增版本**，旧版本停用但不删除——
 * 否则历史运费无法复算（对账与申诉都需要）。
 */
@Getter
@Setter
@TableName("fn_rate_card")
public class RateCardPo {

    /** 草稿。 */
    public static final String STATUS_DRAFT = "草稿";

    /** 启用。 */
    public static final String STATUS_ENABLED = "启用";

    /** 停用。 */
    public static final String STATUS_DISABLED = "停用";

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 价目表编码。 */
    private String cardCode;

    /** 名称。 */
    private String name;

    /** 承运商编码（ALL = 通用）。 */
    private String carrierCode;

    /** 版本号。 */
    private Integer version;

    /** 状态。 */
    private String status;

    /** 预估价缓冲系数。 */
    private BigDecimal bufferRatio;

    /** 备注。 */
    private String remark;

    /** 逻辑删除。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂（B4：禁止 new + 连续 setter）。
     *
     * @param id           主键
     * @param tenantId     租户
     * @param cardCode     编码
     * @param name         名称
     * @param carrierCode  承运商
     * @param version      版本
     * @param bufferRatio  缓冲系数
     * @param operatorId   操作人
     * @return 价目表（状态=草稿）
     */
    public static RateCardPo of(Long id, Long tenantId, String cardCode, String name, String carrierCode,
                                Integer version, BigDecimal bufferRatio, Long operatorId) {
        RateCardPo po = new RateCardPo();
        po.id = id;
        po.tenantId = tenantId;
        po.cardCode = cardCode;
        po.name = name;
        po.carrierCode = carrierCode;
        po.version = version;
        po.status = STATUS_DRAFT;
        po.bufferRatio = bufferRatio;
        po.deleted = 0;
        po.createdBy(operatorId);
        return po;
    }

    /**
     * 启用（语义方法：状态只能由这里改，避免散落的 setter 调用）。
     */
    public void enable() {
        this.status = STATUS_ENABLED;
    }

    /**
     * 停用。
     */
    public void disable() {
        this.status = STATUS_DISABLED;
    }

    /**
     * 记录创建人（避免在服务层直接 set）。
     *
     * @param operatorId 操作人
     */
    private void createdBy(Long operatorId) {
        this.createdBy = operatorId;
    }

    /** 创建人。 */
    private Long createdBy;
}
