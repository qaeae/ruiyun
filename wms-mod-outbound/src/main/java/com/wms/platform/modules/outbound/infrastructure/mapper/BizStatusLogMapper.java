package com.wms.platform.modules.outbound.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.outbound.infrastructure.po.BizStatusLogPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 单据状态流水 Mapper（表 {@code pf_biz_status_log}）。
 *
 * <p>只提供"写入一行"（{@link BaseMapper#insert}）与"按单据查轨迹"两种能力：
 * 唯一键 {@code uk_status_idem} 负责状态迁移幂等，插入冲突时由上层按幂等语义处理
 * （与"重复提交"区分：真正的重复提交在入口层就被 {@code pf_idempotent} 拦下了）。
 */
@Mapper
public interface BizStatusLogMapper extends BaseMapper<BizStatusLogPo> {

    /**
     * 按业务单据查状态轨迹（API-OUT-13 {@code /{doNo}/status-logs}）。
     *
     * @param tenantId 租户
     * @param bizType  业务类型（出库单为 {@code DO}）
     * @param bizId    业务主键
     * @return 轨迹列表（按时间升序）
     */
    default List<BizStatusLogPo> listByBiz(long tenantId, String bizType, long bizId) {
        return selectList(Wrappers.<BizStatusLogPo>lambdaQuery()
                .eq(BizStatusLogPo::getTenantId, tenantId)
                .eq(BizStatusLogPo::getBizType, bizType)
                .eq(BizStatusLogPo::getBizId, bizId)
                .orderByAsc(BizStatusLogPo::getCreatedAt, BizStatusLogPo::getId));
    }
}
