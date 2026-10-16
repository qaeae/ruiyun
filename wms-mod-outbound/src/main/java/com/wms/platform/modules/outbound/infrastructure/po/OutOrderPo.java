package com.wms.platform.modules.outbound.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出库单（表 {@code out_order}，DDL 见 {@code 01} §3.2）。
 *
 * <p><b>status 字段只允许由 {@code modules.outbound.domain.statemachine} 包内的状态机修改</b>
 * （{@code 01} §1.2 + ArchUnit 规则"状态字段只能由状态机修改"）。本类**刻意不提供业务语义的
 * 状态变更方法**，只保留 Lombok 生成的 setter 供状态机与 MyBatis-Plus 读取。
 *
 * <p><b>卖家不可见的收件人信息</b>：{@code receiver_*_enc} 是密文（{@code PiiCipher}），
 * {@code receiver_*_masked} 是掩码（列表/详情只回掩码，明文不回传、不入日志——B16）。
 */
@Getter
@Setter
@TableName(value = "out_order", autoResultMap = true)
public class OutOrderPo {

    /** 来源类型：上游 API 推送。 */
    public static final String SOURCE_API = "API";

    /** 来源类型：Excel 导入。 */
    public static final String SOURCE_EXCEL = "EXCEL";

    /** 来源类型：人工录入（部分发货的余量单用它，避免与上游"防重复下单"唯一键打架）。 */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 主键（雪花 ID，应用生成）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 出库单号（{@code DO + yyyyMMdd + 36 进制雪花}）。 */
    private String doNo;

    /** 仓库 ID。 */
    private Long warehouseId;

    /** 商家 ID。 */
    private Long merchantId;

    /** 来源类型：API/EXCEL/MANUAL。 */
    private String sourceType;

    /** 上游订单号（防重复下单唯一键 {@code uk_source_order} 的组成部分）。 */
    private String sourceOrderNo;

    /** 承运商编码（建单时一并生成装运单）。 */
    private String carrierCode;

    /** 状态（见 {@code OutOrderStatus}；只由状态机修改）。 */
    private String status;

    /** 是否当日单（1 当日 / 0 次日，按截单时间判定，{@code 02} M7-06）。 */
    private Boolean isSameDay;

    /** 商家申报重量（kg）。 */
    private BigDecimal expectedWeight;

    /** 商家申报体积（m³）。 */
    private BigDecimal expectedVolume;

    /** 复核称重（kg）。 */
    private BigDecimal actualWeight;

    /** 复核量方（m³）。 */
    private BigDecimal actualVolume;

    /** 是否已完成称重校验。 */
    private Boolean weightChecked;

    /** 包裹件数。 */
    private Integer packageCount;

    /** 面单打印次数（补打监控）。 */
    private Integer labelPrintCount;

    /** 分拣→装卸交接是否已扫码（未交接不可装车，{@code 20005}）。 */
    private Boolean handoverConfirmed;

    /** 收件人姓名（密文）。 */
    private byte[] receiverNameEnc;

    /** 收件人姓名（掩码）。 */
    private String receiverNameMasked;

    /** 收件人电话（密文）。 */
    private byte[] receiverPhoneEnc;

    /** 收件人电话（掩码）。 */
    private String receiverPhoneMasked;

    /** 收件人地址（密文）。 */
    private byte[] receiverAddrEnc;

    /** 收件人地址（掩码，保留到"区"）。 */
    private String receiverAddrMasked;

    /** 收件人省（非 PII，用于计费与分单）。 */
    private String receiverProvince;

    /** 收件人市。 */
    private String receiverCity;

    /** 收件人区。 */
    private String receiverDistrict;

    /** 取消时点状态（回滚判定用，取消时由状态机写入）。 */
    private String cancelStage;

    /** 取消时间。 */
    private LocalDateTime cancelledAt;

    /** 出库时间（装车扫码完成）。 */
    private LocalDateTime finishedAt;

    /** 备注（部分发货会写入余量新单号；异常拦截写入原因）。 */
    private String remark;

    /** 乐观锁版本。 */
    private Integer version;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除：0 未删、1 已删。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂：新建出库单（B4）。
     *
     * <p>初始状态由状态机在 {@code CREATE} 事件里写入（{@code 待接单}），这里**不预设** status，
     * 避免出现"绕过状态机就得到一个合法状态"的旁路。
     *
     * @param id            雪花 ID
     * @param tenantId      租户 ID
     * @param doNo          出库单号
     * @param warehouseId   仓库 ID
     * @param merchantId    商家 ID
     * @param sourceType    来源类型
     * @param sourceOrderNo 上游订单号（可空）
     * @param carrierCode   承运商编码
     * @param isSameDay     是否当日单
     * @param expectedWeight 申报重量（可空）
     * @param expectedVolume 申报体积（可空）
     * @param remark        备注（可空）
     * @param operatorId    操作人（可空）
     * @return 出库单
     */
    public static OutOrderPo newOrder(long id, long tenantId, String doNo, long warehouseId, long merchantId,
                                      String sourceType, String sourceOrderNo, String carrierCode, boolean isSameDay,
                                      BigDecimal expectedWeight, BigDecimal expectedVolume, String remark,
                                      Long operatorId) {
        OutOrderPo po = new OutOrderPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setDoNo(doNo);
        po.setWarehouseId(warehouseId);
        po.setMerchantId(merchantId);
        po.setSourceType(sourceType);
        po.setSourceOrderNo(sourceOrderNo);
        po.setCarrierCode(carrierCode);
        po.setIsSameDay(isSameDay);
        po.setExpectedWeight(expectedWeight);
        po.setExpectedVolume(expectedVolume);
        po.setWeightChecked(false);
        po.setLabelPrintCount(0);
        po.setHandoverConfirmed(false);
        po.setRemark(remark);
        po.setVersion(0);
        po.setCreatedBy(operatorId);
        po.setDeleted(0);
        return po;
    }
}
