-- 定时任务（2026-10-14 随 W4 补建，与 01 §3.17 同源，请勿手工编辑本文件）
--
-- 为什么补建：设计文档多处依赖定时任务（01 §1.2 第 8 行「揽收后 7 天无回传 → 定时任务兜底扣减」、
-- 01 §1.2 第 148 行「按承运商+月份生成对账单」、04 §5.3「每 10 分钟扫描通道未回调并主动查询补正」），
-- 但**没有任何任务表**。这是第 6 个跨文档缺口（前五个：md_device、md_*、au_*、iv_stocktake、au_approval）。
--
-- 关键工程问题（为什么必须有表，而不是只写 @Scheduled）：
--   ① **多实例部署时 cron 会被每个实例各跑一遍**。日终勾稽虽然靠唯一键幂等，
--      但"两实例同时算同一份账"会互相覆盖结论，兜底扣减/告警也会重复触发；
--   ② 运维需要回答"这个任务上次成功是什么时候、失败原因是什么、能不能手工补跑"——
--      这些信息只能落库，靠日志检索在出事故时太慢。
--
-- 租约（lease）设计：
--   job_name 唯一一行；执行前用 CAS 抢租约（`lease_until IS NULL OR lease_until < NOW()`），
--   抢到的实例才执行，执行完/失败都释放（置空 lease_owner/lease_until）。
--   租约带**过期时间**（不是"执行期间一直持有"），所以实例崩溃后不会永久卡死——
--   超过租约时长即可被其他实例接管（代价是极端情况下可能重入，因此任务本身仍需幂等）。
CREATE TABLE pf_scheduled_job (
  id             BIGINT UNSIGNED NOT NULL,
  tenant_id      BIGINT UNSIGNED NOT NULL DEFAULT 1,
  job_name       VARCHAR(64) NOT NULL COMMENT '任务名（枚举常量，如 FALLBACK_SWEEP）',
  display_name   VARCHAR(64) NOT NULL COMMENT '中文名（运维看）',
  cron_desc      VARCHAR(64) NULL COMMENT '调度表达式（描述用，真正生效的是配置）',
  enabled        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  lease_owner    VARCHAR(64) NULL COMMENT '持锁实例标识（instanceId:threadId）',
  lease_until    DATETIME(3) NULL COMMENT '租约到期时间（过期可被其他实例接管，防实例崩溃后永久卡死）',
  last_status    VARCHAR(16) NULL COMMENT '成功/失败/跳过',
  last_start_at  DATETIME(3) NULL,
  last_end_at    DATETIME(3) NULL,
  last_message   VARCHAR(500) NULL COMMENT '上次结果摘要（差异数/候选数/异常信息）',
  success_count  INT NOT NULL DEFAULT 0,
  fail_count     INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_name (tenant_id, job_name)
) ENGINE=InnoDB COMMENT='定时任务（含租约与最近一次结果）';

CREATE TABLE pf_job_run (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  job_name      VARCHAR(64) NOT NULL,
  trigger_type  VARCHAR(16) NOT NULL COMMENT 'SCHEDULE定时/MANUAL手工补跑（手工补跑必须与定时走同一段代码）',
  status        VARCHAR(16) NOT NULL DEFAULT '执行中' COMMENT '执行中/成功/失败/跳过（停留在「执行中」= 任务卡死或被杀的痕迹）',
  started_at    DATETIME(3) NOT NULL,
  ended_at      DATETIME(3) NULL,
  cost_ms       BIGINT NULL,
  message       VARCHAR(500) NULL COMMENT '结果摘要或异常信息',
  operator_id   BIGINT UNSIGNED NULL COMMENT '手工补跑的操作人（定时为空）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_job_time (tenant_id, job_name, started_at),
  KEY idx_status_time (tenant_id, status, started_at)
) ENGINE=InnoDB COMMENT='定时任务执行历史（每次一行）';
