package com.wms.platform.modules.logistics.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 装运单单号规则（{@code 01} §1.1：{@code SHP + yyyyMMdd + 序列}）。
 *
 * <p>与 {@code ApprovalRule.approvalNo} / {@code FeeFreezeRule.freezeNo} 同口径：
 * 用 **36 进制编码整颗雪花 ID** 而不是"取模 1 万的流水号"。后者在一天内累计约 120 单时
 * 碰撞概率就接近 50%，表现为偶发唯一键冲突（建单直接失败），而且极难复现。
 */
public final class ShipmentNoRule {

    /** 前缀（{@code 01} 约定）。 */
    private static final String PREFIX = "SHP";

    private ShipmentNoRule() {
    }

    /**
     * 生成装运单号。
     *
     * @param id 雪花 ID
     * @return 装运单号（{@code lg_shipment.shp_no VARCHAR(32)} 之内）
     */
    public static String shpNo(long id) {
        return PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + Long.toString(id, 36).toUpperCase(Locale.ROOT);
    }
}
