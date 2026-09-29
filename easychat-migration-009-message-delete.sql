-- ============================================================
-- migration-009：管理端消息删除位 数据库变更
-- ============================================================
-- 说明：为「管理端消息删除位（举报处置 DELETE_CONTENT 真实逻辑删除）」
--   在 chat_message 上新增删除位列。
--   开发与生产环境请按需手动执行（本仓库不自动跑迁移）。
--   执行前建议先备份：mysqldump -uroot -p easychat > backup.sql
--
-- 执行方式（本机）：
--   cmd /c ""C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -proot
--     -h127.0.0.1 easychat < D:\qd\EasyChat\easychat-migration-009-message-delete.sql"
--
-- 语义（已通过 L4 人工确认，ADR-001）：
--   delete_flag：0=存活，非0=删除时间戳ms（对齐 sensitive_word.delete_flag 先例）。
--   chat_message.status 已被发送态占用，故必须新列。
--
-- 执行注意：
--   MySQL 5.7 加列 = INPLACE 列式重建；chat_message 为最大表，
--   ADD COLUMN DEFAULT 0 为即时元数据变更 + 后台重建，建议低峰执行。
--   重复执行会报 Duplicate column（1060），属预期，可忽略。
-- ============================================================

ALTER TABLE `chat_message`
  ADD COLUMN `delete_flag` BIGINT NOT NULL DEFAULT 0 COMMENT '0=存活，非0=删除时间戳ms';
