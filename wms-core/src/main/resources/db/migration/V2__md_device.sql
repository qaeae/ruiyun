-- ===================================================================
-- V2：补建设备档案 md_device（01 §3.9 原先只有摘要行，03 §1.1 却要求设备绑定校验）
-- 由《01-数据模型DDL与状态机.md》§3.10 抽取生成，请勿手工编辑本文件
-- 变更流程：改设计文档 → 重新抽取 → 新增 V3__xxx.sql（V1/V2 一经执行不再修改）
-- ===================================================================
SET NAMES utf8mb4;

-- 设备档案（PDA / 扫码枪 / 打印机 / 电子秤 / 闸机）
CREATE TABLE md_device (
  id             BIGINT UNSIGNED NOT NULL,
  tenant_id      BIGINT UNSIGNED NOT NULL DEFAULT 1,
  device_code    VARCHAR(64) NOT NULL COMMENT '设备编号（现场唯一；PDA 请求头 X-Device-Code 取值）',
  device_type    VARCHAR(24) NOT NULL COMMENT '设备类型：PDA/SCANNER/PRINTER/SCALE/GATE',
  name           VARCHAR(64) NULL COMMENT '设备名称（如"复核台1-电子秤"）',
  warehouse_id   BIGINT UNSIGNED NULL COMMENT '归属仓库；NULL 表示平台或跨仓共用',
  location_desc  VARCHAR(128) NULL COMMENT '摆放位置描述（如"B 区复核台"）',
  status         VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用/停用；停用后该设备的 PDA 请求被拒',
  last_online_at DATETIME(3) NULL COMMENT '最近一次在线时间（由设备请求刷新；不做主动心跳）',
  remark         VARCHAR(255) NULL COMMENT '备注',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_md_device (tenant_id, device_code),
  KEY idx_type_status (tenant_id, device_type, status),
  KEY idx_wh (tenant_id, warehouse_id, device_type)
) ENGINE=InnoDB COMMENT='设备档案（PDA/扫码枪/打印机/电子秤/闸机）';
