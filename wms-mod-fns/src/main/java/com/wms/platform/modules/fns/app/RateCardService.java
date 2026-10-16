package com.wms.platform.modules.fns.app;

import com.wms.platform.modules.fns.domain.rate.FreightQuote;
import com.wms.platform.modules.fns.domain.rate.ParcelFacts;
import com.wms.platform.modules.fns.domain.rate.RateItem;

import java.math.BigDecimal;
import java.util.List;

/**
 * 价目表与运费试算服务（`03` API-CS-04 的后端能力）。
 *
 * <p><b>为什么"调价"是 {@code createVersion} 而不是 {@code updatePrice}</b>：
 * 历史运费必须能复算。直接改价会让上个月的运费失去解释力
 * （对账、申诉、承运商结算都要回答"当时为什么是这个价"）。
 */
public interface RateCardService {

    /**
     * 新建一个价目表版本（状态=草稿）。
     *
     * @param cardCode    编码
     * @param name        名称
     * @param carrierCode 承运商（ALL=通用）
     * @param bufferRatio 缓冲系数
     * @return 新版本号
     */
    int createVersion(String cardCode, String name, String carrierCode, BigDecimal bufferRatio);

    /**
     * 给指定版本的价目表加一条明细（仅草稿版本可改）。
     *
     * @param cardCode      编码
     * @param version       版本
     * @param regionCode    区域
     * @param weightFrom    计费重下界（含）
     * @param weightTo      计费重上界（不含，null=无上限）
     * @param firstWeight   首重
     * @param firstPrice    首重价
     * @param addUnit       续重单位
     * @param addPrice      续重单价
     * @param volumeDivisor 体积重系数
     * @param minCharge     最低消费
     * @param priority      优先级
     */
    void addItem(String cardCode, int version, String regionCode, BigDecimal weightFrom, BigDecimal weightTo,
                 BigDecimal firstWeight, BigDecimal firstPrice, BigDecimal addUnit, BigDecimal addPrice,
                 BigDecimal volumeDivisor, BigDecimal minCharge, int priority);

    /**
     * 启用某版本（同编码的其它启用版本会被停用：同一时刻只有一个生效版本）。
     *
     * @param cardCode 编码
     * @param version  版本
     */
    void enableVersion(String cardCode, int version);

    /**
     * 查看某版本的明细（运营核对用）。
     *
     * @param cardCode 编码
     * @param version  版本
     * @return 明细
     */
    List<RateItem> itemsOf(String cardCode, int version);

    /**
     * 运费试算（`03` API-CS-04）。
     *
     * @param cardCode            价目表编码（空则用默认编码）
     * @param facts               包裹事实
     * @param bufferRatioOverride 缓冲系数覆盖（为空则用价目表自带的）
     * @return 试算结果（含命中版本与轨迹）
     */
    FreightQuote estimate(String cardCode, ParcelFacts facts, BigDecimal bufferRatioOverride);
}
