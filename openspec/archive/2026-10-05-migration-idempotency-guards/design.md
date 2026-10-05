# Design — 补齐 5 份迁移脚本的幂等性守卫

- 关联 Proposal: `proposal.md`
- 创建日期: 2026-10-05
- 效率等级: L4

## 约束

1. **MySQL 5.7 无原生 `ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS` / `DROP INDEX IF EXISTS`**（`DROP INDEX IF EXISTS` 是 MariaDB 语法，5.7 的 MySQL 不支持）。故必须用动态 SQL。
2. 迁移脚本内**不得含** `DELIMITER` 与 `CREATE PROCEDURE|FUNCTION|TRIGGER|EVENT`（AGENTS §6.4-4，Flyway 解析器过不了，门禁 `verify_migration_flyway.mjs` 断言）。
3. 门禁 `verify_migration_flyway.mjs` 已断言「AGENTS 未声称『全部迁移脚本幂等』」—— 补齐后需同步移除该断言的前提条件。

## 决策

### ADR-001：复用 011 已验证的 `SET @ddl := IF(...)` + `PREPARE/EXECUTE` 写法

不引入新范式，直接沿用 `easychat-migration-011-privacy-settings.sql` 里**已在生产路径上跑过**的写法：

```sql
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'x' AND COLUMN_NAME = 'y') > 0,
  'DO 0',
  'ALTER TABLE ...');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

**理由**：① 范式一致性 —— 同一仓库里已有可运行样本，降低评审与排障成本；② 已过 `verify_migration_flyway.mjs` 的 DELIMITER 断言。

**被否方案**：
- *`CREATE PROCEDURE` + `IF NOT EXISTS` 判断* —— 违反约束 2（Flyway 解析器不支持），否。
- *用 `information_schema` 判断 + 客户端侧 `\!` 调用* —— 非 SQL，否。
- *统一在 XML/运行时做* —— 迁移脚本是独立交付物，运行时已由 Flyway 记账，重复治理无处安放，否。

### ADR-002：`@ddl` 与 `stmt` 每条语句独立命名并 `DEALLOCATE`

沿用 011 的 `@ddl` / `stmt` 命名，但**每条守卫语句后都 `DEALLOCATE PREPARE stmt`**，避免同一会话连跑多条时 prepared statement 名冲突。

**被否方案**：只 PREPARE 不 DEALLOCATE —— 第二次 `PREPARE stmt` 会报 `ERROR 1243: Unknown prepared statement handler`。这正是「看似幂等、实则第二次执行失败」的典型陷阱，必须在模板里就带上 DEALLOCATE。

### ADR-003：组合语句（007 的 `DROP INDEX + ADD UNIQUE INDEX`）以「目标态」为守卫条件

007 的语句是**一条 ALTER 内做两件事**：

```sql
ALTER TABLE `sensitive_word` DROP INDEX `uk_word`, ADD UNIQUE INDEX `uk_word_flag`(`word`,`delete_flag`)
```

守卫条件取**目标索引 `uk_word_flag` 是否已存在**：

- 已存在 → `DO 0`（说明迁移已完成，跳过整条）
- 不存在 → 执行原语句

**理由**：守卫必须表达「这件事是否已经做完」，而不是「第一步是否做过」。若按 `uk_word` 是否存在判断，第二次执行会走进 else 分支并因 `uk_word` 已不存在而报 1091。

### ADR-004：门禁断言的判定口径

新增断言：**剥注释后**，任何匹配 `ALTER TABLE ... ADD COLUMN` / `ADD (UNIQUE )?INDEX` / `DROP INDEX` 的语句，若其所在位置的前 30 行内**既无** `information_schema` **也无** `PREPARE` 包裹，则判定为无守卫 → exit 1。

**口径的已知上限（须登记）**：这是**静态字面量**判定，无法验证「守卫的条件写对了」（把 `COLUMN_NAME = 'role'` 改成 `COLUMN_NAME = 'xxx'` 仍照样通过，AGENTS §2.1 第 3 条同源）。真正的正确性由**「跑两次」的真机验证**兜底，本 Change 的 DoD 含该项。

## 数据影响

- 无 schema 变更；补齐前后 `information_schema` 结构**逐列逐索引一致**。
- DoD 含「补齐前后结构 diff = 空」的比对。

## 验证策略

以**真机实证**为主，不靠静态断言自证：

1. 修复前：5 份脚本各跑一次 → 全部 exit 1（`ERROR 1060`）—— **已取证**。
2. 修复后：每份脚本**连跑两次**，两次均须 exit 0。
3. 修复前后各取一次 `information_schema` 结构快照（列 + 索引 + 类型），diff 必须为空。
4. 门禁断言加入后跑一次确认 exit 0（基线自检），再做**变异检验**：把某份脚本改回无守卫写法，门禁须转红。

## 风险

| 风险 | 缓解 |
|---|---|
| 守卫条件写错列名 → 永远不执行 | §「验证策略」第 2/3 项的真机跑两次 + 结构快照比对 |
| `PREPARE` 后忘 `DEALLOCATE` → 第二次执行失败 | ADR-002 写进模板；跑两次验证必然覆盖 |
| 改了已记账的迁移 → checksum 失配 | 见 proposal Impact；已知残余风险仅在 `baseline ≤ 8` 环境 |
| 门禁误报（把注释里的 DDL 当代码） | 判定前先剥 `--` 与 `/* */` 注释（AGENTS §2.1 第 7 条） |

## 依赖

无新增依赖。
