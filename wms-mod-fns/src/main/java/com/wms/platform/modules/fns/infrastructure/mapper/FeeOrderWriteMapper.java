package com.wms.platform.modules.fns.infrastructure.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用单写入（兜底扣减 / 回传结算专用）。
 *
 * <p><b>为什么单独一个 Mapper 而不是复用 {@code FnFeeOrderMapper.insert}</b>：
 * 这里需要的是"**插入并依赖唯一键判重**"的语义——{@code uk_fee_biz(tenant_id, fee_type, biz_type, biz_id)}
 * 是兜底扣减的幂等防线：同一运单重复扫描时插入会撞唯一键，调用方据此判定"已经扣过了"，
 * 而不是靠"先查后插"（并发下会双写）。用 {@code INSERT} 直给字段，避免把整张表的列拉进服务层。
 */
@Mapper
public interface FeeOrderWriteMapper {

    /**
     * 插入费用单（撞唯一键 {@code uk_fee_biz} 时抛 {@code DuplicateKeyException}）。
     *
     * @param id            雪花 ID
     * @param tenantId      租户
     * @param feeNo         费用单号
     * @param merchantId    商家
     * @param warehouseId   仓库
     * @param feeType       费用类型
     * @param bizType       业务类型
     * @param bizId         业务对象 ID
     * @param bizNo         业务单号
     * @param amount        金额（可负：退款为负）
     * @param billingPeriod 账期
     * @param status        状态
     * @param remark        备注
     * @param operatorId    操作人
     * @param billedAt      计费时间
     * @return 影响行数
     */
    @Insert("INSERT INTO fn_fee_order (id, tenant_id, fee_no, merchant_id, warehouse_id, fee_type, biz_type, biz_id, "
            + "biz_no, amount, billing_period, status, price_snapshot, billed_at, created_by, deleted) "
            + "VALUES (#{id}, #{tenantId}, #{feeNo}, #{merchantId}, #{warehouseId}, #{feeType}, #{bizType}, #{bizId}, "
            + "#{bizNo}, #{amount}, #{billingPeriod}, #{status}, JSON_OBJECT('remark', #{remark}), #{billedAt}, "
            + "#{operatorId}, 0)")
    int insertFeeOrder(@Param("id") long id, @Param("tenantId") long tenantId, @Param("feeNo") String feeNo,
                       @Param("merchantId") long merchantId, @Param("warehouseId") long warehouseId,
                       @Param("feeType") String feeType, @Param("bizType") String bizType,
                       @Param("bizId") long bizId, @Param("bizNo") String bizNo,
                       @Param("amount") BigDecimal amount, @Param("billingPeriod") String billingPeriod,
                       @Param("status") String status, @Param("remark") String remark,
                       @Param("operatorId") Long operatorId, @Param("billedAt") LocalDateTime billedAt);

    /**
     * 查某业务对象的费用单状态（幂等判定用）。
     *
     * @param tenantId 租户
     * @param feeType  费用类型
     * @param bizType  业务类型
     * @param bizId    业务对象 ID
     * @return 状态，不存在返回 {@code null}
     */
    @org.apache.ibatis.annotations.Select("SELECT status FROM fn_fee_order WHERE tenant_id = #{tenantId} "
            + "AND fee_type = #{feeType} AND biz_type = #{bizType} AND biz_id = #{bizId} AND deleted = 0 LIMIT 1")
    String selectStatus(@Param("tenantId") long tenantId, @Param("feeType") String feeType,
                        @Param("bizType") String bizType, @Param("bizId") long bizId);

    /**
     * 查某业务对象的费用单金额。
     *
     * @param tenantId 租户
     * @param feeType  费用类型
     * @param bizType  业务类型
     * @param bizId    业务对象 ID
     * @return 金额，不存在返回 {@code null}
     */
    @org.apache.ibatis.annotations.Select("SELECT amount FROM fn_fee_order WHERE tenant_id = #{tenantId} "
            + "AND fee_type = #{feeType} AND biz_type = #{bizType} AND biz_id = #{bizId} AND deleted = 0 LIMIT 1")
    BigDecimal selectAmount(@Param("tenantId") long tenantId, @Param("feeType") String feeType,
                            @Param("bizType") String bizType, @Param("bizId") long bizId);
}
