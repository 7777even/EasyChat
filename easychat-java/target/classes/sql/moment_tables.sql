-- 朋友圈动态表
CREATE TABLE IF NOT EXISTS `moment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` VARCHAR(15) NOT NULL COMMENT '用户ID',
  `content` TEXT COMMENT '文本内容',
  `media_type` TINYINT DEFAULT 0 COMMENT '媒体类型 0纯文字 1图片 2视频 3图文混合',
  `location` VARCHAR(100) COMMENT '地理位置',
  `visibility` TINYINT DEFAULT 0 COMMENT '可见范围 0公开 1好友 2私密 3白名单 4黑名单',
  `visible_list` TEXT COMMENT '白名单/分组列表 JSON',
  `invisible_list` TEXT COMMENT '黑名单过滤列表 JSON',
  `status` TINYINT DEFAULT 1 COMMENT '状态 1正常 0删除',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  `update_time` BIGINT NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈动态表';

-- 朋友圈媒体表
CREATE TABLE IF NOT EXISTS `moment_media` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `moment_id` BIGINT NOT NULL COMMENT '朋友圈ID',
  `file_path` VARCHAR(500) NOT NULL COMMENT '文件路径',
  `media_type` TINYINT NOT NULL COMMENT '媒体类型 0图片 1视频',
  `cover_path` VARCHAR(500) COMMENT '封面路径（视频）',
  `width` INT COMMENT '宽度',
  `height` INT COMMENT '高度',
  `duration` INT COMMENT '时长（秒）',
  PRIMARY KEY (`id`),
  KEY `idx_moment_id` (`moment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈媒体表';

-- 朋友圈点赞表
CREATE TABLE IF NOT EXISTS `moment_like` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `moment_id` BIGINT NOT NULL COMMENT '朋友圈ID',
  `user_id` VARCHAR(15) NOT NULL COMMENT '用户ID',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_moment_user` (`moment_id`, `user_id`),
  KEY `idx_moment_id` (`moment_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈点赞表';

-- 朋友圈评论表
CREATE TABLE IF NOT EXISTS `moment_comment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `moment_id` BIGINT NOT NULL COMMENT '朋友圈ID',
  `user_id` VARCHAR(15) NOT NULL COMMENT '评论用户ID',
  `parent_id` BIGINT DEFAULT 0 COMMENT '父评论ID 0表示一级评论',
  `reply_to_user_id` VARCHAR(15) COMMENT '回复的用户ID',
  `content` TEXT NOT NULL COMMENT '评论内容',
  `status` TINYINT DEFAULT 1 COMMENT '状态 1正常 0删除',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_moment_id` (`moment_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈评论表';

-- 朋友圈通知表（用于消息提醒）
CREATE TABLE IF NOT EXISTS `moment_notify` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `moment_id` BIGINT NOT NULL COMMENT '朋友圈ID',
  `from_user_id` VARCHAR(15) NOT NULL COMMENT '操作用户ID',
  `to_user_id` VARCHAR(15) NOT NULL COMMENT '接收用户ID',
  `notify_type` TINYINT NOT NULL COMMENT '通知类型 1点赞 2评论 3回复',
  `content` TEXT COMMENT '通知内容',
  `is_read` TINYINT DEFAULT 0 COMMENT '是否已读 0未读 1已读',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_to_user` (`to_user_id`, `is_read`),
  KEY `idx_moment_id` (`moment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈通知表';
