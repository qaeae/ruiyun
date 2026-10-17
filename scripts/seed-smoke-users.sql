-- ==========================================================================================
-- 联调/冒烟专用账号（**仅限本地与验证库**，禁止用于任何真库）
--
-- 用途：给 `scripts/contract-smoke.mjs` 提供两类身份，验证契约里最容易错的两条：
--   1) 超管（admin_flag=1）→ 全权限通过
--   2) 作业员（admin_flag=0，只有 outbound:order:query）→ 写接口必须 403 + 40300
--
-- 口令：ItPassw0rd!   （PBKDF2WithHmacSHA256 / 210000 次 / 16 字节盐，格式见 01 §3.14）
--   密文是按该口令预生成的；**换口令请用一次性的生成器重新生成**，不要复用这条密文。
--   复现生成方式（Node，本机 python 是商店占位符不能用）：
--     node -e "const{randomBytes,pbkdf2Sync}=require('crypto');const s=randomBytes(16);
--              console.log('pbkdf2\$H256\$210000\$'+s.toString('base64')+'\$'+
--              pbkdf2Sync('ItPassw0rd!',s,210000,32,'sha256').toString('base64'))"
--
-- 执行：mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 wms_docker < scripts/seed-smoke-users.sql
--   Windows 上 mysql 客户端默认 gbk，**必须带 --default-character-set=utf8mb4**，否则中文姓名写库报
--   ERROR 1366 Incorrect string value（文件里的 SET NAMES 只管服务端会话，管不了客户端编码）
-- ==========================================================================================
SET NAMES utf8mb4;

SET @hash = 'pbkdf2$H256$210000$7QDIqN+TsS673gt8HdRaqg==$GsMPzVtOmgAbgYyrx08ziS2jmQxhzL08ZL4f3BuacxQ=';
SET @tenant = 1;

-- 1. 用户：超管 + 作业员（id 固定，方便重复执行时覆盖）
INSERT INTO au_user (id, tenant_id, username, password_hash, name, status, admin_flag, remark)
VALUES (9001, @tenant, 'it_admin', @hash, '联调超管', '启用', 1, 'contract-smoke 专用'),
       (9002, @tenant, 'it_worker', @hash, '联调作业员', '启用', 0, 'contract-smoke 专用')
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = '启用';

-- 2. 角色
INSERT INTO au_role (id, tenant_id, role_code, name, status, remark)
VALUES (9101, @tenant, 'IT_ADMIN_TEST', '联调用超管角色', '启用', 'contract-smoke 专用'),
       (9102, @tenant, 'IT_WAREHOUSE', '联调仓内角色', '启用', 'contract-smoke 专用')
ON DUPLICATE KEY UPDATE status = '启用';

-- 3. 权限点（只给作业员两条：能查、不能建）
INSERT INTO au_permission (id, tenant_id, perm_code, name, perm_type)
VALUES (9201, @tenant, 'stock:query', '库存查询', '接口'),
       (9202, @tenant, 'outbound:order:query', '出库单查询', '接口')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 4. 关联
INSERT INTO au_user_role (id, tenant_id, user_id, role_id)
VALUES (9301, @tenant, 9001, 9101), (9302, @tenant, 9002, 9102)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO au_role_permission (id, tenant_id, role_id, perm_id)
VALUES (9401, @tenant, 9102, 9201), (9402, @tenant, 9102, 9202)
ON DUPLICATE KEY UPDATE perm_id = VALUES(perm_id);
