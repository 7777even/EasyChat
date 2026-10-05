-- ============================================================
-- migration-007：敏感词库管理端数据库变更
-- ============================================================
-- 说明：本文件为「敏感词库管理端（CRUD + 批量导入导出 + 热更新）」配套 DDL。
--   开发与生产环境请按需手动执行（本仓库不自动跑迁移）。
--   执行前建议先备份：mysqldump -uroot -p easychat > backup.sql
--
-- 幂等性（2026-10-05 补齐，遗留 #10）：
--   **可重复执行**：已存在的列退化为 DO 0；第 2 步以**目标索引 uk_word_flag**
--   是否存在为守卫条件（**不是** 被 DROP 的 uk_word）——
--   若按 uk_word 判断，第二次执行会因 uk_word 已被删除而报
--   ERROR 1091 Can't DROP，即「看似加了守卫、实则仍不可重复执行」。
--   实测：连跑两次均 exit 0，且表结构不变。
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
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sensitive_word'
       AND COLUMN_NAME = 'delete_flag') > 0,
  'DO 0',
  'ALTER TABLE `sensitive_word`
     ADD COLUMN `delete_flag` bigint(20) NOT NULL DEFAULT 0
       COMMENT ''逻辑删除标记：0=存活，非0=删除时间戳毫秒'' AFTER `status`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 唯一索引从仅 word 换成 (word, delete_flag)，否则被删词条占住唯一位、删后无法重导
--    守卫条件取**目标索引 uk_word_flag**（迁移是否已完成），而非被 DROP 的 uk_word
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sensitive_word'
       AND INDEX_NAME = 'uk_word_flag') > 0,
  'DO 0',
  'ALTER TABLE `sensitive_word`
     DROP INDEX `uk_word`,
     ADD UNIQUE INDEX `uk_word_flag`(`word`, `delete_flag`) USING BTREE');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
