-- ============================================================
-- migration-010：密码列宽放宽 + 2026-09-30 IM 能力四表补建
-- ============================================================
-- 说明：补齐两处「只改了基线 easychat.sql、没有迁移脚本」造成的存量库漂移。
--   1) user_info.password 实际为 varchar(32)，BCrypt 哈希 60 字符写不进去：
--      登录成功前的「MD5 → BCrypt 自动升级」会触发
--      `Data too long for column 'password'`，接口返回 HTTP 500 / CODE_1002。
--      即：BCrypt 改造（2026-09-30）上线后，存量库登录必然 500。
--   2) emoji / favorite / user_status / operation_log 四张表在存量库不存在，
--      对应后端接口（/emoji/*、/favorite/*、/userStatus/*、登录失败写操作日志）
--      在存量库上直接报 SQL 错。
--
-- 执行方式（本机）：
--   cmd /c ""C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -proot
--     -h127.0.0.1 easychat < D:\qd\EasyChat\easychat-migration-010-password-and-im-tables.sql"
--
-- 语义（已通过 L4 人工确认，ADR-005）：
--   · password 列宽 32 → 60，与基线 easychat.sql 逐字一致；放宽为向后兼容变更，
--     存量 MD5 值（32 字符）原样保留，登录时按既有双验证逻辑自动升级。
--   · 四张表结构（含索引、注释、字符集）逐字取自基线 easychat.sql，不重新设计。
--
-- 执行注意：
--   · 全部语句幂等：表用 CREATE TABLE IF NOT EXISTS，重复执行安全。
--   · 已核对无需迁移：chat_message.delete_flag、chat_session_user.top_type/
--     no_disturb/draft、call_log、group_file、report_audit_log、
--     sensitive_word.delete_flag 在存量库均已就位。
--   · 基线 easychat.sql 无需改动（已是最新）。
-- ============================================================

-- ----------------------------
-- 1) password 列宽放宽：varchar(32) → varchar(60)（BCrypt 哈希长度）
-- ----------------------------
ALTER TABLE `user_info`
  MODIFY COLUMN `password` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '密码';

-- ----------------------------
-- 2) 补建四张缺失表（结构取自基线 easychat.sql）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `emoji` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件名',
  `file_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '存储路径',
  `file_size` bigint(20) NULL DEFAULT NULL COMMENT '文件大小',
  `emoji_type` tinyint(1) NULL DEFAULT 0 COMMENT '0=系统 1=自定义',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '创建时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user`(`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '表情包';

CREATE TABLE IF NOT EXISTS `operation_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作用户ID',
  `operation_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作类型',
  `operation_desc` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '操作描述',
  `ip_address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'IP地址',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '操作时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user`(`user_id`) USING BTREE,
  INDEX `idx_type`(`operation_type`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '操作日志';

CREATE TABLE IF NOT EXISTS `favorite` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `message_id` bigint(20) NOT NULL COMMENT '消息ID',
  `content` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '收藏内容',
  `file_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '文件路径',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '创建时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user`(`user_id`) USING BTREE,
  UNIQUE INDEX `uk_user_message`(`user_id`, `message_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '收藏';

CREATE TABLE IF NOT EXISTS `user_status` (
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `content` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '状态文字内容',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '状态图片URL',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '创建时间戳',
  `expire_time` bigint(20) NULL DEFAULT NULL COMMENT '过期时间戳',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '用户状态';
