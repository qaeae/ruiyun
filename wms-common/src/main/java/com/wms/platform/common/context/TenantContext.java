package com.wms.platform.common.context;

import cn.hutool.core.collection.CollUtil;

import java.util.Collections;
import java.util.Set;

/**
 * 请求级上下文：租户 / 操作人 / 仓库范围（{@code 项目一方案.md} §7、{@code 03} §1.1）。
 *
 * <p>一期单租户（{@code tenantId = 1}），但**所有查询强制注入 tenantId**、仓库侧角色再按
 * {@code warehouseIds} 过滤 —— 一期就按多租户 + 多仓建模，二期不返工。
 *
 * <p>使用约定：由认证过滤器在请求入口 {@link #set}，请求结束 {@link #clear}（必须在 finally 中调用）。
 */
public final class TenantContext {

    /** 一期默认租户：平台自营。 */
    public static final long DEFAULT_TENANT_ID = 1L;

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(long tenantId, Long userId, Set<Long> warehouseIds, String deviceCode) {
        // B10：需要构造集合时用 CollUtil；这里同时保持不可变（只读语义），避免调用方误改上下文
        Set<Long> scoped = CollUtil.isEmpty(warehouseIds)
                ? Collections.emptySet()
                : Collections.unmodifiableSet(CollUtil.newHashSet(warehouseIds));
        HOLDER.set(new Holder(tenantId, userId, scoped, deviceCode));
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static long tenantId() {
        Holder holder = HOLDER.get();
        return holder == null ? DEFAULT_TENANT_ID : holder.tenantId();
    }

    public static Long userId() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.userId();
    }

    public static Set<Long> warehouseIds() {
        Holder holder = HOLDER.get();
        return holder == null ? Collections.emptySet() : holder.warehouseIds();
    }

    public static String deviceCode() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.deviceCode();
    }

    /** 是否在指定仓库的数据权限范围内；未登录或未授权时返回 false（默认拒绝）。 */
    public static boolean hasWarehouse(long warehouseId) {
        return warehouseIds().contains(warehouseId);
    }

    private record Holder(long tenantId, Long userId, Set<Long> warehouseIds, String deviceCode) {
    }
}
