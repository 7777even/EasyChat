-- ============================================================
-- migration-006：举报处理 / 管理端审计 数据库变更
-- ============================================================
-- 说明：本文件为「内容治理 - 举报处理与审计」配套 DDL。
--   开发与生产环境请按需手动执行（本仓库不自动跑迁移）。
--   幂等提示：若已执行过，请跳过对应 ALTER / CREATE。
--   执行前建议先备份：mysqldump -uroot -p easychat > backup.sql
-- ============================================================

-- 1) message_report 补齐处理字段（原表缺 handle_user_id/handle_time/handle_note/handle_action）
ALTER TABLE `message_report`
  ADD COLUMN `handle_user_id` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理人' AFTER `status`,
  ADD COLUMN `handle_time`   bigint(20) NULL DEFAULT NULL COMMENT '处理时间毫秒' AFTER `handle_user_id`,
  ADD COLUMN `handle_note`   varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理备注' AFTER `handle_time`,
  ADD COLUMN `handle_action` tinyint(1) NULL DEFAULT NULL COMMENT '0仅记录 1删内容 2封禁发布者' AFTER `handle_note`;

-- 2) moment_report 补齐处理备注与动作字段（原表已有 handle_user_id/handle_time）
ALTER TABLE `moment_report`
  ADD COLUMN `handle_note`   varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理备注' AFTER `handle_time`,
  ADD COLUMN `handle_action` tinyint(1) NULL DEFAULT NULL COMMENT '0仅记录 1删内容 2封禁发布者' AFTER `handle_note`;

-- 3) 新增举报处置审计日志表（每次处置动作追加一条，不可变）
CREATE TABLE IF NOT EXISTS `report_audit_log` (
  `id`           bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `report_id`    bigint(20) NOT NULL COMMENT '关联举报记录ID（moment_report/message_report 的 id）',
  `report_type`  tinyint(1) NOT NULL COMMENT '1动态 2评论 3消息',
  `target_id`    bigint(20) NULL DEFAULT NULL COMMENT '被举报对象ID',
  `admin_id`     varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理管理员ID',
  `action`       tinyint(1) NULL DEFAULT NULL COMMENT '1已处理 2已驳回',
  `handle_action` tinyint(1) NULL DEFAULT NULL COMMENT '0仅记录 1删内容 2封禁发布者',
  `handle_note`  varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理备注',
  `create_time`  bigint(20) NULL DEFAULT NULL COMMENT '处理时间毫秒',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_report`(`report_id`, `report_type`) USING BTREE,
  INDEX `idx_admin`(`admin_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '举报处置审计日志';
