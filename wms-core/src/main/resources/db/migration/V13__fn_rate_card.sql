-- V13（版本号必须唯一且递增：V9/V10/V11/V12 已被占用）
-- ==========================================================================================
-- 版本化价目表（P3.3 计费规则引擎的数据来源；`项目一方案.md` 已定「平台自建价目表（版本化）」）
--
-- 为什么必须"版本化"而不是"改一行价"：
--   运费要能对账、要能被商家与承运商质询。如果直接改价目表，历史运费的"为什么是这个价"
--   就永久丢失了——T+1 对账、月度结算、争议申诉全都无法复现。
--   因此：**每次调价新增一个版本**，旧版本置「停用」但不删除；
--   计费时把命中的版本号写进费用单，之后任何时候都能复算出同一个数。
--
-- 状态：草稿 → 启用 → 停用（同时最多一个启用版本，由 uk_rate_card_enabled 兜底）
-- ==========================================================================================
CREATE TABLE fn_rate_card (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  card_code     VARCHAR(32) NOT NULL COMMENT '价目表编码，如 FREIGHT-SH',
  name          VARCHAR(64) NOT NULL,
  carrier_code  VARCHAR(32) NOT NULL COMMENT '承运商编码；ALL 表示通用价目表',
  version       INT NOT NULL COMMENT '版本号，从 1 递增；每次调价 +1',
  status        VARCHAR(16) NOT NULL DEFAULT '草稿' COMMENT '草稿/启用/停用',
  buffer_ratio  DECIMAL(8,4) NOT NULL DEFAULT 0.1000 COMMENT '预估价缓冲系数（0.1 = 加价 10%）',
  effective_from DATETIME(3) NULL COMMENT '生效时间（NULL 表示立即）',
  effective_to  DATETIME(3) NULL COMMENT '失效时间（NULL 表示长期）',
  remark        VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rate_card_version (tenant_id, card_code, version),
  KEY idx_rate_card_enabled (tenant_id, carrier_code, status)
) ENGINE=InnoDB COMMENT='运费价目表（版本化：调价新增版本，旧版本停用不删除）';

-- 明细：一个版本下按「区域 + 重量区间」给出首重/续重价
CREATE TABLE fn_rate_item (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  card_id       BIGINT UNSIGNED NOT NULL,
  region_code   VARCHAR(32) NOT NULL COMMENT '目的地区域码（与商家地址/承运商分区对齐）',
  weight_from   DECIMAL(12,4) NOT NULL COMMENT '计费重下界（含）',
  weight_to     DECIMAL(12,4) NULL COMMENT '计费重上界（**不含**；NULL 表示无上限）。半开区间避免相邻档重叠',
  first_weight  DECIMAL(12,4) NOT NULL COMMENT '首重（kg）',
  first_price   DECIMAL(18,4) NOT NULL COMMENT '首重价（元）',
  add_unit      DECIMAL(12,4) NOT NULL DEFAULT 1.0000 COMMENT '续重单位（kg）',
  add_price     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '续重单价（元/单位）',
  volume_divisor DECIMAL(12,4) NOT NULL DEFAULT 6000.0000 COMMENT '体积重系数（体积cm³ / 系数 = 体积重kg）',
  min_charge    DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '最低消费（元）',
  priority      INT NOT NULL DEFAULT 0 COMMENT '同区间多规则时取优先级大者（承运商特价覆盖通用价）',
  remark        VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rate_item (tenant_id, card_id, region_code, weight_from, priority),
  KEY idx_rate_item_lookup (tenant_id, card_id, region_code, weight_from, weight_to),
  CONSTRAINT ck_rate_item_weight CHECK (weight_to IS NULL OR weight_to > weight_from),
  CONSTRAINT ck_rate_item_positive CHECK (first_price >= 0 AND add_price >= 0 AND min_charge >= 0
                                          AND first_weight > 0 AND add_unit > 0 AND volume_divisor > 0)
) ENGINE=InnoDB COMMENT='运费价目表明细（区域 + 重量区间 → 首重/续重价）';
