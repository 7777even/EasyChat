/*
 Navicat Premium Data Transfer

 Source Server         : localhost_3306
 Source Server Type    : MySQL
 Source Server Version : 50739 (5.7.39-log)
 Source Host           : localhost:3306
 Source Schema         : easychat02

 Target Server Type    : MySQL
 Target Server Version : 50739 (5.7.39-log)
 File Encoding         : 65001

 Date: 07/06/2025 14:05:40
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for app_update
-- ----------------------------
DROP TABLE IF EXISTS `app_update`;
CREATE TABLE `app_update`  (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `version` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '版本号',
  `update_desc` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '更新描述',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `status` tinyint(1) NULL DEFAULT NULL COMMENT '0:未发布 1:灰度发布 2:全网发布',
  `grayscale_uid` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '灰度uid',
  `file_type` tinyint(1) NULL DEFAULT NULL COMMENT '文件类型0:本地文件 1:外链',
  `outer_link` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '外链地址',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_key`(`version`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4  COMMENT = 'app发布';

-- ----------------------------
-- Table structure for chat_message
-- ----------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message`  (
  `message_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '消息自增ID',
  `session_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话ID',
  `message_type` tinyint(1) NOT NULL COMMENT '消息类型',
  `message_content` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '消息内容',
  `send_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '发送人ID',
  `send_user_nick_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '发送人昵称',
  `send_time` bigint(20) NULL DEFAULT NULL COMMENT '发送时间',
  `contact_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '接收联系人ID',
  `contact_type` tinyint(1) NULL DEFAULT NULL COMMENT '联系人类型 0:单聊 1:群聊',
  `file_size` bigint(20) NULL DEFAULT NULL COMMENT '文件大小',
  `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '文件名',
  `file_type` tinyint(1) NULL DEFAULT NULL COMMENT '文件类型',
  `status` tinyint(1) NULL DEFAULT 1 COMMENT '状态 0:正在发送 1:已发送',
  `seq` bigint(20) NULL DEFAULT NULL COMMENT '会话内单调序号（同 session_id 下严格递增，用于排序/补推/ACK）',
  `client_id` varchar(64) NULL DEFAULT NULL COMMENT '客户端生成的消息去重键（send_user_id + client_id 唯一）',
  `extra_data` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '消息扩展数据JSON（引用/转发/@）',
  `at_user_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '被@的用户ID，逗号分隔',
  `duration` int(11) NULL DEFAULT NULL COMMENT '语音/视频时长秒',
  PRIMARY KEY (`message_id`) USING BTREE,
  INDEX `idx_session_id`(`session_id`) USING BTREE,
  INDEX `idx_send_user_id`(`send_user_id`) USING BTREE,
  INDEX `idx_receive_contact_id`(`contact_id`) USING BTREE,
  INDEX `idx_send_time`(`send_time`) USING BTREE,
  INDEX `idx_session_seq`(`session_id`, `seq`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1775 CHARACTER SET = utf8mb4  COMMENT = '聊天消息表';

-- ----------------------------
-- Table structure for chat_session
-- ----------------------------
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session`  (
  `session_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话ID',
  `last_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '最后接受的消息',
  `last_receive_time` bigint(11) NULL DEFAULT NULL COMMENT '最后接受消息时间毫秒',
  PRIMARY KEY (`session_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4  COMMENT = '会话信息';

-- ----------------------------
-- Table structure for chat_session_user
-- ----------------------------
DROP TABLE IF EXISTS `chat_session_user`;
CREATE TABLE `chat_session_user`  (
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `contact_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '联系人ID',
  `session_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话ID',
  `contact_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '联系人名称',
  `top_type` tinyint(1) NULL DEFAULT 0 COMMENT '0未置顶 1置顶（服务端真源，跨端同步）',
  `no_disturb` tinyint(1) NULL DEFAULT 0 COMMENT '0正常 1免打扰（不闪烁不响铃）',
  `draft` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '会话草稿（跨端同步）',
  PRIMARY KEY (`user_id`, `contact_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_session_id`(`session_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4  COMMENT = '会话用户';

-- ----------------------------
-- Table structure for group_info
-- ----------------------------
DROP TABLE IF EXISTS `group_info`;
CREATE TABLE `group_info`  (
  `group_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '群ID',
  `group_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '群组名',
  `group_owner_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '群主id',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `group_notice` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '群公告',
  `join_type` tinyint(1) NULL DEFAULT NULL COMMENT '0:直接加入 1:管理员同意后加入',
  `status` tinyint(1) NULL DEFAULT 1 COMMENT '状态 1:正常 0:解散',
  PRIMARY KEY (`group_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 ;

-- ----------------------------
-- Table structure for user_contact
-- ----------------------------
DROP TABLE IF EXISTS `user_contact`;
CREATE TABLE `user_contact`  (
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `contact_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '联系人ID或者群组ID',
  `contact_type` tinyint(1) NULL DEFAULT NULL COMMENT '联系人类型 0:好友 1:群组',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `status` tinyint(1) NULL DEFAULT NULL COMMENT '状态 0:非好友 1:好友 2:已删除好友 3:被好友删除 4:已拉黑好友 5:被好友拉黑',
  `last_update_time` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  `role` tinyint(1) NULL DEFAULT 2 COMMENT '群成员角色（仅群组 contact_type=1 有效）0:群主 1:管理员 2:成员',
  `mute_end_time` datetime NULL DEFAULT NULL COMMENT '禁言到期时间（仅群组有效，NULL表示未被禁言）',
  `remark` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '好友备注名',
  `group_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '好友分组名',
  PRIMARY KEY (`user_id`, `contact_id`) USING BTREE,
  INDEX `idx_contact_id`(`contact_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4  COMMENT = '联系人';

-- ----------------------------
-- Table structure for user_contact_apply
-- ----------------------------
DROP TABLE IF EXISTS `user_contact_apply`;
CREATE TABLE `user_contact_apply`  (
  `apply_id` int(11) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `apply_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '申请人id',
  `receive_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '接收人ID',
  `contact_type` tinyint(1) NOT NULL COMMENT '联系人类型 0:好友 1:群组',
  `contact_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '联系人群组ID',
  `last_apply_time` bigint(20) NULL DEFAULT NULL COMMENT '最后申请时间',
  `status` tinyint(1) NULL DEFAULT NULL COMMENT '状态0:待处理 1:已同意  2:已拒绝 3:已拉黑',
  `apply_info` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '申请信息',
  PRIMARY KEY (`apply_id`) USING BTREE,
  UNIQUE INDEX `idx_key`(`apply_user_id`, `receive_user_id`, `contact_id`) USING BTREE,
  INDEX `idx_last_apply_time`(`last_apply_time`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 136923 CHARACTER SET = utf8mb4  COMMENT = '联系人申请';

-- ----------------------------
-- Table structure for user_info
-- ----------------------------
DROP TABLE IF EXISTS `user_info`;
CREATE TABLE `user_info`  (
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '邮箱',
  `nick_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '昵称',
  `join_type` tinyint(1) NULL DEFAULT NULL COMMENT '0:直接加入  1:同意后加好友',
  `sex` tinyint(1) NULL DEFAULT NULL COMMENT '性别 0:女 1:男',
  `password` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '密码',
  `personal_signature` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个性签名',
  `status` tinyint(1) NULL DEFAULT NULL COMMENT '状态',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `last_login_time` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `area_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '地区',
  `area_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '地区编号',
  `last_off_time` bigint(13) NULL DEFAULT NULL COMMENT '最后离开时间',
  PRIMARY KEY (`user_id`) USING BTREE,
  UNIQUE INDEX `idx_key_email`(`email`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4  COMMENT = '用户信息';

-- ----------------------------
-- Table structure for user_info_beauty
-- ----------------------------
DROP TABLE IF EXISTS `user_info_beauty`;
CREATE TABLE `user_info_beauty`  (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '邮箱',
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `status` tinyint(1) NULL DEFAULT 0 COMMENT '0：未使用 1：已使用',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_key_user_id`(`user_id`) USING BTREE,
  UNIQUE INDEX `idx_key_email`(`email`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4  COMMENT = '靓号表';

-- ----------------------------
-- Table structure for moment
-- ----------------------------
DROP TABLE IF EXISTS `moment`;
CREATE TABLE `moment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '动态ID',
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '作者ID',
  `content` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '文本内容',
  `media_type` tinyint(1) NULL DEFAULT 0 COMMENT '0无 1图片 2视频 3混合',
  `location` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '地理位置',
  `visibility` tinyint(1) NULL DEFAULT 0 COMMENT '0公开 1好友 2私密 3白名单 4黑名单过滤',
  `visible_list` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '白名单/分组 列表 JSON',
  `invisible_list` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '黑名单过滤列表 JSON',
  `status` tinyint(1) NULL DEFAULT 1 COMMENT '1正常 0删除',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '创建时间毫秒',
  `update_time` bigint(20) NULL DEFAULT NULL COMMENT '更新时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_create`(`user_id`, `create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '朋友圈动态';

-- ----------------------------
-- Table structure for moment_media
-- ----------------------------
DROP TABLE IF EXISTS `moment_media`;
CREATE TABLE `moment_media` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `moment_id` bigint(20) NOT NULL COMMENT '动态ID',
  `file_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件路径或ID',
  `media_type` tinyint(1) NULL DEFAULT 0 COMMENT '0图 1视频',
  `cover_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '封面',
  `width` int(11) NULL DEFAULT NULL COMMENT '宽',
  `height` int(11) NULL DEFAULT NULL COMMENT '高',
  `duration` int(11) NULL DEFAULT NULL COMMENT '时长秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_moment_id`(`moment_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '朋友圈媒体';

-- ----------------------------
-- Table structure for moment_like
-- ----------------------------
DROP TABLE IF EXISTS `moment_like`;
CREATE TABLE `moment_like` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `moment_id` bigint(20) NOT NULL COMMENT '动态ID',
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_moment_user`(`moment_id`, `user_id`) USING BTREE,
  INDEX `idx_user`(`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '朋友圈点赞';

-- ----------------------------
-- Table structure for moment_comment
-- ----------------------------
DROP TABLE IF EXISTS `moment_comment`;
CREATE TABLE `moment_comment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `moment_id` bigint(20) NOT NULL COMMENT '动态ID',
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '评论人',
  `parent_id` bigint(20) NULL DEFAULT 0 COMMENT '父评论ID',
  `reply_to_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '被回复人',
  `content` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '评论内容',
  `status` tinyint(1) NULL DEFAULT 1 COMMENT '1正常 0删除',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_moment_id`(`moment_id`) USING BTREE,
  INDEX `idx_parent`(`parent_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '朋友圈评论';

-- ----------------------------
-- Table structure for moment_notify
-- ----------------------------
DROP TABLE IF EXISTS `moment_notify`;
CREATE TABLE `moment_notify` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '收件人',
  `type` tinyint(1) NOT NULL COMMENT '0新动态 1点赞 2评论 3回复 4@',
  `ref_id` bigint(20) NULL DEFAULT NULL COMMENT '关联ID 动态或评论',
  `from_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '触发人',
  `read_status` tinyint(1) NULL DEFAULT 0 COMMENT '0未读 1已读',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user`(`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '朋友圈通知';

-- ----------------------------
-- Table structure for email_verify_code
-- ----------------------------
DROP TABLE IF EXISTS `email_verify_code`;
CREATE TABLE `email_verify_code` (
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

-- ----------------------------
-- Table structure for group_file
-- ----------------------------
DROP TABLE IF EXISTS `group_file`;
CREATE TABLE `group_file` (
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

-- ----------------------------
-- Table structure for moment_report
-- ----------------------------
DROP TABLE IF EXISTS `moment_report`;
CREATE TABLE `moment_report` (
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
  `handle_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理备注',
  `handle_action` tinyint(1) NULL DEFAULT NULL COMMENT '0仅记录 1删内容 2封禁发布者',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_status`(`status`, `create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '内容举报';

-- ----------------------------
-- Table structure for message_report
-- ----------------------------
DROP TABLE IF EXISTS `message_report`;
CREATE TABLE `message_report` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `message_id` bigint(20) NOT NULL COMMENT '被举报消息ID',
  `report_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '举报人',
  `reason` tinyint(1) NULL DEFAULT 0 COMMENT '0色情 1暴力 2诈骗 3侵权 4其他',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '补充说明',
  `status` tinyint(1) NULL DEFAULT 0 COMMENT '0待处理 1已处理 2已驳回',
  `handle_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理人',
  `handle_time` bigint(20) NULL DEFAULT NULL COMMENT '处理时间毫秒',
  `handle_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理备注',
  `handle_action` tinyint(1) NULL DEFAULT NULL COMMENT '0仅记录 1删内容 2封禁发布者',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '举报时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_status`(`status`, `create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '消息举报';

-- ----------------------------
-- Table structure for report_audit_log
-- ----------------------------
DROP TABLE IF EXISTS `report_audit_log`;
CREATE TABLE `report_audit_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `report_id` bigint(20) NOT NULL COMMENT '关联举报记录ID（moment_report/message_report 的 id）',
  `report_type` tinyint(1) NOT NULL COMMENT '1动态 2评论 3消息',
  `target_id` bigint(20) NULL DEFAULT NULL COMMENT '被举报对象ID',
  `admin_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理管理员ID',
  `action` tinyint(1) NULL DEFAULT NULL COMMENT '1已处理 2已驳回',
  `handle_action` tinyint(1) NULL DEFAULT NULL COMMENT '0仅记录 1删内容 2封禁发布者',
  `handle_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理备注',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '处理时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_report`(`report_id`, `report_type`) USING BTREE,
  INDEX `idx_admin`(`admin_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '举报处置审计日志';

-- ----------------------------
-- Table structure for sensitive_word
-- ----------------------------
DROP TABLE IF EXISTS `sensitive_word`;
CREATE TABLE `sensitive_word` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `word` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '敏感词',
  `level` tinyint(1) NULL DEFAULT 1 COMMENT '1提醒 2替换 3禁止发送',
  `status` tinyint(1) NULL DEFAULT 1 COMMENT '1启用 0停用',
  `delete_flag` bigint(20) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0=存活，非0=删除时间戳毫秒',
  `create_time` bigint(20) NULL DEFAULT NULL COMMENT '创建时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_word_flag`(`word`, `delete_flag`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '敏感词';

SET FOREIGN_KEY_CHECKS = 1;
