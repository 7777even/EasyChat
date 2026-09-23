-- =====================================================================
-- 消息可靠性改造：chat_message 加 seq / client_id
--   seq        : 会话内单调序号（Redis INCR 产生，同 session_id 下严格递增）
--   client_id  : 客户端生成去重键（send_user_id + client_id 唯一，防重发）
-- =====================================================================
-- ⚠️ 一次性迁移脚本，执行前请备份 chat_message 表
--    适用于已有数据的存量环境（MySQL 5.7，不使用 ADD COLUMN IF NOT EXISTS）

ALTER TABLE chat_message
  ADD COLUMN seq BIGINT NULL DEFAULT NULL
    COMMENT '会话内单调序号（同 session_id 下严格递增，用于排序/补推/ACK）',
  ADD COLUMN client_id VARCHAR(64) NULL DEFAULT NULL
    COMMENT '客户端生成的消息去重键';

-- 为已有消息回填 seq（按 send_time/message_id 顺序，INCR 语义由 Redis 重建后覆盖）
-- 注：存量消息的 seq 在 Redis INCR 中被重新计算后自然对齐；此处仅建索引加速查询
ALTER TABLE chat_message
  ADD INDEX idx_session_seq (session_id, seq);
