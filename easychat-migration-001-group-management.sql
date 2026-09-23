-- ============================================================
-- 迁移 001：群管理（群成员角色 / 禁言）
-- 适用：MySQL 5.7，在已有 easychat.sql 基础上升级 user_contact 表
-- 日期：2026-09-22
-- 说明：重复执行请先去重，本脚本假设目标字段尚不存在
-- ============================================================

-- 1. 新增字段（MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，此处按首次迁移处理）
ALTER TABLE `user_contact`
  ADD COLUMN `role` tinyint(1) NULL DEFAULT 2
    COMMENT '群成员角色（仅群组 contact_type=1 有效）0:群主 1:管理员 2:成员',
  ADD COLUMN `mute_end_time` datetime NULL DEFAULT NULL
    COMMENT '禁言到期时间（仅群组有效，NULL表示未被禁言）';

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
