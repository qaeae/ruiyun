-- 敏感操作双人复核（2026-10-14 随 W4 补建，与 01 §3.16 同源，请勿手工编辑本文件）
--
-- 为什么补建：02 M2-05「敏感操作双人复核与审批流（退款/调价/库存调整/押金退还/账单冲正/差异放行）」
-- 与 03 API-AUTH-04/05 都要求这条能力，04 §六也写了「财务发起 + 财务负责人复核（双人不可同一人）」，
-- 但基线 DDL 里**没有 au_approval**（V6 建 RBAC 时已注明"待后续切片补"）。
-- 这是第 5 个跨文档缺口（前四个：md_device、md_* 主数据、au_*、iv_stocktake）。
--
-- 设计要点（评审请重点看这三条）：
--   ① **一个业务对象只允许一张审批单**（uk_biz）：否则同一个人重复提交会造出多条待办，
--      审批人点错其中一条就容易放行"另一份内容"。
--   ② **payload_hash 记录提交时的内容指纹**：审批通过时校验内容未被改动。
--      没有它，"先提交一个 100 元的调整单，等审批人点头后再改成 10000 元"是可行的——
--      审批人以为自己在批准 100 元。
--   ③ **status 走条件更新**（仅「待复核」可被复核）：并发双人对同一单点头，只能有一次生效，
--      避免"通过 + 驳回"两条结论同时落库。
CREATE TABLE au_approval (
  id             BIGINT UNSIGNED NOT NULL,
  tenant_id      BIGINT UNSIGNED NOT NULL DEFAULT 1,
  approval_no    VARCHAR(40) NOT NULL COMMENT '审批单号 AP+yyyyMMdd+序号',
  biz_type       VARCHAR(32) NOT NULL COMMENT 'STOCK_ADJUST/BILL_REVERSE/REFUND/DEPOSIT_REFUND/PRICE_CHANGE/DIFF_RELEASE/SETTLE_DISPATCH/ADMIN_GRANT',
  biz_id         BIGINT UNSIGNED NULL COMMENT '业务对象 ID（可为空：如"授予超管"这类无业务对象的操作）',
  biz_no         VARCHAR(64) NULL COMMENT '业务单号',
  warehouse_id   BIGINT UNSIGNED NULL,
  merchant_id    BIGINT UNSIGNED NULL,
  amount         DECIMAL(18,4) NULL COMMENT '涉及金额（无金额的操作留空）',
  payload_json   JSON NOT NULL COMMENT '**审批内容快照**（执行时以它为准，不接受执行时的实时参数）',
  payload_hash   CHAR(64) NOT NULL COMMENT '内容指纹（SHA-256）：防"审批后改内容"',
  reason         VARCHAR(255) NOT NULL COMMENT '申请事由（复核人据此判断，不能空）',
  status         VARCHAR(16) NOT NULL DEFAULT '待复核' COMMENT '待复核/已通过/已驳回/已撤回',
  apply_by       BIGINT UNSIGNED NOT NULL COMMENT '发起人',
  apply_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  approve_by     BIGINT UNSIGNED NULL COMMENT '复核人（**必须 ≠ 发起人**，80002）',
  approve_at     DATETIME(3) NULL,
  reject_reason  VARCHAR(255) NULL COMMENT '驳回原因（驳回必填）',
  executed       TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已执行副作用（通过后由处理器置 1）',
  executed_at    DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_approval_no (tenant_id, approval_no),
  UNIQUE KEY uk_approval_biz (tenant_id, biz_type, biz_id) COMMENT '同一业务对象只允许一张审批单',
  KEY idx_pending (tenant_id, status, apply_at),
  KEY idx_approver (tenant_id, approve_by, approve_at)
) ENGINE=InnoDB COMMENT='敏感操作双人复核单';
