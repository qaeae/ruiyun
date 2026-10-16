package com.wms.platform.modules.fns.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.modules.fns.api.ro.FreightEstimateRo;
import com.wms.platform.modules.fns.api.vo.FreightQuoteVo;
import com.wms.platform.modules.fns.app.RateCardService;
import com.wms.platform.modules.fns.domain.rate.FreightQuote;
import com.wms.platform.modules.fns.domain.rate.ParcelFacts;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运费试算接口（`03` **API-CS-04**：`POST /pricing/estimate`）。
 *
 * <p><b>路径严格按 `03` 契约</b>（不改接口文档已定义的对外路径）；
 * 代码落在 fns 域，因为价目表与结算同属资金口径——客服域（cs）目前只有包占位，
 * 等它真正落地客服工单能力时再决定是否迁址（接口不变，仅内部归属调整）。
 *
 * <p>返回按契约包含：{@code estimatedFee}（预估价）、**命中版本**、缓冲系数，
 * 另外附上实重/体积重/计费重与计费轨迹——这些是回答"为什么是这个价"的必需信息（对账与申诉）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
public class PricingController {

    /** 价目表与试算服务。 */
    private final RateCardService rateCardService;

    /**
     * 运费试算（预估价）。
     *
     * @param ro 试算请求
     * @return 试算结果（含命中版本、缓冲系数与计费轨迹）
     */
    @PostMapping("/estimate")
    @RequiresPermission(PermissionCodes.PRICING_ESTIMATE)
    public ApiResponse<FreightQuoteVo> estimate(@Valid @RequestBody FreightEstimateRo ro) {
        FreightQuote quote = rateCardService.estimate(ro.cardCode(),
                new ParcelFacts(ro.regionCode(), ro.actualWeightKg(), ro.volumeCm3()),
                ro.bufferRatio());
        return ApiResponse.ok(FreightQuoteVo.of(quote));
    }
}
