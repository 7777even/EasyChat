-- ============================================================
-- migration-012：语音消息「未播放红点」旁挂表
-- ============================================================
-- 关联规格：openspec/changes/2026-10-03-location-and-voice-message
--
-- 背景：
--   语音消息（MessageTypeEnum.VOICE=24）此前端到端未接通（断链五层），
--   本表用于「未播放红点」功能（对标微信：播放前气泡带红点）。
--
--   为什么用旁挂表而不是给 chat_message 加列：
--     「谁播了」是**每接收方独立**的状态。若加列到 chat_message，
--     A 播放自己收到的语音会污染 B 看到的同一行。
--     且一个会话里常有多条语音，加列无法表达「哪几条没播」。
--     （见 design.md ADR-001）
--
-- 语义：
--   · 主键 (message_id, user_id)：一条语音 × 一个接收方的播放状态
--   · is_read  0 未播放 / 1 已播放
--   · read_time  播放时间（毫秒），未播放为 NULL
--   · create_time 行创建时间（§6.4-5 新建表必须含 create_time）
--     注意：create_time 与 read_time 语义不同 —— 前者是「状态行何时建立」，
--     后者是「何时播放」。播放后 create_time 不变、read_time 更新。
--
--   行生命周期：
--     · 接收方首次进入会话（渲染到该语音）时 upsert 一行 is_read=0
--     · 播放时 update 为 is_read=1 + read_time=当前时间
--     · 消息被删（chat_message.delete_flag 非0）时不做级联清理：
--       行数据量极小（= 未播放语音数），且保留可支撑「语音未读总数」聚合。
--
-- 执行方式（本机）：
--   cmd /c ""C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -proot
--     -h127.0.0.1 --default-character-set=utf8mb4 easychat
--     < D:\qd\EasyChat\easychat-migration-012-voice-read.sql"
--
-- 幂等性：
--   建表用 CREATE TABLE IF NOT EXISTS，**重复执行安全**：已存在则跳过、不报错。
--   后续 ALTER 类语句用 information_schema + PREPARE 判存在。
--
-- 向后兼容：
--   旧版本代码不读本表 → 本表可独立保留/删除，不影响既有功能。
--
-- 已核对无需迁移：
--   chat_message 已有 duration / file_name / file_size / file_type / extra_data(2000)
--   → **位置与语音消息本体零 DDL**，无需为它们加列。
-- ============================================================

CREATE TABLE IF NOT EXISTS `chat_message_voice_read` (
  `message_id`  BIGINT(20)                    NOT NULL COMMENT '语音消息 id（chat_message.message_id）',
  `user_id`     VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '接收方 user_id（播放者）',
  `is_read`     TINYINT(1)                    NOT NULL DEFAULT 0 COMMENT '播放状态 0未播放 1已播放',
  `read_time`   BIGINT(20)                    NULL COMMENT '播放时间（毫秒），未播放为 NULL',
  `create_time` BIGINT(20)                    NULL COMMENT '状态行创建时间（毫秒）',
  PRIMARY KEY (`message_id`, `user_id`) USING BTREE,
  KEY `idx_user_read` (`user_id`, `is_read`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '语音消息未播放状态（每接收方独立）';

-- ----------------------------
-- 核对：表存在 + 结构符合预期
-- ----------------------------
SELECT TABLE_NAME, TABLE_COMMENT
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'chat_message_voice_read';

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'chat_message_voice_read'
 ORDER BY ORDINAL_POSITION;

SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'chat_message_voice_read'
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;