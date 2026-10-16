package com.wms.platform.modules.outbound.api.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出库单详情视图（API-OUT-13 {@code /{doNo}}）。
 *
 * <p><b>收件人只回掩码</b>：{@code receiver} 里是 {@code nameMasked/phoneMasked/addressMasked}
 * （明文密文都不出接口，B16）；省份/市/区是非 PII，用于展示与分单。
 *
 * <p><b>为什么不在这里嵌装运单与冻结单详情</b>：那两个对象分别归物流域与资金域，
 * 由各自接口提供（物流取号 M8、资金扣减 M3 落地时补）；出库详情只保证"本域事实"完整，
 * 避免出库详情变成跨域聚合接口（一处跨域查询失败会让整个详情页打不开）。
 *
 * @param id                出库单 ID
 * @param doNo              出库单号
 * @param status            状态
 * @param warehouseId       仓库 ID
 * @param merchantId        商家 ID
 * @param sourceType        来源类型
 * @param sourceOrderNo     上游订单号
 * @param carrierCode       承运商编码
 * @param isSameDay         是否当日单（按截单时间判定）
 * @param expectedWeight    申报重量
 * @param expectedVolume    申报体积
 * @param actualWeight      实称重量
 * @param actualVolume      实量体积
 * @param weightChecked     是否已称重校验
 * @param packageCount      件数
 * @param labelPrintCount   面单打印次数
 * @param handoverConfirmed 是否已扫码交接
 * @param receiver          收件人（掩码视图）
 * @param items             明细
 * @param cancelStage       取消时点状态（未取消为 {@code null}）
 * @param cancelledAt       取消时间
 * @param finishedAt        出库时间
 * @param remark            备注（部分发货余量单号、拦截原因等）
 * @param createdAt         建单时间
 */
public record OutOrderDetailVo(Long id, String doNo, String status, Long warehouseId, Long merchantId,
                               String sourceType, String sourceOrderNo, String carrierCode, Boolean isSameDay,
                               BigDecimal expectedWeight, BigDecimal expectedVolume, BigDecimal actualWeight,
                               BigDecimal actualVolume, Boolean weightChecked, Integer packageCount,
                               Integer labelPrintCount, Boolean handoverConfirmed, ReceiverVo receiver,
                               List<OutOrderItemVo> items, String cancelStage, LocalDateTime cancelledAt,
                               LocalDateTime finishedAt, String remark, LocalDateTime createdAt) {

    /**
     * 收件人掩码视图（明文永不出接口）。
     *
     * @param nameMasked    姓名掩码
     * @param phoneMasked   电话掩码
     * @param addressMasked 地址掩码（保留到"区"）
     * @param province      省
     * @param city          市
     * @param district      区
     */
    public record ReceiverVo(String nameMasked, String phoneMasked, String addressMasked, String province,
                             String city, String district) {
    }
}
