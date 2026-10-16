-- 盘点单（2026-10-14 随 W3 补建，与 01 §3.15 同源，请勿手工编辑本文件）
--
-- 为什么补建：03 定义了 API-IV-03（盘点单创建 / 录入 / 审批），
-- 且 01 §4 的不变量校验与 02 M6 都把盘点列为库存差异的唯一合法来源，
-- 但基线 DDL 里**没有任何盘点表**——属跨文档缺口（第 4 个：md_device、md_* 主数据、au_*、iv_stocktake）。
--
-- 设计要点（评审请重点看这两条）：
--   ① **账面快照 book_qty 在录入前固定**：差异 = 实盘 − 快照。
--      若拿"审批那一刻的账面"做基准，录入期间发生的出库会被算成盘盈（错得毫无痕迹）。
--   ② **location_id 非空**：盘点按库位点数（现场逐个库位盘），
--      同时也避免了 MySQL 唯一键里 NULL 不参与去重的坑（同一 SKU 可重复建行）。
CREATE TABLE iv_stocktake (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  stocktake_no  VARCHAR(40) NOT NULL COMMENT '盘点单号 PT+yyyyMMdd+序号',
  warehouse_id  BIGINT UNSIGNED NOT NULL,
  zone_id       BIGINT UNSIGNED NULL COMMENT '库区（按库区盘点时填）',
  scope_type    VARCHAR(24) NOT NULL DEFAULT '库区' COMMENT '全仓/库区/库位',
  status        VARCHAR(16) NOT NULL DEFAULT '待录入' COMMENT '待录入/待审批/已审批/已驳回',
  item_count    INT NOT NULL DEFAULT 0 COMMENT '明细条数',
  diff_count    INT NOT NULL DEFAULT 0 COMMENT '有差异条数（申请审批时统计）',
  created_by    BIGINT UNSIGNED NULL,
  submitted_by  BIGINT UNSIGNED NULL COMMENT '提交人',
  submitted_at  DATETIME(3) NULL,
  approved_by   BIGINT UNSIGNED NULL COMMENT '审批人（必须 ≠ 创建人，80002）',
  approved_at   DATETIME(3) NULL,
  reason        VARCHAR(255) NULL COMMENT '驳回原因',
  remark        VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iv_stocktake_no (tenant_id, stocktake_no),
  KEY idx_status (tenant_id, warehouse_id, status)
) ENGINE=InnoDB COMMENT='盘点单';

CREATE TABLE iv_stocktake_item (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  stocktake_id  BIGINT UNSIGNED NOT NULL,
  merchant_id   BIGINT UNSIGNED NOT NULL COMMENT '商家（审批时定位库存账必需：三态账的键是 租户+仓库+商家+SKU）',
  sku_id        BIGINT UNSIGNED NOT NULL,
  location_id   BIGINT UNSIGNED NOT NULL COMMENT '库位（非空：盘点按库位点数，且规避唯一键 NULL 不去重）',
  book_qty      DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '**建单时的账面快照**（差异基准，录入前固定）',
  actual_qty    DECIMAL(18,4) NULL COMMENT '实盘数（未录入为 NULL，不能默认 0——0 与"没盘"必须可区分）',
  diff_qty      DECIMAL(18,4) NULL COMMENT '差异 = 实盘 − 账面（盘盈正、盘亏负）',
  item_status   VARCHAR(16) NOT NULL DEFAULT '待录入' COMMENT '待录入/已录入/已调整',
  remark        VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iv_stocktake_item (tenant_id, stocktake_id, sku_id, location_id),
  KEY idx_stocktake (tenant_id, stocktake_id, item_status)
) ENGINE=InnoDB COMMENT='盘点明细（账面快照 + 实盘 + 差异）';
