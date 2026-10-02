-- ============================================================
-- migration-011：隐私设置 —— 朋友圈可见范围 + 在线状态可见性（user_info 加 4 列）
-- ============================================================
-- 关联规格：openspec/specs/privacy-settings
--           openspec/changes/2026-10-02-privacy-moment-and-status
--
-- 背景：
--   1) 朋友圈「谁能看到」此前只有**单条动态级**判定
--      （MomentServiceImpl#canView 覆盖 0–4，逻辑本身完整），
--      但没有任何地方能配置**用户级默认**，且发布页只提供 3 个选项，
--      白名单(3)/黑名单(4) 选不了人也选不了项。
--   2) 在线状态此前**无条件**向所有好友广播
--      （ChannelContextUtils#broadcastOnlineStatus 无任何开关），
--      用户没有任何手段隐藏自己的在线状态。
--
-- 语义（已通过 L4 人工确认）：
--   · moment_visibility 语义与既有 moment.visibility **完全对齐**：
--       0 公开 / 1 仅好友 / 2 仅自己 / 3 自定义白名单 / 4 黑名单
--   · moment_visible_list / moment_invisible_list 为 JSON 数组字符串，
--     格式与既有 moment.visible_list / moment.invisible_list 一致
--     （例：["U01234567890","U09876543210"]）
--   · online_status_visible：1 展示（默认）/ 0 隐藏
--
-- ★ 默认值刻意选「与现状一致」，存量用户升级后**行为完全不变**：
--     moment_visibility=0  ← 与 PublishMoment.vue 既有 visibility: 0 一致
--     online_status_visible=1 ← 与既有「无条件广播」一致
--   故本次迁移**无需通知任何用户重新设置**。
--
-- 执行方式（本机）：
--   cmd /c ""C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -proot
--     -h127.0.0.1 easychat < D:\qd\EasyChat\easychat-migration-011-privacy-settings.sql"
--
-- 幂等性：
--   MySQL 5.7 **不支持** ADD COLUMN IF NOT EXISTS，
--   故用 information_schema.COLUMNS 判存在 + PREPARE 动态执行。
--   **重复执行安全**：已存在的列会被跳过，不报错、不重复添加。
--   列可独立存在：旧版本代码不读这 4 列，回退代码后列保留不影响运行。
--
-- 已核对无需迁移：
--   chat_message.delete_flag、chat_session_user.top_type/no_disturb/draft、
--   call_log、group_file、report_audit_log、sensitive_word.delete_flag、
--   user_info.password 列宽（migration-010 已放宽至 60）在存量库均已就位。
-- ============================================================

-- ----------------------------
-- 1) moment_visibility：朋友圈默认可见范围
-- ----------------------------
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'user_info'
       AND COLUMN_NAME  = 'moment_visibility') > 0,
  'DO 0',
  'ALTER TABLE `user_info`
     ADD COLUMN `moment_visibility` tinyint(1) NOT NULL DEFAULT 0
       COMMENT ''朋友圈默认可见范围 0公开 1仅好友 2仅自己 3白名单 4黑名单''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------
-- 2) moment_visible_list：自定义白名单（JSON 数组，visibility=3 生效）
-- ----------------------------
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'user_info'
       AND COLUMN_NAME  = 'moment_visible_list') > 0,
  'DO 0',
  'ALTER TABLE `user_info`
     ADD COLUMN `moment_visible_list` text NULL
       COMMENT ''朋友圈自定义白名单 JSON 数组（visibility=3 生效）''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------
-- 3) moment_invisible_list：自定义黑名单（JSON 数组，visibility=4 生效）
-- ----------------------------
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'user_info'
       AND COLUMN_NAME  = 'moment_invisible_list') > 0,
  'DO 0',
  'ALTER TABLE `user_info`
     ADD COLUMN `moment_invisible_list` text NULL
       COMMENT ''朋友圈自定义黑名单 JSON 数组（visibility=4 生效）''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------
-- 4) online_status_visible：是否对好友展示在线状态
-- ----------------------------
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'user_info'
       AND COLUMN_NAME  = 'online_status_visible') > 0,
  'DO 0',
  'ALTER TABLE `user_info`
     ADD COLUMN `online_status_visible` tinyint(1) NOT NULL DEFAULT 1
       COMMENT ''是否对好友展示在线状态 1展示 0隐藏''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------
-- 5) 核对：4 列就位 + 存量行默认值正确（行为不变）
-- ----------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'user_info'
   AND COLUMN_NAME IN ('moment_visibility', 'moment_visible_list',
                       'moment_invisible_list', 'online_status_visible')
 ORDER BY ORDINAL_POSITION;

SELECT user_id, moment_visibility, online_status_visible
  FROM user_info
 ORDER BY user_id;
