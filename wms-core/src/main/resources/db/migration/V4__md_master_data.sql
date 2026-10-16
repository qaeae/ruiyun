-- 主数据补建（2026-10-14 随 W3 开工补，与 01 §3.12 同源，请勿手工编辑本文件）
--
-- 为什么补建：01 §3.9 原本只有"关键约束"摘要、没有建表语句，而 03 的库存接口（API-IV-*）、
-- 出库下单、PDA 错扫判定都需要 md_sku / md_location 做"业务编码 → 内部 ID"的解析。
-- 本迁移只补 5 张与库存直接相关的主数据表；md_sku_unit_conv / md_vehicle /
-- md_category_blacklist 等按 02 M2 的任务在后续切片补（本文件留注释标记）。

-- 商家
CREATE TABLE md_merchant (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  merchant_no   VARCHAR(32) NOT NULL COMMENT '商家编号',
  name          VARCHAR(100) NOT NULL COMMENT '商家名称',
  contact_name  VARCHAR(64) NULL,
  contact_phone_enc    VARCHAR(255) NULL COMMENT '联系人手机号密文（AES-GCM）',
  contact_phone_masked VARCHAR(32)  NULL COMMENT '脱敏手机号（列表展示用）',
  status        VARCHAR(16) NOT NULL DEFAULT '正常' COMMENT '正常/停用/黑名单',
  credit_score  INT NOT NULL DEFAULT 100 COMMENT '信用分（欠费/违规扣分）',
  remark        VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_no (tenant_id, merchant_no),
  KEY idx_status (tenant_id, status)
) ENGINE=InnoDB COMMENT='商家主数据';

-- 仓库
CREATE TABLE md_warehouse (
  id             BIGINT UNSIGNED NOT NULL,
  tenant_id      BIGINT UNSIGNED NOT NULL DEFAULT 1,
  warehouse_code VARCHAR(32) NOT NULL COMMENT '仓库编码',
  name           VARCHAR(100) NOT NULL,
  province       VARCHAR(32) NULL,
  city           VARCHAR(32) NULL,
  address        VARCHAR(255) NULL,
  timezone       VARCHAR(32) NOT NULL DEFAULT 'Asia/Shanghai',
  cutoff_time    TIME NOT NULL DEFAULT '18:00:00' COMMENT '截单时间（按仓可配，01 §3.9）',
  status         VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用/停用',
  remark         VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_warehouse_code (tenant_id, warehouse_code)
) ENGINE=InnoDB COMMENT='仓库主数据';

-- 库区
CREATE TABLE md_zone (
  id             BIGINT UNSIGNED NOT NULL,
  tenant_id      BIGINT UNSIGNED NOT NULL DEFAULT 1,
  warehouse_id   BIGINT UNSIGNED NOT NULL,
  zone_code      VARCHAR(32) NOT NULL COMMENT '库区编码',
  name           VARCHAR(64) NULL,
  zone_type      VARCHAR(24) NOT NULL COMMENT '存储区/拣货区/复核区/隔离区/退货区',
  isolation_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否隔离区（02 M2-01）',
  remark         VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_zone_code (tenant_id, warehouse_id, zone_code),
  KEY idx_wh (tenant_id, warehouse_id, zone_type)
) ENGINE=InnoDB COMMENT='库区';

-- 库位（PDA 错扫判定的主数据依据）
CREATE TABLE md_location (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  warehouse_id  BIGINT UNSIGNED NOT NULL,
  zone_id       BIGINT UNSIGNED NOT NULL,
  location_code VARCHAR(64) NOT NULL COMMENT '库位编码（现场贴码）',
  abc_class     VARCHAR(4) NULL COMMENT 'ABC 分类（拣货路径优化）',
  is_pickable   TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否可拣',
  quota_unit    INT NOT NULL DEFAULT 1 COMMENT '该库位折算托盘位数（超占用）',
  status        VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用/停用',
  remark        VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_location_code (tenant_id, warehouse_id, location_code),
  KEY idx_zone (tenant_id, warehouse_id, zone_id)
) ENGINE=InnoDB COMMENT='库位主数据';

-- 商品（SKU）
CREATE TABLE md_sku (
  id               BIGINT UNSIGNED NOT NULL,
  tenant_id        BIGINT UNSIGNED NOT NULL DEFAULT 1,
  merchant_id      BIGINT UNSIGNED NOT NULL,
  sku_code         VARCHAR(64) NOT NULL COMMENT '商品条码（现场扫码值）',
  name             VARCHAR(128) NOT NULL,
  category         VARCHAR(48) NULL COMMENT '品类（禁入品类校验用）',
  unit             VARCHAR(16) NOT NULL DEFAULT '件' COMMENT '基本单位：件/箱/托',
  weight_kg        DECIMAL(12,4) NULL COMMENT '单件重量（运费预估基准，M4-03）',
  volume_ratio     DECIMAL(12,4) NULL COMMENT '体积（或抛比基准）',
  shelf_life_days  INT NULL COMMENT '保质期天数（效期管理；一期仅登记）',
  is_forbidden     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否禁入品类（冷链/危品）',
  forbidden_reason VARCHAR(255) NULL,
  status           VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用/停用',
  remark           VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_code (tenant_id, merchant_id, sku_code),
  KEY idx_merchant (tenant_id, merchant_id, status)
) ENGINE=InnoDB COMMENT='商品主数据';

-- 待补（02 M2 后续切片）：md_sku_unit_conv（件/箱/托换算，计费必需）、
-- md_vehicle（车辆/司机档案）、md_category_blacklist（禁入品类黑名单字典）。
