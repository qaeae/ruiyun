package com.wms.platform.modules.fns.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运单只读查询（**跨域缝隙，临时**）。
 *
 * <p><b>为什么是缝隙</b>：`lg`（物流）域尚未落地（W7），而 `fns` 的兜底扣减必须知道
 * "哪些运单已揽收满 7 天且未回传实际运费"。因此这里用 `@Select` 直接读 `lg_shipment`。
 *
 * <p><b>回收计划</b>：`lg` 域 app 层落地后，改为调用 `logistics.app.ShipmentQueryService`，
 * 删除本类。已登记在 `docs/standards/STANDARDS-MAPPING.md` §八。
 *
 * <p><b>只读约束</b>：本类**只允许 SELECT**。回写 `fee_back_at`/`actual_fee` 属于 `lg` 域的职责——
 * 跨域写入会让"谁在改这张表"变得无法回答（这也是前面 `pda` 域缝隙清理时定下的规则）。
 */
@Mapper
public interface LogisticsShipmentSeamMapper {

    /**
     * 兜底扣减候选：已揽收满 {@code days} 天、仍未回传实际运费、且有预估价。
     *
     * @param tenantId      租户
     * @param deadline      截止时间（= now - 7 天；揽收时间早于它即到期）
     * @param limit         单次扫描上限（防止一次捞太多把内存/事务撑爆）
     * @return 候选行
     */
    @Select("SELECT id, waybill_no, merchant_id, warehouse_id, estimated_fee, picked_up_at "
            + "FROM lg_shipment WHERE tenant_id = #{tenantId} AND deleted = 0 "
            + "AND picked_up_at IS NOT NULL AND picked_up_at <= #{deadline} "
            + "AND fee_back_at IS NULL AND estimated_fee IS NOT NULL "
            + "AND status NOT IN ('已作废','已退件') "
            + "ORDER BY picked_up_at LIMIT #{limit}")
    List<ShipmentRow> selectFallbackCandidates(@Param("tenantId") long tenantId,
                                               @Param("deadline") LocalDateTime deadline,
                                               @Param("limit") int limit);

    /**
     * 按运单查（回传结算时用）。
     *
     * @param tenantId  租户
     * @param waybillNo 运单号
     * @return 行，不存在返回 {@code null}
     */
    @Select("SELECT id, waybill_no, merchant_id, warehouse_id, estimated_fee, picked_up_at "
            + "FROM lg_shipment WHERE tenant_id = #{tenantId} AND waybill_no = #{waybillNo} AND deleted = 0 "
            + "ORDER BY id LIMIT 1")
    ShipmentRow selectByWaybill(@Param("tenantId") long tenantId, @Param("waybillNo") String waybillNo);

    /**
     * 运单行（仅兜底扣减所需的列）。
     *
     * @param id           发货单 ID
     * @param waybillNo    运单号
     * @param merchantId   商家
     * @param warehouseId  仓库
     * @param estimatedFee 预估运费
     * @param pickedUpAt   揽收时间
     */
    record ShipmentRow(long id, String waybillNo, long merchantId, long warehouseId, BigDecimal estimatedFee,
                       LocalDateTime pickedUpAt) {
    }
}
