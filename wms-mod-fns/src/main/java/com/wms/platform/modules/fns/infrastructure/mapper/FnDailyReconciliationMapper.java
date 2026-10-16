package com.wms.platform.modules.fns.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.fns.infrastructure.po.FnDailyReconciliationPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 日终勾稽 Mapper（表 {@code fn_daily_reconciliation}）。 */
@Mapper
public interface FnDailyReconciliationMapper extends BaseMapper<FnDailyReconciliationPo> {

    /**
     * 查某天某层。
     *
     * @param tenantId 租户
     * @param bizDate  业务日期
     * @param layer    层
     * @return 记录，不存在返回 {@code null}
     */
    default FnDailyReconciliationPo selectByDateLayer(long tenantId, LocalDate bizDate, String layer) {
        return selectOne(Wrappers.<FnDailyReconciliationPo>lambdaQuery()
                .eq(FnDailyReconciliationPo::getTenantId, tenantId)
                .eq(FnDailyReconciliationPo::getBizDate, bizDate)
                .eq(FnDailyReconciliationPo::getLayer, layer)
                .last("limit 1"));
    }

    /**
     * 按 ID 查（含租户过滤）。
     *
     * @param tenantId 租户
     * @param id       记录 ID
     * @return 记录，不存在返回 { null}
     */
    default FnDailyReconciliationPo selectByIdSafe(long tenantId, long id) {
        return selectOne(Wrappers.<FnDailyReconciliationPo>lambdaQuery()
                .eq(FnDailyReconciliationPo::getTenantId, tenantId)
                .eq(FnDailyReconciliationPo::getId, id)
                .last("limit 1"));
    }

    /**
     * 查某天四层。
     *
     * @param tenantId 租户
     * @param bizDate  业务日期
     * @return 记录列表（按层排序，结果稳定）
     */
    default List<FnDailyReconciliationPo> selectByDate(long tenantId, LocalDate bizDate) {
        return selectList(Wrappers.<FnDailyReconciliationPo>lambdaQuery()
                .eq(FnDailyReconciliationPo::getTenantId, tenantId)
                .eq(FnDailyReconciliationPo::getBizDate, bizDate)
                .orderByAsc(FnDailyReconciliationPo::getLayer));
    }

    /**
     * 覆盖写勾稽结论（**条件更新：已处理的不再被重跑覆盖**）。
     *
     * <p>为什么"已处理"不能被覆盖：人工已确认结论并写明理由，重跑把状态打回"有差异"
     * 会让人重复劳动，也会让处理痕迹失去意义（下次就没人愿意再处理了）。
     *
     * @param tenantId       租户
     * @param bizDate        业务日期
     * @param layer          层
     * @param platformAmount 平台侧金额
     * @param channelAmount  对方侧金额
     * @param diffAmount     差额
     * @param diffCount      差异笔数
     * @param status         状态
     * @param detailJson     差异明细
     * @param operatorId     操作人
     * @return 影响行数（0 = 已处理，未覆盖）
     */
    @Update("UPDATE fn_daily_reconciliation SET platform_amount = #{platformAmount}, "
            + "channel_amount = #{channelAmount}, diff_amount = #{diffAmount}, diff_count = #{diffCount}, "
            + "status = #{status}, detail_json = #{detailJson}, updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND biz_date = #{bizDate} AND layer = #{layer} "
            + "AND status <> '已处理' AND deleted = 0")
    int updateResult(@Param("tenantId") long tenantId, @Param("bizDate") LocalDate bizDate,
                     @Param("layer") String layer, @Param("platformAmount") BigDecimal platformAmount,
                     @Param("channelAmount") BigDecimal channelAmount, @Param("diffAmount") BigDecimal diffAmount,
                     @Param("diffCount") int diffCount, @Param("status") String status,
                     @Param("detailJson") String detailJson, @Param("operatorId") Long operatorId);

    /**
     * 标记差异已处理（条件更新：仅「有差异」可标记）。
     *
     * @param tenantId   租户
     * @param id         记录 ID
     * @param remark     处理说明（写进 detail_json 便于追溯）
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Update("UPDATE fn_daily_reconciliation SET status = '已处理', handled_by = #{operatorId}, handled_at = NOW(3), "
            + "detail_json = JSON_SET(IFNULL(detail_json, JSON_OBJECT()), '$.handleRemark', #{remark}), "
            + "updated_by = #{operatorId} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = '有差异' AND deleted = 0")
    int markHandled(@Param("tenantId") long tenantId, @Param("id") long id, @Param("remark") String remark,
                    @Param("operatorId") Long operatorId);
}
