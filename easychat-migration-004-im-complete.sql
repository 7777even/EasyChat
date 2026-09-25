-- =====================================================================
-- IM 能力补齐（一~四项）：缺字段补字段、缺表建表
-- 覆盖：置顶跨端 / 免打扰 / 草稿 / 好友备注分组 / 消息扩展（转发·引用·@）
--      / 邮箱验证码（找回密码）/ 群文件 / 内容举报 / 敏感词
-- =====================================================================
-- ⚠️ 一次性迁移脚本，执行前请备份数据库
--    可重复执行：通过 information_schema 判断列/表是否已存在
--    适用于 MySQL 5.7+

-- ---------- 通用：安全加列存储过程 ----------
DROP PROCEDURE IF EXISTS `ec_add_column`;
DELIMITER $$
CREATE PROCEDURE `ec_add_column`(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_define VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_define);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- ---------- 1. chat_session_user：置顶 / 免打扰 / 草稿 ----------
-- top_type    0未置顶 1置顶（跨端同步，服务端真源）
-- no_disturb  0正常 1免打扰（不闪烁、不响铃）
-- draft       本地未发送的草稿（跨端同步）
CALL ec_add_column('chat_session_user', 'top_type',
    'tinyint(1) NULL DEFAULT 0 COMMENT ''0未置顶 1置顶''');
CALL ec_add_column('chat_session_user', 'no_disturb',
    'tinyint(1) NULL DEFAULT 0 COMMENT ''0正常 1免打扰''');
CALL ec_add_column('chat_session_user', 'draft',
    'varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''会话草稿''');

-- ---------- 2. user_contact：好友备注 / 分组 ----------
CALL ec_add_column('user_contact', 'remark',
    'varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''好友备注名''');
CALL ec_add_column('user_contact', 'group_name',
    'varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''好友分组名''');

-- ---------- 3. chat_message：扩展数据 / @ 提及 / 语音时长 ----------
-- extra_data   JSON：{"quoteId":123,"quoteContent":"...","quoteUserId":"U...","forwardFrom":"U..."}
-- at_user_ids  逗号分隔的被 @ 用户 ID
CALL ec_add_column('chat_message', 'extra_data',
    'varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''消息扩展数据JSON（引用/转发/@）''');
CALL ec_add_column('chat_message', 'at_user_ids',
    'varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''被@的用户ID，逗号分隔''');
CALL ec_add_column('chat_message', 'duration',
    'int(11) NULL DEFAULT NULL COMMENT ''语音/视频时长秒''');

-- ---------- 4. 邮箱验证码（注册校验 / 忘记密码找回） ----------
CREATE TABLE IF NOT EXISTS `email_verify_code` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '邮箱',
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '验证码',
  `type` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0注册 1找回密码 2修改邮箱',
  `status` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0未使用 1已使用',
  `expire_time` bigint(20) NOT NULL COMMENT '过期时间戳毫秒',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '创建时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_email_type`(`email`, `type`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '邮箱验证码';

-- ---------- 5. 群文件 / 群相册 ----------
CREATE TABLE IF NOT EXISTS `group_file` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `group_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '群ID',
  `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件名',
  `file_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '存储路径',
  `file_size` bigint(20) NULL DEFAULT NULL COMMENT '文件大小',
  `file_type` tinyint(1) NULL DEFAULT 0 COMMENT '0图片 1视频 2文件',
  `cover_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '封面',
  `upload_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '上传人',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '上传时间毫秒',
  `status` tinyint(1) NULL DEFAULT 1 COMMENT '1正常 0删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_group`(`group_id`, `create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '群文件/群相册';

-- ---------- 6. 内容举报 ----------
CREATE TABLE IF NOT EXISTS `moment_report` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `moment_id` bigint(20) NULL DEFAULT NULL COMMENT '被举报动态ID',
  `comment_id` bigint(20) NULL DEFAULT NULL COMMENT '被举报评论ID',
  `report_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '举报人',
  `reason` tinyint(1) NULL DEFAULT 0 COMMENT '0色情 1暴力 2诈骗 3侵权 4其他',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '补充说明',
  `status` tinyint(1) NULL DEFAULT 0 COMMENT '0待处理 1已处理 2已驳回',
  `handle_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理人',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '举报时间毫秒',
  `handle_time` bigint(20) NULL DEFAULT NULL COMMENT '处理时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_status`(`status`, `create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '内容举报';

-- ---------- 7. 敏感词 ----------
CREATE TABLE IF NOT EXISTS `sensitive_word` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `word` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '敏感词',
  `level` tinyint(1) NULL DEFAULT 1 COMMENT '1提醒 2替换 3禁止发送',
  `status` tinyint(1) NULL DEFAULT 1 COMMENT '1启用 0停用',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '创建时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_word`(`word`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '敏感词';

-- ---------- 8. 消息举报（聊天内容）复用 moment_report 的 comment_id 之外，单独建表 ----------
CREATE TABLE IF NOT EXISTS `message_report` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `message_id` bigint(20) NOT NULL COMMENT '被举报消息ID',
  `report_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '举报人',
  `reason` tinyint(1) NULL DEFAULT 0 COMMENT '0色情 1暴力 2诈骗 3侵权 4其他',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '补充说明',
  `status` tinyint(1) NULL DEFAULT 0 COMMENT '0待处理 1已处理 2已驳回',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '举报时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_status`(`status`, `create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '消息举报';

DROP PROCEDURE IF EXISTS `ec_add_column`;
