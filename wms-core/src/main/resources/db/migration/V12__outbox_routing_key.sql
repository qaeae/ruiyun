-- V12（注意：V9/V10/V11 已被占用，版本号必须唯一且递增——重复只在 Flyway 启动时才报错）
-- ==========================================================================================
-- 发件箱增加 routing_key（P3.1：每域独立队列与路由键）
--
-- 为什么必须落库而不是发送时临时推导：
--   路由决策（这条事件该进哪个域的队列）属于**事件产生时的业务语义**，
--   应当与事件内容一起被持久化。若投递时才从 payload JSON 里反推，
--   一旦事件载荷结构演进或解析失败，就会出现"消息投到了错误的队列/没人消费"，
--   且这类错误发生在重投阶段（往往是在故障恢复期间），排查成本极高。
--
-- 同时把"事件类型"与"路由键"分开：event_type 是业务语义（APPROVAL_APPROVED），
-- routing_key 是投递语义（approval.approved.iv），二者粒度不同、演进节奏也不同。
-- ==========================================================================================
ALTER TABLE pf_outbox
    ADD COLUMN routing_key VARCHAR(64) NULL COMMENT '投递路由键（如 approval.approved.iv）；老数据为 NULL 时按默认交换机投递' AFTER event_type,
    ADD KEY idx_routing_key (tenant_id, routing_key);

-- 已存在的历史记录（本地/测试库）补一个兜底路由键，避免它们永远无法投递
UPDATE pf_outbox SET routing_key = 'approval.approved' WHERE routing_key IS NULL AND status = '待投递';
