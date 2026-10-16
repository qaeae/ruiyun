package com.wms.platform.modules.fns.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 价目表明细持久化对象（区域 + 重量半开区间 → 首重/续重）。
 */
@Getter
@Setter
@TableName("fn_rate_item")
public class RateItemPo {

    /** 主键。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户。 */
    private Long tenantId;

    /** 所属价目表。 */
    private Long cardId;

    /** 区域码。 */
    private String regionCode;

    /** 计费重下界（含）。 */
    private BigDecimal weightFrom;

    /** 计费重上界（不含，NULL = 无上限）。 */
    private BigDecimal weightTo;

    /** 首重（kg）。 */
    private BigDecimal firstWeight;

    /** 首重价。 */
    private BigDecimal firstPrice;

    /** 续重单位（kg）。 */
    private BigDecimal addUnit;

    /** 续重单价。 */
    private BigDecimal addPrice;

    /** 体积重系数。 */
    private BigDecimal volumeDivisor;

    /** 最低消费。 */
    private BigDecimal minCharge;

    /** 优先级。 */
    private Integer priority;

    /** 备注。 */
    private String remark;

    /** 逻辑删除。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂。
     *
     * @param id       主键
     * @param tenantId 租户
     * @param cardId   价目表 ID
     * @return 明细
     */
    public static RateItemPo of(Long id, Long tenantId, Long cardId) {
        RateItemPo po = new RateItemPo();
        po.id = id;
        po.tenantId = tenantId;
        po.cardId = cardId;
        po.deleted = 0;
        return po;
    }
}
