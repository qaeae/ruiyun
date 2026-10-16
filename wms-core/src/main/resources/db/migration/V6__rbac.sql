-- RBAC 骨架（2026-10-14 随 W3 补建，与 01 §3.14 同源，请勿手工编辑本文件）
--
-- 为什么补建：03 定义了 API-AUTH-01~06（登录、角色与权限分配、仓库数据权限、敏感操作复核、审计查询），
-- 但 01 的基线 DDL 里**没有任何 au_* 表**——属跨文档缺口（同 md_device、md_* 主数据）。
--
-- 本迁移只落 RBAC 骨架的 6 张表；API-AUTH-04/05 的双人复核（M2-05，4 人天）
-- 待后续切片补 au_approval，届时本文件的「复核」相关字段已预留说明。

-- 用户
CREATE TABLE au_user (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  username      VARCHAR(64) NOT NULL COMMENT '登录名（租户内唯一）',
  password_hash VARCHAR(255) NOT NULL COMMENT 'PBKDF2WithHmacSHA256 密文（含迭代次数与盐，格式见 01 §3.14）',
  name          VARCHAR(64) NOT NULL COMMENT '姓名',
  phone_enc     VARCHAR(255) NULL COMMENT '手机号密文（AES-GCM）',
  phone_masked  VARCHAR(32)  NULL COMMENT '脱敏手机号',
  status        VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用/停用/锁定',
  admin_flag    TINYINT(1) NOT NULL DEFAULT 0 COMMENT '超管（跨仓全权限）；高风险，仅平台负责人持有',
  last_login_at DATETIME(3) NULL,
  remark        VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_au_user (tenant_id, username),
  KEY idx_status (tenant_id, status)
) ENGINE=InnoDB COMMENT='用户';

-- 角色
CREATE TABLE au_role (
  id          BIGINT UNSIGNED NOT NULL,
  tenant_id   BIGINT UNSIGNED NOT NULL DEFAULT 1,
  role_code   VARCHAR(64) NOT NULL COMMENT '角色码：PLATFORM_OPS/WAREHOUSE_KEEPER/FINANCE/SECURITY/CLERK/MERCHANT',
  name        VARCHAR(64) NOT NULL,
  description VARCHAR(255) NULL,
  status      VARCHAR(16) NOT NULL DEFAULT '启用',
  remark      VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_au_role (tenant_id, role_code)
) ENGINE=InnoDB COMMENT='角色';

-- 权限点（菜单/按钮/接口三态，便于前端按权限渲染）
CREATE TABLE au_permission (
  id         BIGINT UNSIGNED NOT NULL,
  tenant_id  BIGINT UNSIGNED NOT NULL DEFAULT 1,
  perm_code  VARCHAR(96) NOT NULL COMMENT '权限码：域:资源:动作，如 stock:adjust / master:sku:create',
  name       VARCHAR(96) NOT NULL,
  perm_type  VARCHAR(16) NOT NULL DEFAULT '接口' COMMENT '菜单/按钮/接口',
  parent_id  BIGINT UNSIGNED NULL COMMENT '父权限（菜单树）',
  sort_no    INT NOT NULL DEFAULT 0,
  remark     VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_au_perm (tenant_id, perm_code),
  KEY idx_parent (tenant_id, parent_id)
) ENGINE=InnoDB COMMENT='权限点';

-- 用户-角色（**支持一人多角色**，03 API-AUTH-02）
CREATE TABLE au_user_role (
  id         BIGINT UNSIGNED NOT NULL,
  tenant_id  BIGINT UNSIGNED NOT NULL DEFAULT 1,
  user_id    BIGINT UNSIGNED NOT NULL,
  role_id    BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_au_user_role (tenant_id, user_id, role_id),
  KEY idx_role (tenant_id, role_id)
) ENGINE=InnoDB COMMENT='用户角色关联（一人多角色）';

-- 角色-权限
CREATE TABLE au_role_permission (
  id         BIGINT UNSIGNED NOT NULL,
  tenant_id  BIGINT UNSIGNED NOT NULL DEFAULT 1,
  role_id    BIGINT UNSIGNED NOT NULL,
  perm_id    BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_au_role_perm (tenant_id, role_id, perm_id),
  KEY idx_perm (tenant_id, perm_id)
) ENGINE=InnoDB COMMENT='角色权限关联';

-- 用户-仓库数据权限（**跨仓任职需显式授权**，03 API-AUTH-03）
CREATE TABLE au_user_warehouse (
  id           BIGINT UNSIGNED NOT NULL,
  tenant_id    BIGINT UNSIGNED NOT NULL DEFAULT 1,
  user_id      BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_au_user_wh (tenant_id, user_id, warehouse_id),
  KEY idx_wh (tenant_id, warehouse_id)
) ENGINE=InnoDB COMMENT='用户仓库数据权限';
