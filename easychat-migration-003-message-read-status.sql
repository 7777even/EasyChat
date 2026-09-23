-- =====================================================================
-- 消息已读/送达状态：新增 message_read_record 表
--   记录每个用户对单条消息的 ack 类型（2=已送达 3=已读）
--   单聊：用于"已送达 / 已读"双勾状态
--   群聊：每个成员一条记录，用于"已读成员列表"与"全部已读"判断
-- =====================================================================
CREATE TABLE IF NOT EXISTS message_read_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  message_id BIGINT NOT NULL COMMENT '关联 chat_message.message_id',
  user_id VARCHAR(12) NOT NULL COMMENT '确认已读/已送达的用户 ID',
  contact_id VARCHAR(12) NOT NULL COMMENT '所属会话联系人 ID（群 ID 或对方用户 ID）',
  contact_type TINYINT NOT NULL DEFAULT 0 COMMENT '0:单聊 1:群聊',
  ack_type TINYINT NOT NULL DEFAULT 2 COMMENT '2:已送达 3:已读',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '确认时间',
  PRIMARY KEY (id) USING BTREE,
  UNIQUE KEY uk_message_user (message_id, user_id) USING BTREE,
  INDEX idx_user_contact (user_id, contact_id) USING BTREE
) ENGINE = InnoDB COMMENT = '消息已读/送达确认记录';
