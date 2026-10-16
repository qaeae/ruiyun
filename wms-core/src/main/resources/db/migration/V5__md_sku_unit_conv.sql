-- 箱规单位换算（2026-10-14 随 W3 主数据切片补，与 01 §3.13 同源，请勿手工编辑本文件）
--
-- 为什么必须有这张表：**计费与库存都要用**。
--   ① 计费：按托/按箱报价时，收货按"件"扫，没有换算就只能估；
--   ② 库存：上架 2 托 = 多少件？没有换算会出现"操作工按托上架、系统按件记账"的量纲混乱，
--      而量纲混乱的直接后果是账面库存与实际差几十倍。
CREATE TABLE md_sku_unit_conv (
  id         BIGINT UNSIGNED NOT NULL,
  tenant_id  BIGINT UNSIGNED NOT NULL DEFAULT 1,
  sku_id     BIGINT UNSIGNED NOT NULL,
  unit_from  VARCHAR(16) NOT NULL COMMENT '源单位：件/箱/托',
  unit_to    VARCHAR(16) NOT NULL COMMENT '目标单位：件/箱/托',
  factor     DECIMAL(18,6) NOT NULL COMMENT '1 unit_from = factor unit_to（必须 > 0）',
  remark     VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_unit_conv (tenant_id, sku_id, unit_from, unit_to),
  CONSTRAINT ck_unit_conv_factor_positive CHECK (factor > 0),
  CONSTRAINT ck_unit_conv_diff_unit CHECK (unit_from <> unit_to)
) ENGINE=InnoDB COMMENT='SKU 箱规单位换算（计费与库存量纲的统一依据）';
