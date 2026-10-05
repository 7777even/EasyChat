# Spec Delta — 补齐 5 份迁移脚本的幂等性守卫

- 关联 Tasks: `2026-10-05-migration-idempotency-guards/tasks.md`
- 创建日期: 2026-10-05
- 目标 capability: `migration-automation`

## ADDED Requirements

### Requirement: 迁移脚本可重复执行（migration-idempotency，C1）

仓库根 `easychat-migration-*.sql` 中**所有**结构性 DDL 语句（`ALTER TABLE ... ADD COLUMN` / `ADD [UNIQUE] INDEX` / `DROP INDEX`） SHALL 具备存在性守卫：目标已存在时退化为 `DO 0` 而非报错。任意一份脚本 SHALL 可连续执行两次而两次均成功，且数据库结构 SHALL 不变。

#### Scenario: 重复执行不报错

- **WHEN** 对已执行过的库再次执行 `easychat-migration-009-message-delete.sql`
- **THEN** 退出码 SHALL 为 0，SHALL NOT 出现 `ERROR 1060 Duplicate column name`
- **AND** `chat_message` 结构 SHALL 与执行前一致

#### Scenario: 首次执行照常生效

- **WHEN** 对缺少目标列/索引的库首次执行脚本
- **THEN** 守卫条件为假，SHALL 执行与补齐前**逐字相同**的 DDL
- **AND** 最终结构与基线 `easychat.sql` 一致

---

### Requirement: 守卫在 MySQL 5.7 上可用且 Flyway 可解析（guard-syntax-portability，C2）

守卫 SHALL 使用 MySQL 5.7 支持的动态 SQL（`SET @var := IF(...)` + `PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE`），SHALL NOT 使用 `DELIMITER`、`CREATE PROCEDURE|FUNCTION|TRIGGER|EVENT`，SHALL NOT 使用 MariaDB 专有的 `DROP INDEX IF EXISTS`。

#### Scenario: 不含 Flyway 不兼容指令

- **WHEN** 门禁扫描全部迁移脚本
- **THEN** 代码段（剥注释后）SHALL NOT 含 `DELIMITER` 与 `CREATE PROCEDURE|FUNCTION|TRIGGER|EVENT`

#### Scenario: 同一会话内连续多条守卫语句

- **WHEN** 一份脚本内含多条 `PREPARE stmt FROM @ddl`
- **THEN** 每条 SHALL 在 `EXECUTE` 后 `DEALLOCATE PREPARE stmt`，SHALL NOT 出现 `ERROR 1243 Unknown prepared statement handler`

---

### Requirement: 组合语句按目标态守卫（compound-statement-guard，C4）

一条 `ALTER TABLE` 内同时包含 `DROP INDEX` 与 `ADD [UNIQUE] INDEX` 的语句，SHALL 以**目标对象**（新索引）是否存在作为守卫条件，而**非**被 DROP 的对象。

#### Scenario: 已迁移过的库重跑组合语句

- **WHEN** 对已存在 `uk_word_flag`、且 `uk_word` 已被删除的库执行 `easychat-migration-007-sensitive-word-admin.sql`
- **THEN** 守卫命中、整条退化为 `DO 0`，SHALL NOT 报 `ERROR 1091 Can't DROP`

#### Scenario: 未迁移的库执行组合语句

- **WHEN** 库中仍存在 `uk_word`、不存在 `uk_word_flag`
- **THEN** SHALL 正常执行 `DROP INDEX uk_word, ADD UNIQUE INDEX uk_word_flag(...)`

---

## MODIFIED Requirements

### Requirement: 迁移自动化（migration-automation）

迁移脚本 SHALL 在**结构变更**与**脚本写法**两个维度同时满足：不仅编号连续、可被 Flyway 打包执行，且自身可重复执行。

#### Scenario: 门禁覆盖幂等性

- **WHEN** 运行 `node scripts/verify/verify_migration_flyway.mjs`
- **THEN** SHALL 额外断言「全部迁移脚本的结构性 DDL 均被守卫包裹」
- **AND** 存在任一无守卫 DDL 时 SHALL 以非 0 退出

**变更前**: 门禁只校验 DELIMITER / 编号连续 / `baseline-version` 与最大编号一致 / 迁移已打包进产物。
**变更后**: 上述全部保留，**并新增**结构性 DDL 的守卫存在性校验 —— 该缺口是本缺陷得以长期存在的直接原因。

---

## REMOVED Requirements

无。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 可重复执行且安全 | ADDED: 迁移脚本可重复执行 | 2.1~2.5, 4.1~4.5 |
| C2 最终表结构零变化 | ADDED: 迁移脚本可重复执行 / 首次执行照常生效 | 4.6 |
| C3 防复发机控 | MODIFIED: 迁移自动化 | 3.1~3.4 |
| C4 组合语句幂等 | ADDED: 组合语句按目标态守卫 | 2.5, 4.5 |
| （约束，非能力） | ADDED: 守卫在 MySQL 5.7 上可用且 Flyway 可解析 | 2.1, 4.7 |
