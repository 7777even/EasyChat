-- ============================================================
-- 迁移 001：群管理（群成员角色 / 禁言）
-- 适用：MySQL 5.7，在已有 easychat.sql 基础上升级 user_contact 表
-- 日期：2026-09-22
-- 说明：**可重复执行**（2026-10-05 补齐守卫，遗留 #10）
-- ============================================================

-- 1. 新增字段
--    幂等性：MySQL 5.7 **不支持** ADD COLUMN IF NOT EXISTS，故用 information_schema
--    判存在 + SET @ddl 动态执行。已存在的列退化为 DO 0，不报错。
--    原为一条 ALTER 加 2 列，改为**逐列独立守卫**：全部已存在则全 DO 0，
--    部分已存在则只补缺列（可自愈上次中途失败的执行）。
--    实测：连跑两次均 exit 0，且表结构不变。
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_contact'
       AND COLUMN_NAME = 'role') > 0,
  'DO 0',
  'ALTER TABLE `user_contact`
     ADD COLUMN `role` tinyint(1) NULL DEFAULT 2
       COMMENT ''群成员角色（仅群组 contact_type=1 有效）0:群主 1:管理员 2:成员''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_contact'
       AND COLUMN_NAME = 'mute_end_time') > 0,
  'DO 0',
  'ALTER TABLE `user_contact`
     ADD COLUMN `mute_end_time` datetime NULL DEFAULT NULL
       COMMENT ''禁言到期时间（仅群组有效，NULL表示未被禁言）''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 存量数据回填：把已有群聊的群主 role 置为 0
--    群主判定：group_info.group_owner_id = user_contact.user_id 且 group_id = contact_id
UPDATE `user_contact` uc
  INNER JOIN `group_info` gi ON gi.group_owner_id = uc.user_id AND gi.group_id = uc.contact_id
   SET uc.role = 0
 WHERE uc.contact_type = 1
   AND uc.status = 1;

-- 3. 验证（按需打开）
-- SELECT uc.user_id, uc.contact_id, uc.role, gi.group_name
--   FROM user_contact uc JOIN group_info gi ON gi.group_id = uc.contact_id
--  WHERE uc.contact_type = 1 AND uc.status = 1;
