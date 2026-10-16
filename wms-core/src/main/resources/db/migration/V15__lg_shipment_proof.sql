-- V15（版本号唯一递增：V9~V14 已占用）
-- ==========================================================================================
-- 回单/异常影像登记（P5 对象存储）
--
-- 设计要点：
--   1. **数据库只存"影像在哪 + 元数据"**，影像本身放对象存储（MinIO/本地盘）。
--      把图片存进 MySQL 会同时拖垮备份体积、主从复制与查询缓存。
--   2. **sha256 是自证凭据**：回单影像常被用于结算举证，一旦被质疑"是否被改过"，
--      重新计算哈希与登记值比对即可自证（不依赖文件系统时间戳）。
--   3. **object_key 唯一**：同一张影像不允许登记两次（避免重复上传把存储费用翻倍、
--      也让"这张回单有没有传过"成为一次唯一键查询）。
--   4. biz_type 区分影像用途（回单 POD / 异常 EXCEPTION / 面单 LABEL / 其他 OTHER），
--      便于按用途设置不同的保留期与访问策略。
-- ==========================================================================================
CREATE TABLE lg_shipment_proof (
  id            BIGINT UNSIGNED NOT NULL,
  tenant_id     BIGINT UNSIGNED NOT NULL DEFAULT 1,
  shipment_no   VARCHAR(64) NOT NULL COMMENT '发货单号',
  biz_type      VARCHAR(16) NOT NULL DEFAULT 'POD' COMMENT 'POD回单/EXCEPTION异常/LABEL面单/OTHER其他',
  object_key    VARCHAR(255) NOT NULL COMMENT '对象存储键（含租户与业务路径，便于按业务查找）',
  original_name VARCHAR(255) NULL COMMENT '上传时的原始文件名（仅作展示，不参与寻址）',
  content_type  VARCHAR(128) NULL,
  size_bytes    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  sha256        CHAR(64) NOT NULL COMMENT '内容 SHA-256（被质疑"是否被改过"时用来自证）',
  storage       VARCHAR(64) NULL COMMENT '存储介质描述（LOCAL:/path 或 MINIO:endpoint/bucket）',
  remark        VARCHAR(255) NULL,
  trace_id      VARCHAR(64) NULL COMMENT '上传请求的 traceId（P4 全链路，排障用）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_proof_object (tenant_id, object_key),
  KEY idx_proof_shipment (tenant_id, shipment_no, biz_type, created_at),
  CONSTRAINT ck_proof_size CHECK (size_bytes >= 0)
) ENGINE=InnoDB COMMENT='发货单影像登记（回单/异常/面单；文件本体在对象存储）';
