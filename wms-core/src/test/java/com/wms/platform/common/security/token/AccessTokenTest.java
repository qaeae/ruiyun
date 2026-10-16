package com.wms.platform.common.security.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌内容与数据权限测试（{@code 01} §1 数据权限：按"租户 + 仓库"过滤）。
 */
@DisplayName("访问令牌与数据权限（AccessToken）")
class AccessTokenTest {

    /** 普通仓管：有库存查询/调整权限，只授权 1 号仓。 */
    private static AccessToken keeper() {
        return new AccessToken(1001L, 1L, "kou", false, Set.of("WAREHOUSE_KEEPER"),
                Set.of("stock:query", "stock:adjust"), Set.of(1L), Instant.now(),
                Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("权限判断：有则通过，无则拒绝（任一满足）")
    void permissionCheck() {
        AccessToken token = keeper();

        assertThat(token.hasPermission("stock:query")).isTrue();
        assertThat(token.hasPermission("auth:role:manage")).isFalse();
    }

    @Test
    @DisplayName("数据权限：只授权 1 号仓 → 2 号仓被拒（跨仓任职必须显式授权）")
    void warehouseScope() {
        AccessToken token = keeper();

        assertThat(token.canAccessWarehouse(1L)).isTrue();
        assertThat(token.canAccessWarehouse(2L)).as("未授权的仓库不可访问").isFalse();
        assertThatThrownBy(() -> token.assertWarehouseAccess(2L))
                .hasMessageContaining("无该仓库的数据权限");
        assertThat(token.canAccessWarehouse(null)).as("不涉及仓库的操作不受限").isTrue();
    }

    @Test
    @DisplayName("超管跨仓全权限（高风险标记，只应由平台负责人持有）")
    void adminBypassesEverything() {
        AccessToken admin = new AccessToken(1L, 1L, "boss", true, Set.of(), Set.of(), Set.of(), Instant.now(),
                Instant.now().plusSeconds(60));

        assertThat(admin.hasPermission("any:thing")).isTrue();
        assertThat(admin.canAccessWarehouse(99L)).isTrue();
    }

    @Test
    @DisplayName("过期判定用服务端当前时间")
    void expiry() {
        Instant now = Instant.now();
        AccessToken expired = new AccessToken(1L, 1L, "u", false, Set.of(), Set.of(), Set.of(),
                now.minusSeconds(7200), now.minusSeconds(3600));

        assertThat(expired.isExpired(now)).isTrue();
        assertThat(keeper().isExpired(now)).isFalse();
    }

    @Test
    @DisplayName("空集合安全（null 归一为空集，避免 NPE 变成 500）")
    void nullCollectionsAreSafe() {
        AccessToken token = new AccessToken(1L, 1L, "u", false, null, null, null, Instant.now(),
                Instant.now().plusSeconds(60));

        assertThat(token.roleCodes()).isEmpty();
        assertThat(token.hasPermission("x")).isFalse();
        assertThat(token.canAccessWarehouse(5L)).isFalse();
    }
}
