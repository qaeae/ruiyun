-- 资金通道指令表（2026-10-14 随 W4 补建，DDL 取自 04 §4.6，请勿手工编辑本文件）
--
-- 为什么补建：`04` §4.6 明确写了「通道指令表 ac_channel_order（**对 01 的增补，需 DDL 评审**）」，
-- 并给出完整 DDL；§5.3 的「每 10 分钟扫描未回调并主动查询补正」也依赖它。
-- 但该增补**从未落进迁移脚本**——属第 7 个跨文档缺口（前六个：md_device、md_*、au_*、
-- iv_stocktake、au_approval、pf_scheduled_job）。
--
-- 是定时任务把它挖出来的：写完 CHANNEL_QUERY_RETRY 任务后，集成测试在
-- `DELETE FROM ac_channel_order` 上直接报 "bad SQL grammar"——**表不存在**。
-- 这类"文档里写了、迁移脚本里没有"的表，只有真去跑那条 SQL 才会暴露。
--
-- 用途（04 §4.6）：所有通道调用必须先落本表再发送，解决"指令已发出但结果未知"的经典问题，
-- 支撑重试、主动查询与对账。K2 接入后由 PaymentInstructionPort 的真实适配器写入。
CREATE TABLE ac_channel_order (
  id               BIGINT UNSIGNED NOT NULL,
  tenant_id        BIGINT UNSIGNED NOT NULL DEFAULT 1,
  channel_order_no VARCHAR(40) NOT NULL COMMENT '平台侧指令号 CH+yyyyMMdd+序列',
  channel_code     VARCHAR(32) NOT NULL COMMENT '机构代码',
  biz_type         VARCHAR(24) NOT NULL COMMENT 'FREEZE/UNFREEZE/DEDUCT/SPLIT/REFUND/TRANSFER/RECHARGE/DEPOSIT_REFUND',
  biz_no           VARCHAR(64) NOT NULL COMMENT '业务单号（冻结单号/批次号/充值单号）',
  merchant_id      BIGINT UNSIGNED NULL,
  amount           DECIMAL(18,4) NOT NULL,
  request_json     JSON NOT NULL COMMENT '通道请求报文（脱敏后）',
  response_json    JSON NULL,
  status           VARCHAR(16) NOT NULL COMMENT '待发送/处理中/成功/失败/已补偿',
  channel_txn_no   VARCHAR(64) NULL COMMENT '通道流水号（回调或查询回填）',
  retry_count      INT NOT NULL DEFAULT 0,
  next_retry_at    DATETIME(3) NULL,
  last_error       VARCHAR(512) NULL,
  sent_at          DATETIME(3) NULL,
  finished_at      DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_order_no (tenant_id, channel_order_no),
  UNIQUE KEY uk_channel_txn (tenant_id, channel_txn_no) COMMENT '通道流水幂等（NULL 不冲突）',
  KEY idx_retry (tenant_id, status, next_retry_at),
  KEY idx_biz (tenant_id, biz_type, biz_no)
) ENGINE=InnoDB COMMENT='资金通道指令（重试与对账载体）';
