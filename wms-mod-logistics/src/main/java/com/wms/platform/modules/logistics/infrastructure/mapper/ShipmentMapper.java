package com.wms.platform.modules.logistics.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.logistics.infrastructure.po.ShipmentPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 装运单 Mapper（表 {@code lg_shipment}）。
 *
 * <p>本轮的调用方（出库域）经端口转调，因此这里提供的是"按 DO + 承运商查一张装运单"这类
 * 业务键查询：出库建单需要靠它做**建单联动的幂等**（同一 DO 同一承运商只生成一张装运单）。
 */
@Mapper
public interface ShipmentMapper extends BaseMapper<ShipmentPo> {

    /**
     * 按出库单 + 承运商查装运单（建单联动幂等用）。
     *
     * <p>表上没有 {@code (do_id, carrier_code)} 唯一键（{@code 01} 只给了 {@code idx_do}），
     * 因此这里必须配合"一个 DO 只在创建时装一次"的调用时机；若后续允许一 DO 多承运商重派，
     * 应改为按 {@code shp_no} 定位而不是复用它。
     *
     * @param tenantId    租户
     * @param doId        出库单 ID
     * @param carrierCode 承运商编码
     * @return 装运单，不存在返回 {@code null}
     */
    default ShipmentPo selectByDo(long tenantId, long doId, String carrierCode) {
        return selectOne(Wrappers.<ShipmentPo>lambdaQuery()
                .eq(ShipmentPo::getTenantId, tenantId)
                .eq(ShipmentPo::getDoId, doId)
                .eq(ShipmentPo::getCarrierCode, carrierCode)
                .orderByAsc(ShipmentPo::getId)
                .last("limit 1"));
    }

    /**
     * 按装运单号查。
     *
     * @param tenantId 租户
     * @param shpNo    装运单号
     * @return 装运单，不存在返回 {@code null}
     */
    default ShipmentPo selectByNo(long tenantId, String shpNo) {
        return selectOne(Wrappers.<ShipmentPo>lambdaQuery()
                .eq(ShipmentPo::getTenantId, tenantId)
                .eq(ShipmentPo::getShpNo, shpNo)
                .last("limit 1"));
    }

    /**
     * 按出库单号查装运单（出库取消联动作废用）。
     *
     * @param tenantId 租户
     * @param doNo     出库单号
     * @return 装运单，不存在返回 {@code null}
     */
    default ShipmentPo selectByDoNo(long tenantId, String doNo) {
        return selectOne(Wrappers.<ShipmentPo>lambdaQuery()
                .eq(ShipmentPo::getTenantId, tenantId)
                .eq(ShipmentPo::getDoNo, doNo)
                .orderByAsc(ShipmentPo::getId)
                .last("limit 1"));
    }

    /**
     * 绑定运单号（条件更新：仅当尚未绑定，避免并发下覆盖）。
     *
     * <p>绑定同时把状态推到"已取号"：运单号存在即意味着号已取出。若运单号被其它单占用，
     * {@code uk_waybill} 会抛唯一键冲突，由 Service 翻译成 {@code 20004 面单与单据不匹配}。
     *
     * @param tenantId   租户
     * @param shpNo      装运单号
     * @param waybillNo  运单号
     * @param operatorId 操作人
     * @return 影响行数（0 = 已绑定或状态不允许，由 Service 判定是幂等还是冲突）
     */
    @Update("""
            UPDATE lg_shipment
               SET waybill_no = #{waybillNo}, status = '已取号', updated_by = #{operatorId}
             WHERE tenant_id = #{tenantId} AND shp_no = #{shpNo} AND deleted = 0
               AND waybill_no IS NULL AND status NOT IN ('已作废')
            """)
    int bindWaybill(@Param("tenantId") long tenantId, @Param("shpNo") String shpNo,
                    @Param("waybillNo") String waybillNo, @Param("operatorId") Long operatorId);

    /**
     * 作废（条件更新：未取号/已取号可作废；已作废不重复改）。
     *
     * <p>已揽收及之后的状态在 Service 层先拦（{@code 30003 面单不可作废}）——作废一个已揽收的面单
     * 意味着"承运商那边已经在运输"，本地标废只会让面单与实物彻底对不上。
     *
     * @param tenantId   租户
     * @param shpNo      装运单号
     * @param reason     作废原因
     * @param at         作废时间
     * @param operatorId 操作人
     * @return 影响行数（1 = 本次真的作废）
     */
    @Update("""
            UPDATE lg_shipment
               SET status = '已作废', label_void = 1, void_reason = #{reason}, void_at = #{at},
                   updated_by = #{operatorId}
             WHERE tenant_id = #{tenantId} AND shp_no = #{shpNo} AND deleted = 0
               AND status NOT IN ('已作废', '已揽收', '运输中', '已签收', '退回中', '已退回入库')
            """)
    int markVoided(@Param("tenantId") long tenantId, @Param("shpNo") String shpNo,
                   @Param("reason") String reason, @Param("at") LocalDateTime at,
                   @Param("operatorId") Long operatorId);
}
