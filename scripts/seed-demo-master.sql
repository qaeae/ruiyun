-- ==========================================================================================
-- 演示用主数据（**仅本地/验证库**）：让"多商家、多商品、多库位、多行明细"都能点出来。
--
-- 为什么主数据走 SQL 而订单走接口：
--   主数据是"配置"（商家/商品/库位），订单是"业务"。业务数据必须经接口产生，
--   否则库存三态、冻结、装运单、状态轨迹这些副作用都不会有——那样的假数据点开就自相矛盾。
--
-- 执行：
--   mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 wms_docker < scripts/seed-demo-master.sql
--
-- 说明：可重复执行（ON DUPLICATE KEY UPDATE）。ID 固定，便于脚本与文档互相引用。
-- ==========================================================================================
SET NAMES utf8mb4;

-- 1. 仓库（截单 18:00：18:00 前建单为当日单，之后为次日单，列表里能看出批次差异）
INSERT INTO md_warehouse (id, tenant_id, warehouse_code, name, province, city, address, cutoff_time, status)
VALUES (1, 1, 'WH-DEMO', '演示仓（上海）', '上海市', '上海市', '浦东新区张江路 1 号', '18:00:00', '启用')
ON DUPLICATE KEY UPDATE cutoff_time = VALUES(cutoff_time), name = VALUES(name);

-- 2. 商家：一号（主）、二号（用于"按商家筛选"）
INSERT INTO md_merchant (id, tenant_id, merchant_no, name, status)
VALUES (1, 1, 'M-DEMO-1', '演示商家一', '正常'),
       (2, 1, 'M-DEMO-2', '演示商家二', '正常')
ON DUPLICATE KEY UPDATE name = VALUES(name), status = '正常';

-- 3. 库区与库位：A 区两个拣货位
INSERT INTO md_zone (id, tenant_id, warehouse_id, zone_code, zone_type)
VALUES (4001, 1, 1, 'Z-A', '存储区'),
       (4002, 1, 1, 'Z-B', '存储区')
ON DUPLICATE KEY UPDATE zone_code = VALUES(zone_code);

INSERT INTO md_location (id, tenant_id, warehouse_id, zone_id, location_code, is_pickable, status)
VALUES (2001, 1, 1, 4001, 'V-01-01-01', 1, '启用'),
       (2002, 1, 1, 4002, 'V-01-01-02', 1, '启用')
ON DUPLICATE KEY UPDATE is_pickable = 1, status = '启用';

-- 4. 商品：商家一两个（可下多行明细）、商家二一个（验商家隔离）
INSERT INTO md_sku (id, tenant_id, merchant_id, sku_code, name, unit, weight_kg, status)
VALUES (1001, 1, 1, 'SKU-V-001', '演示商品 A（件装 2kg）', '件', 2.0000, '启用'),
       (1003, 1, 1, 'SKU-V-003', '演示商品 C（箱装 12.5kg）', '箱', 12.5000, '启用'),
       (1002, 1, 2, 'SKU-V-002', '演示商家二商品（1.2kg）', '件', 1.2000, '启用')
ON DUPLICATE KEY UPDATE name = VALUES(name), status = '启用';

-- 5. 资金账户：余额充足，避免造单被 20001 拦住
INSERT INTO ac_merchant_account (id, tenant_id, merchant_id, balance, freeze_amount,
                                 deposit_amount, credit_limit, version, deleted)
VALUES (3001, 1, 1, 50000.0000, 0, 0, 0, 0, 0),
       (3002, 1, 2, 50000.0000, 0, 0, 0, 0, 0)
ON DUPLICATE KEY UPDATE balance = VALUES(balance), deleted = 0;

-- 6. 账号与角色（演示两个身份：全权 / 只读）
--    口令均为 ItPassw0rd!（PBKDF2-HMAC-SHA256 / 210000 次 / 16 字节盐）
INSERT INTO au_role (id, tenant_id, role_code, name, status)
VALUES (9101, 1, 'IT_ADMIN_TEST', '演示超管角色', '启用'),
       (9102, 1, 'IT_WAREHOUSE', '演示仓内只读角色', '启用')
ON DUPLICATE KEY UPDATE status = '启用';

INSERT INTO au_permission (id, tenant_id, perm_code, name, perm_type)
VALUES (9201, 1, 'stock:query', '库存查询', '接口'),
       (9202, 1, 'outbound:order:query', '出库单查询', '接口')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO au_user_role (id, tenant_id, user_id, role_id)
VALUES (9301, 1, 9001, 9101), (9302, 1, 9002, 9102)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO au_role_permission (id, tenant_id, role_id, perm_id)
VALUES (9401, 1, 9102, 9201), (9402, 1, 9102, 9202)
ON DUPLICATE KEY UPDATE perm_id = VALUES(perm_id);
