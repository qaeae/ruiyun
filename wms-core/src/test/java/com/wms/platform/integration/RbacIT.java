package com.wms.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.modules.au.api.ro.CreateUserRo;
import com.wms.platform.modules.au.api.ro.RoleCreateRo;
import com.wms.platform.modules.au.app.RbacAdminService;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.poc.PocMysqlCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC 骨架集成测试（W3，03 API-AUTH-01/02/03）。
 *
 * <p>**它验证的是"真的拦住了"**，而不是"类和方法都在"：
 * 未带令牌 → 401；令牌合法但缺权限 → 403；权限到位 → 200；
 * 撤销权限 + 刷新令牌 → 立刻 403（证明撤销可达）；令牌篡改 → 401。
 *
 * <p>运行方式：{@code mvn -pl wms-core -am verify -Dit.skip=false -Dit.test=RbacIT
 * -Dpoc.mysql.url=... -Dpoc.mysql.user=root -Dpoc.mysql.password=}
 */
@ExtendWith(PocMysqlCondition.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RBAC 骨架（真实库 + 真实 HTTP 链路）")
class RbacIT {

    /** 租户。 */
    private static final long TENANT = 1L;

    /** Testcontainers 容器（外部模式为 null）。 */
    private static MySQLContainer<?> container;

    /**
     * 注入数据源（外部 MySQL 优先）。
     *
     * @param registry 属性注册器
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("poc.mysql.user", "root"));
            registry.add("spring.datasource.password", () -> System.getProperty("poc.mysql.password", ""));
        } else {
            container = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("wms_w3").withUsername("poc").withPassword("poc");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        // 令牌密钥：测试用非默认值，避免启动自检在生产 profile 下的干扰
        registry.add("wms.security.token-secret", () -> "it-secret-for-rbac-0123456789abcdef");
        registry.add("spring.flyway.enabled", () -> true);
    }

    /** 数据访问。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** RBAC 管理服务（造数据用）。 */
    @Autowired
    private RbacAdminService rbacAdminService;

    /** HTTP 客户端。 */
    @Autowired
    private MockMvc mockMvc;

    /** JSON。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 每个用例重置权限数据。 */
    @BeforeEach
    void resetFixtures() {
        for (String table : new String[]{"au_user_role", "au_role_permission", "au_user_warehouse", "au_permission",
                "au_role", "au_user"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
    }

    @Test
    @DisplayName("登录 → 令牌 → 访问受保护接口：无令牌 401、缺权限 403、有权限 200")
    void loginAndEnforcement() throws Exception {
        // 1. 造角色与用户：仓管只有 stock:query
        setUpUser("keeper", "KeeperPass1", false, List.of(PermissionCodes.STOCK_QUERY));

        // 2. 登录拿令牌
        String token = login("keeper", "KeeperPass1");
        assertThat(token).isNotBlank();

        // 3. 无令牌访问受保护接口 → 401 + 40300（未认证）
        mockMvc.perform(get("/api/v1/inventory/sku").param("warehouseId", "1").param("merchantId", "1")
                        .param("skuId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40300));

        // 4. 有 stock:query → 200
        mockMvc.perform(get("/api/v1/inventory/sku").param("warehouseId", "1").param("merchantId", "1")
                        .param("skuId", "1").header(HeaderConst.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 5. 缺 auth:role:manage → 403 + 40300（已认证但无权限）
        mockMvc.perform(post("/api/v1/auth/roles")
                        .header(HeaderConst.AUTHORIZATION, "Bearer " + token)
                        .header(HeaderConst.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"HACKER\",\"name\":\"越权尝试\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        print("登录与拦截", "无令牌 401；缺权限 403；有权限 200（HTTP 状态码区分「未认证/无权限」）");
    }

    @Test
    @DisplayName("撤销权限 + 刷新令牌 → 立刻失效（撤销必须确定可达）")
    void revokeThenRefreshTakesEffect() throws Exception {
        // 1. 造角色（含 stock:query）与用户
        Long roleId = setUpUser("keeper2", "KeeperPass2", false, List.of(PermissionCodes.STOCK_QUERY));
        String token = login("keeper2", "KeeperPass2");

        // 2. 撤销角色上的全部权限（整批替换为空集）
        rbacAdminService.grantRolePermissions(roleId, List.of());

        // 2.1 旧令牌仍带旧权限（令牌即快照——这是设计的取舍，已在 AccessToken 注释写明）
        mockMvc.perform(get("/api/v1/inventory/sku").param("warehouseId", "1").param("merchantId", "1")
                        .param("skuId", "1").header(HeaderConst.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // 3. 刷新令牌（重新查库）→ 权限已消失 → 403
        String refreshed = refresh(token);
        mockMvc.perform(get("/api/v1/inventory/sku").param("warehouseId", "1").param("merchantId", "1")
                        .param("skuId", "1").header(HeaderConst.AUTHORIZATION, "Bearer " + refreshed))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        print("撤销可达", "撤权后旧令牌仍有效（快照取舍）；刷新令牌后立刻 403");
    }

    @Test
    @DisplayName("超管跨仓全权限；令牌被篡改 → 401")
    void adminBypassAndTamperedToken() throws Exception {
        // 1. 超管
        rbacAdminService.createUser(new CreateUserRo("boss", "BossPass123", "平台负责人", null, true));
        String adminToken = login("boss", "BossPass123");

        // 2. 超管无需显式权限即可管理角色（跨仓全权限）
        mockMvc.perform(post("/api/v1/auth/roles")
                        .header(HeaderConst.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HeaderConst.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"FINANCE\",\"name\":\"财务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 3. 篡改令牌（改 payload 不重新签名）→ 401
        String tampered = adminToken.substring(0, adminToken.length() - 4) + "AAAA";
        mockMvc.perform(post("/api/v1/auth/roles")
                        .header(HeaderConst.AUTHORIZATION, "Bearer " + tampered)
                        .header(HeaderConst.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"X\",\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40300));

        print("超管与防篡改", "超管跨仓全权限；篡改令牌签名不匹配 → 401");
    }

    @Test
    @DisplayName("登录安全：口令错误/账号停用/用户名不存在 都不能拿到令牌")
    void loginSecurity() throws Exception {
        rbacAdminService.createUser(new CreateUserRo("normal", "NormalPass1", "普通", null, false));

        // 1. 口令错误
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"normal\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40300));

        // 2. 用户名不存在（与口令错误同一提示，防用户名枚举）
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"whatever-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40300))
                .andExpect(jsonPath("$.message").value("用户名或口令错误"));

        // 3. 停用后即使口令正确也拿不到令牌
        Long userId = jdbc.queryForObject("SELECT id FROM au_user WHERE username = 'normal'", Long.class);
        rbacAdminService.changeUserStatus(userId, false);
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"normal\",\"password\":\"NormalPass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40300))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("状态不可用")));

        // 4. 口令不以明文落库
        String stored = jdbc.queryForObject("SELECT password_hash FROM au_user WHERE id = " + userId, String.class);
        assertThat(stored).startsWith("pbkdf2$").doesNotContain("NormalPass1");

        print("登录安全", "口令错误/不存在同提示（防枚举）；停用账号拒绝；口令密文落库");
    }

    @Test
    @DisplayName("权限目录同步：代码里登记的权限码能幂等补齐到权限表")
    void permissionCatalogSync() {
        // 1. 首次同步：补齐全部登记的权限码
        int first = rbacAdminService.syncPermissionCatalog();
        assertThat(first).isEqualTo(PermissionCodes.all().size());

        // 2. 再次同步：幂等，不再新增（否则界面会出现重复权限项）
        int second = rbacAdminService.syncPermissionCatalog();
        assertThat(second).isZero();
        assertThat(rbacAdminService.listPermissionCodes()).hasSize(PermissionCodes.all().size());

        print("权限目录同步", "首次补齐 " + first + " 条，重复执行新增 0 条（幂等）");
    }

    // ===================== 工具 =====================

    /**
     * 造角色 + 用户 + 授权。
     *
     * @param username    登录名
     * @param password    口令
     * @param admin       是否超管
     * @param permCodes   角色权限
     * @return 角色 ID
     */
    private Long setUpUser(String username, String password, boolean admin, List<String> permCodes) {
        // 1. 权限目录必须先同步（否则授权时校验"权限码未登记"会失败——这正是设计的强校验）
        rbacAdminService.syncPermissionCatalog();
        // 2. 角色 + 授权限
        String roleCode = "ROLE_" + username.toUpperCase();
        Long roleId = rbacAdminService.createRole(new RoleCreateRo(roleCode, username + " 角色", null));
        rbacAdminService.grantRolePermissions(roleId, permCodes);
        // 3. 用户 + 授角色 + 授仓库
        Long userId = rbacAdminService.createUser(new CreateUserRo(username, password, username, null, admin));
        rbacAdminService.assignUserRoles(userId, List.of(roleCode));
        rbacAdminService.assignUserWarehouses(userId, List.of(1L));
        return roleId;
    }

    /**
     * 登录并取令牌。
     *
     * @param username 登录名
     * @param password 口令
     * @return 令牌
     * @throws Exception 请求异常
     */
    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /**
     * 刷新令牌。
     *
     * @param token 现有令牌
     * @return 新令牌
     * @throws Exception 请求异常
     */
    private String refresh(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HeaderConst.AUTHORIZATION, "Bearer " + token)
                        .header(HeaderConst.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /**
     * 打印实验报告行。
     *
     * @param step   步骤
     * @param detail 明细
     */
    private void print(String step, String detail) {
        System.out.printf("[W3-AUTH] %s | %s%n", step, detail);
    }

    @Test
    @DisplayName("整批替换语义：重复授权两次都成功；第二次是替换而非叠加（回归：曾因逻辑删除残留撞唯一键报 19999）")
    void grantTwiceReplacesNotAccumulates() {
        com.wms.platform.common.context.TenantContext.set(TENANT, 1L, java.util.Set.of(1L), null);
        try {
            // 权限目录可能还没同步（用例顺序不确定）→ 先跑一次幂等同步
            rbacAdminService.syncPermissionCatalog();
            String code = "IT_ROLE_" + (System.nanoTime() % 1000000);
            Long roleId = rbacAdminService.createRole(
                    new com.wms.platform.modules.au.api.ro.RoleCreateRo(code, code, "it"));
            rbacAdminService.grantRolePermissions(roleId, java.util.List.of("master:query", "stock:query"));
            rbacAdminService.grantRolePermissions(roleId, java.util.List.of("master:query", "stock:query"));
            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM au_role_permission WHERE role_id = ? AND deleted = 0", Integer.class, roleId);
            assertThat(rows).as("整批替换后行数等于集合大小（替换而非叠加）").isEqualTo(2);
            rbacAdminService.grantRolePermissions(roleId, java.util.List.of("master:query"));
            Integer after = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM au_role_permission WHERE role_id = ? AND deleted = 0", Integer.class, roleId);
            assertThat(after).as("缩小集合后只剩 1 条（撤销可达）").isEqualTo(1);
        } finally {
            com.wms.platform.common.context.TenantContext.clear();
        }
    }
}