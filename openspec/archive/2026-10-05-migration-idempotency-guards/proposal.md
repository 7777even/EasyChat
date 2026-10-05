# Proposal — 补齐 5 份迁移脚本的幂等性守卫

- 创建日期: 2026-10-05
- 效率等级: **L4**（数据库结构 / 迁移脚本）
- 台账对应: `docs/system-facts.md` §14 遗留 **#10**

## Why

AGENTS §6.4-3 要求「迁移脚本**必须**幂等」，但 5 份脚本的 DDL 语句无存在性守卫，与该条**长期矛盾**。每份脚本头部都印着给运维的手工执行命令（`mysql < xxx.sql`），而**重复执行必然失败**。

**已实测取证**（对本机开发库执行，结果为 exit=1）：

```
001-group-management        ERROR 1060 (42S21) at line 9:  Duplicate column name 'role'
002-message-reliability     ERROR 1060 (42S21) at line 9:  Duplicate column name 'seq'
006-report-admin            ERROR 1060 (42S21) at line 11: Duplicate column name 'handle_user_id'
007-sensitive-word-admin    ERROR 1060 (42S21) at line 21: Duplicate column name 'delete_flag'
009-message-delete          ERROR 1060 (42S21) at line 23: Duplicate column name 'delete_flag'
```

## 先更正台账的两处失准

本轮实测发现台账 #10 与 AGENTS §6.4-4 的警告均**与现状不符**，一并订正：

| 台账说法 | 实测 | 处置 |
|---|---|---|
| 「**6**/12 迁移脚本不是幂等的」，含 **011** | 011 的 ADD COLUMN **全部已有守卫**（`information_schema` 判存在 + `PREPARE/EXECUTE`），本轮扫描 4/4 命中 | 台账**多算一份**，实为 **5 份** |
| AGENTS §6.4-4 提到 001 注释自述「MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，此处按首次迁移处理」，暗示按只跑一次设计 | 该注释仍在，但脚本头部同时印着可重复执行的手工命令，**自相矛盾** | 补齐守卫后矛盾消除 |

## What Changes

- 后端: 无（不涉及 Java 代码）
- 前端: 无
- 数据库: **仅改迁移脚本的写法，不改任何最终表结构**。补齐守卫后，脚本在「列/索引已存在」时退化为 `DO 0`，结构与现在**逐字节等价**。

改动清单（**7 条语句**，5 份文件）：

| 文件 | 语句 | 缺守卫的表现 |
|---|---|---|
| 001 | `ADD COLUMN user_contact.role` | 1060 Duplicate column |
| 002 | `ADD COLUMN chat_message.seq` | 1060 |
| 002 | `ADD INDEX idx_session_seq` | 1061 Duplicate key name |
| 006 | `ADD COLUMN message_report.handle_user_id` | 1060 |
| 006 | `ADD COLUMN moment_report.handle_note` | 1060 |
| 007 | `ADD COLUMN sensitive_word.delete_flag` | 1060 |
| 007 | `DROP INDEX uk_word, ADD UNIQUE INDEX uk_word_flag(...)`（**组合语句**） | 1091 Can't DROP |

- 新增门禁断言: `scripts/verify/verify_migration_flyway.mjs` 增加「**所有 `ALTER TABLE ... ADD COLUMN/INDEX` 与 `DROP INDEX` 都必须被守卫包裹**」的断言（当前该门禁只校验 DELIMITER / 编号连续 / baseline 一致性，**不校验幂等性** —— 这是机控缺口，也是本缺陷能长期存在的直接原因）。
- `AGENTS.md` §6.4-3: 删除「本条与现状存在偏差」的警告块（现状已对齐）。
- `AGENTS.md` §10: 门禁表补上新增的幂等性断言。
- 台账 #10 结项。

## Capabilities

- C1: **迁移脚本可重复执行且安全** —— 任意一份脚本连续执行两次，两次均 exit 0 且结构不变。
- C2: **最终表结构零变化** —— 补守卫只改变「已存在时怎么办」，不改变「不存在时建什么」。
- C3: **防复发机控** —— 门禁能在 CI / pre-push 阻断「新写的无守卫 DDL」。
- C4: **组合语句幂等** —— `DROP INDEX + ADD UNIQUE INDEX` 这类一条语句内多次改动的写法也能整体跳过。

## Impact

- 对外接口: 不变。
- 存量数据: **零影响**。守卫只在目标已存在时短路；不存在时执行的是与原来**逐字相同**的 DDL。
- **Flyway checksum 风险（本次唯一实质风险）**:
  - Flyway 的 `baseline-version=N` 把 **≤ N 的迁移整体标记为已应用、且不逐条记账**。本机开发库 `flyway_schema_history` 实测**只有 1 行**（`version=12, description='<< Flyway Baseline >>', checksum=NULL`），即 001–012 全部被跳过、**从未写入 checksum**。
  - 按 `application.properties` 记载的两种部署方式（全新环境 `baseline-version=12`；存量环境由运维声明真实版本号，示例 9），001–009 均**在 baseline 之下**，故**不会被执行、也不会被记账 → 改这些文件不触发 `validate-on-migrate` 校验失败**。
  - 残余风险：若存在声明了 `baseline-version ≤ 8` 的环境，006/007/009 会被执行并记账，改动将导致 checksum 不匹配而启动失败。修复手段是运维执行一次 `flyway repair`。**该情形不在文档约定的部署矩阵内**，故列为已知残余风险而非阻塞项。
- 性能 / 安全: 无运行时影响（脚本不在应用启动路径上被 Flyway 执行）。
- 回退方案: 逐文件 `git revert`；因最终结构未变，回退无数据风险。

---

## ☑ 人工确认关卡（已通过）

> 本提案经 **用户（人工决策人）** 于 2026-10-05 确认，允许进入 design / tasks / 实施阶段。
>
> - [x] 同意方案，允许继续（选项：「修复 5 份脚本 + 加幂等门禁」）
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> 确认要点：用户在知悉「台账多算了一份（011 已有守卫）」与「残余风险仅存在于
> 声明 `baseline ≤ 8` 的环境」的前提下仍选择实施。
