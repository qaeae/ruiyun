package com.wms.platform.modules.pda.infrastructure.mapper;

import com.wms.platform.modules.pda.infrastructure.po.BizStatusRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 单据状态只读查询（DO / ASN）。
 *
 * <p><b>⚠️ 这是 W1 的临时跨域只读缝隙（已登记在 {@code STANDARDS-MAPPING.md} §八 偏差登记）</b>：
 * 补传判定需要"服务端当前单据状态"，而 {@code outbound}/{@code inbound} 两个域在 W1 尚未落地
 * （它们的状态机属于 W3 出库全流程）。为不把 W3 的工作提前半做，这里只放**两条只读 SELECT**，
 * 等 `outbound.app` / `inbound.app` 就位后改为调用各域的 app 服务，本类随即删除。
 *
 * <p>它只读、不写：状态变更一律仍由各域状态机负责（`01` §1.2），所以不会造成"绕过状态机"的风险。
 */
@Mapper
public interface SyncBizStatusMapper {

    /**
     * 查出库单状态与归属信息。
     *
     * @param tenantId 租户 ID
     * @param doNo     出库单号
     * @return 单据投影，不存在返回 {@code null}
     */
    @Select("""
            SELECT id, status, merchant_id AS merchantId, warehouse_id AS warehouseId
              FROM out_order
             WHERE tenant_id = #{tenantId} AND do_no = #{doNo} AND deleted = 0
             LIMIT 1
            """)
    BizStatusRow selectDo(@Param("tenantId") long tenantId, @Param("doNo") String doNo);

    /**
     * 查入库通知单状态与归属信息。
     *
     * @param tenantId 租户 ID
     * @param asnNo    入库通知单号
     * @return 单据投影，不存在返回 {@code null}
     */
    @Select("""
            SELECT id, status, merchant_id AS merchantId, warehouse_id AS warehouseId
              FROM in_asn
             WHERE tenant_id = #{tenantId} AND asn_no = #{asnNo} AND deleted = 0
             LIMIT 1
            """)
    BizStatusRow selectAsn(@Param("tenantId") long tenantId, @Param("asnNo") String asnNo);
}
