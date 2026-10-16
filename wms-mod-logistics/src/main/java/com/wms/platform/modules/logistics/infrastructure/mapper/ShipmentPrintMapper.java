package com.wms.platform.modules.logistics.infrastructure.mapper;

import com.wms.platform.modules.logistics.infrastructure.po.ShipmentPrintRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 面单打印登记 Mapper（表 {@code lg_shipment}）。
 *
 * <p>规范落地（B1）：这里用的是**声明式 SQL（{@code @Select}/{@code @Update}）**，
 * 同样满足"SQL 只允许出现在 Mapper 层"的要求——不必为了用 Wrapper 而建一个不完整的 Po。
 *
 * <p>为什么用窄投影 + 声明式 SQL：打印登记只碰"读 6 个字段 + 累加计数"两件事，
 * 声明式 SQL 让"改了哪张表、哪几列"一眼可见，比 Wrapper 更好评审。
 */
@Mapper
public interface ShipmentPrintMapper {

    /**
     * 查询打印所需的运单信息。
     *
     * @param tenantId 租户 ID
     * @param shpNo    装运单号
     * @return 运单投影，不存在返回 {@code null}
     */
    @Select("""
            SELECT shp_no AS shpNo,
                   waybill_no AS waybillNo,
                   label_url AS labelUrl,
                   label_print_count AS printCount,
                   label_void AS labelVoid,
                   status
              FROM lg_shipment
             WHERE tenant_id = #{tenantId} AND shp_no = #{shpNo} AND deleted = 0
             LIMIT 1
            """)
    ShipmentPrintRow selectPrintInfo(@Param("tenantId") long tenantId, @Param("shpNo") String shpNo);

    /**
     * 累加已打印张数（只在真正出纸后调用）。
     *
     * @param tenantId 租户 ID
     * @param shpNo    装运单号
     * @param sheets   本次出纸张数
     * @param userId   操作人
     * @return 受影响行数
     */
    @Update("""
            UPDATE lg_shipment
               SET label_print_count = label_print_count + #{sheets},
                   updated_at = NOW(3),
                   updated_by = #{userId}
             WHERE tenant_id = #{tenantId} AND shp_no = #{shpNo} AND deleted = 0
            """)
    int increasePrintCount(@Param("tenantId") long tenantId, @Param("shpNo") String shpNo,
                           @Param("sheets") int sheets, @Param("userId") Long userId);
}
