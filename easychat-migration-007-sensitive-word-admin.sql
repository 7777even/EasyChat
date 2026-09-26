-- ============================================================
-- migration-007：敏感词库管理端数据库变更
-- ============================================================
-- 说明：本文件为「敏感词库管理端（CRUD + 批量导入导出 + 热更新）」配套 DDL。
--   开发与生产环境请按需手动执行（本仓库不自动跑迁移）。
--   幂等提示：若已执行过，请跳过对应 ALTER / DROP INDEX。
--   执行前建议先备份：mysqldump -uroot -p easychat > backup.sql
--
-- 执行方式（本机）：
--   "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe" -uroot -proot -h127.0.0.1 easychat ^
--     -e "source D:/qd/EasyChat/easychat-migration-007-sensitive-word-admin.sql"
--
-- 语义（ADR-001，已过 L4 人工确认）：
--   delete_flag BIGINT：0=存活；非0=删除时间戳（毫秒）。
--   唯一键 uk_word_flag(word, delete_flag)：
--     - 存活行 delete_flag=0 → 同词唯一（导入/新增查重兜底）
--     - 删除行时间戳互异   → 同词可反复 删 → 导 → 删（唯一索引不被已删行占用）
-- ============================================================

-- 1) 新增逻辑删除字段（0=存活，非0=删除时间戳毫秒）
ALTER TABLE `sensitive_word`
  ADD COLUMN `delete_flag` bigint(20) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0=存活，非0=删除时间戳毫秒' AFTER `status`;

-- 2) 唯一索引从仅 word 换成 (word, delete_flag)，否则被删词条占住唯一位、删后无法重导
ALTER TABLE `sensitive_word`
  DROP INDEX `uk_word`,
  ADD UNIQUE INDEX `uk_word_flag`(`word`, `delete_flag`) USING BTREE;
