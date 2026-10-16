-- PDA 补传冲突任务（2026-10-14 补建，与 01 §3.11 同源，请勿手工编辑本文件）
--
-- 为什么需要本表：01 §6 约定"单据状态已流转/库存被占用 → 任务置『待人工处理』，回推 PDA"，
-- 03 API-PDA-03 要求 GET /pda/conflicts 能列出待处理任务，但 V1 基线的 pf_ 段没有任何任务表。
-- 审计日志（pf_audit_log）是追加型留痕，无法表达"待处理 → 已处理"的工作流状态，故单独建表。

CREATE TABLE pf_pda_conflict (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  idem_key      VARCHAR(96) NOT NULL COMMENT 'device_code+task_id+action+seq，与 iv_movement.idem_key 同源',
  device_code   VARCHAR(64) NOT NULL,
  task_id       VARCHAR(64) NOT NULL,
  action        VARCHAR(16) NOT NULL COMMENT 'PICK/LOAD/PUTAWAY',
  seq           INT NOT NULL,
  biz_no        VARCHAR(64) NULL COMMENT '业务单号（DO 号/ASN 号）',
  merchant_id   BIGINT UNSIGNED NULL,
  conflict_type VARCHAR(24) NOT NULL COMMENT '状态已流转/库存被占用/号码不可识别',
  server_status VARCHAR(24) NULL COMMENT '服务端当前状态（用于让人工判断该怎么处理）',
  target_status VARCHAR(24) NULL COMMENT '补传想推进到的状态',
  payload_json  JSON NULL COMMENT '原始补传内容（人工核对依据）',
  status        VARCHAR(16) NOT NULL DEFAULT '待人工处理' COMMENT '待人工处理/已处理/已忽略',
  message       VARCHAR(255) NULL,
  scanned_at    DATETIME(3) NULL COMMENT 'PDA 本地扫码时间（可能有客户端时钟偏差）',
  occurred_at   DATETIME(3) NOT NULL COMMENT '服务端受理时间',
  handled_by    BIGINT UNSIGNED NULL,
  handled_at    DATETIME(3) NULL,
  handle_result VARCHAR(32) NULL COMMENT '人工处理结论（采纳PDA/维持服务端/作废）',
  remark        VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pda_idem (tenant_id, idem_key) COMMENT '同一补传只生成一条冲突任务（重复补传不再刷屏）',
  KEY idx_status_time (tenant_id, status, occurred_at) COMMENT 'GET /pda/conflicts 列表',
  KEY idx_device (tenant_id, device_code, status)
) ENGINE=InnoDB COMMENT='PDA 补传冲突任务';
