package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建出库单（{@code 03} API-OUT-01，状态机第 1 行）。
 *
 * <p><b>为什么 {@code estimatedFreight} 由调用方传入</b>：{@code 03} API-OUT-01 写的是"含试算冻结"，
 * 而运费试算（API-CS-04 {@code /pricing/estimate}）落在资金域、且该域在后续轮次会独立部署。
 * 在**建单事务里同步远程调价目表**正是 {@code 53.12} 已证伪的反模式（事务内远程调用 →
 * 连接被拖垮、超时后本地回滚而远端已冻）。因此把"先试算、再带价建单"作为两步契约：
 * 调用方（商家端/上游）先调试算拿到价，再把价带进来建单；服务端只校验"价必须为正"。
 *
 * <p>收件人信息是 PII：传入后服务端**加密存储 + 只回掩码**，明文不入日志（B16）。
 *
 * @param warehouseId      仓库 ID
 * @param merchantId       商家 ID
 * @param sourceType       来源类型（API/EXCEL/MANUAL）
 * @param sourceOrderNo    上游订单号（防重复下单的业务键，API 来源必填）
 * @param carrierCode      承运商编码（建单联动生成装运单）
 * @param carrierName      承运商名称（可空）
 * @param estimatedFreight 预估价（元，正数；冻结基数）
 * @param expectedWeight   申报重量 kg（可空）
 * @param expectedVolume   申报体积 m³（可空）
 * @param receiverName     收件人姓名（PII）
 * @param receiverPhone    收件人电话（PII）
 * @param receiverAddress  收件人地址（PII）
 * @param receiverProvince 收件人省（非 PII，用于计费与分单）
 * @param receiverCity     收件人市
 * @param receiverDistrict 收件人区
 * @param remark           备注（可空）
 * @param items            明细（至少一行）
 */
public record CreateOutOrderRo(@NotNull(message = "仓库不能为空") Long warehouseId,
                               @NotNull(message = "商家不能为空") Long merchantId,
                               @NotBlank(message = "来源类型不能为空") String sourceType,
                               @Size(max = 64, message = "上游订单号不得超过 64 字符") String sourceOrderNo,
                               @NotBlank(message = "承运商不能为空") String carrierCode,
                               @Size(max = 64, message = "承运商名称不得超过 64 字符") String carrierName,
                               @NotNull(message = "预估运费不能为空")
                               @DecimalMin(value = "0.0001", message = "预估运费必须为正数")
                               BigDecimal estimatedFreight,
                               BigDecimal expectedWeight,
                               BigDecimal expectedVolume,
                               @NotBlank(message = "收件人姓名不能为空") String receiverName,
                               @NotBlank(message = "收件人电话不能为空") String receiverPhone,
                               @NotBlank(message = "收件人地址不能为空") String receiverAddress,
                               String receiverProvince,
                               String receiverCity,
                               String receiverDistrict,
                               @Size(max = 255, message = "备注不得超过 255 字符") String remark,
                               @NotEmpty(message = "出库明细不能为空") @Valid List<ItemRo> items) {

    /**
     * 出库明细行。
     *
     * @param skuCode 商品条码（服务端解析为 skuId，并校验禁入品类）
     * @param qty     应发数量
     * @param unit    单位（可空）
     */
    public record ItemRo(@NotBlank(message = "商品条码不能为空") String skuCode,
                         @NotNull(message = "应发数量不能为空")
                         @DecimalMin(value = "0.0001", message = "应发数量必须为正数") BigDecimal qty,
                         String unit) {
    }
}
