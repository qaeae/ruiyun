package com.wms.platform.modules.outbound.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.outbound.infrastructure.po.OutOrderItemPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 出库单明细 Mapper（表 {@code out_order_item}）。
 *
 * <p>明细的更新一律按"业务动作"给出语义方法（回填拣货结果、回填库位），**不暴露通用 update**：
 * 明细上的 {@code qty}/{@code pickedQty} 是缺货判定与库存回滚的依据，被随意改会让
 * "缺货差额"与"实际释放的预占"对不上。
 */
@Mapper
public interface OutOrderItemMapper extends BaseMapper<OutOrderItemPo> {

    /**
     * 列出某张出库单的明细（按行号升序，保证幂等动作顺序一致）。
     *
     * @param tenantId 租户
     * @param doId     出库单 ID
     * @return 明细列表
     */
    default List<OutOrderItemPo> listByDo(long tenantId, long doId) {
        return selectList(Wrappers.<OutOrderItemPo>lambdaQuery()
                .eq(OutOrderItemPo::getTenantId, tenantId)
                .eq(OutOrderItemPo::getDoId, doId)
                .orderByAsc(OutOrderItemPo::getLineNo));
    }

    /**
     * 回填拣货库位（派发拣货时执行；出库扣减必须带库位，否则库位账不平）。
     *
     * @param tenantId   租户
     * @param id         明细 ID
     * @param locationId 库位 ID
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Update("UPDATE out_order_item SET location_id = #{locationId}, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted = 0")
    int fillLocation(@Param("tenantId") long tenantId, @Param("id") long id,
                     @Param("locationId") Long locationId, @Param("operatorId") Long operatorId);

    /**
     * 回填实拣数量（拣货完成上报；缺货时只报到实拣量）。
     *
     * @param tenantId   租户
     * @param id         明细 ID
     * @param pickedQty  实拣数量
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Update("UPDATE out_order_item SET picked_qty = #{pickedQty}, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted = 0")
    int fillPickedQty(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("pickedQty") BigDecimal pickedQty, @Param("operatorId") Long operatorId);
}
