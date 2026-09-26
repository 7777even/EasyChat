-- ============================================================
-- migration-008：语音/视频通话 数据库变更
-- ============================================================
-- 说明：为「语音/视频通话（音视频 + 群呼 + TURN + call_log）」新增通话记录表。
--   开发与生产环境请按需手动执行（本仓库不自动跑迁移）。
--   执行前建议先备份：mysqldump -uroot -p easychat > backup.sql
--
-- 执行方式（本机）：
--   "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -proot -h127.0.0.1 easychat ^
--     -e "source D:/qd/EasyChat/easychat-migration-008-voice-call.sql"
--
-- 语义（已通过 L4 人工确认）：
--   call_log：通话结束后落库一条记录，供后续回溯（管理端列表为独立变更）。
--   status：1已接 2未接 3拒接 4取消 5忙线。
-- ============================================================

CREATE TABLE `call_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `caller_id` varchar(20) NOT NULL COMMENT '发起方 userId',
  `call_type` tinyint(4) NOT NULL COMMENT '1=单聊 2=群呼',
  `peer_id` varchar(20) DEFAULT NULL COMMENT '单聊对方 userId',
  `group_id` varchar(20) DEFAULT NULL COMMENT '群呼群组 ID',
  `media_type` tinyint(4) NOT NULL COMMENT '1=音频 2=音视频',
  `start_time` bigint(20) DEFAULT NULL COMMENT '通话开始时间(ms)',
  `end_time` bigint(20) DEFAULT NULL COMMENT '通话结束时间(ms)',
  `status` tinyint(4) NOT NULL COMMENT '1已接 2未接 3拒接 4取消 5忙线',
  `participant_count` int(11) DEFAULT NULL COMMENT '参与人数',
  `create_time` bigint(20) DEFAULT NULL COMMENT '记录创建时间(ms)',
  PRIMARY KEY (`id`),
  KEY `idx_caller` (`caller_id`),
  KEY `idx_group` (`group_id`),
  KEY `idx_peer` (`peer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话记录';
