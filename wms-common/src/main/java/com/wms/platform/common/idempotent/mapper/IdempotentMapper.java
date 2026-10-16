package com.wms.platform.common.idempotent.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.platform.common.idempotent.po.IdempotentPo;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 幂等记录 Mapper（表 {@code pf_idempotent}）。
 *
 * <p>规范落地（B1）：<b>所有 Wrapper / SQL 只允许写在本接口的 {@code default} 方法里</b>，
 * 应用服务（{@code DbIdempotentService}）不得出现 {@code Wrappers}——由 {@code wms-archtest} 阻断。
 * 收益：SQL 与表结构集中一处，服务层只表达业务语义。
 *
 * <p>包位说明：按 B1/B3 落在 {@code ..mapper} 包，便于架构规则识别（{@code common} 的横切
 * Mapper 与业务域 Mapper 遵守同一约定，不设例外）。
 */
@Mapper
public interface IdempotentMapper extends BaseMapper<IdempotentPo> {

    /**
     * 按租户 + 幂等键查询记录。
     *
     * @param tenantId 租户 ID
     * @param idemKey  幂等键
     * @return 记录，不存在返回 {@code null}
     */
    default IdempotentPo selectByKey(long tenantId, String idemKey) {
        return selectOne(Wrappers.<IdempotentPo>lambdaQuery()
                .eq(IdempotentPo::getTenantId, tenantId)
                .eq(IdempotentPo::getIdemKey, idemKey)
                .last("limit 1"));
    }

    /**
     * 更新状态（可同时写入响应缓存）。
     *
     * @param tenantId     租户 ID
     * @param idemKey      幂等键
     * @param status       目标状态
     * @param responseJson 响应缓存，{@code null} 表示不更新该列
     * @return 受影响行数
     */
    default int updateStatus(long tenantId, String idemKey, String status, String responseJson) {
        LambdaUpdateWrapper<IdempotentPo> wrapper = Wrappers.<IdempotentPo>lambdaUpdate()
                .eq(IdempotentPo::getTenantId, tenantId)
                .eq(IdempotentPo::getIdemKey, idemKey)
                .set(IdempotentPo::getStatus, status)
                .set(IdempotentPo::getUpdatedAt, LocalDateTime.now());
        if (responseJson != null) {
            wrapper.set(IdempotentPo::getResponseJson, responseJson);
        }
        return update(null, wrapper);
    }

    /**
     * 把失败记录重置为"处理中"，允许调用方复用同一幂等键重试。
     *
     * @param tenantId 租户 ID
     * @param idemKey  幂等键
     * @return 受影响行数
     */
    default int resetToProcessing(long tenantId, String idemKey) {
        return update(null, Wrappers.<IdempotentPo>lambdaUpdate()
                .eq(IdempotentPo::getTenantId, tenantId)
                .eq(IdempotentPo::getIdemKey, idemKey)
                .set(IdempotentPo::getStatus, IdempotentPo.STATUS_PROCESSING)
                .set(IdempotentPo::getUpdatedAt, LocalDateTime.now()));
    }
}
