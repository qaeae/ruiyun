package com.wms.platform.modules.pda.infrastructure.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.modules.pda.infrastructure.po.PdaConflictPo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * PDA 补传冲突任务 Mapper（表 {@code pf_pda_conflict}）。
 *
 * <p>规范落地（B1）：Wrapper 只出现在本接口的 {@code default} 方法里。
 */
@Mapper
public interface PdaConflictMapper extends BaseMapper<PdaConflictPo> {

    /**
     * 按幂等键查冲突任务（判断是否已生成过，避免重复补传在人工待办里刷屏）。
     *
     * @param tenantId 租户 ID
     * @param idemKey  幂等键
     * @return 冲突任务，不存在返回 {@code null}
     */
    default PdaConflictPo selectByKey(long tenantId, String idemKey) {
        return selectOne(Wrappers.<PdaConflictPo>lambdaQuery()
                .eq(PdaConflictPo::getTenantId, tenantId)
                .eq(PdaConflictPo::getIdemKey, idemKey)
                .last("limit 1"));
    }

    /**
     * 列出待人工处理的冲突（{@code GET /pda/conflicts}）。
     *
     * @param tenantId 租户 ID
     * @param limit    最大条数
     * @return 按受理时间正序（先发生先处理）
     */
    default List<PdaConflictPo> selectPending(long tenantId, int limit) {
        LambdaQueryWrapper<PdaConflictPo> wrapper = Wrappers.<PdaConflictPo>lambdaQuery()
                .eq(PdaConflictPo::getTenantId, tenantId)
                .eq(PdaConflictPo::getStatus, PdaConflictPo.STATUS_PENDING)
                .orderByAsc(PdaConflictPo::getOccurredAt);
        if (limit > 0) {
            wrapper.last("limit " + limit);
        }
        return selectList(wrapper);
    }
}
