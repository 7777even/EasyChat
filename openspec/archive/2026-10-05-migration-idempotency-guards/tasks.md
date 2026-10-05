# Tasks — 补齐 5 份迁移脚本的幂等性守卫

- 关联 Design: `design.md`
- 创建日期: 2026-10-05
- 效率等级: **L4**

> 状态只回填本文件（AGENTS §7）。

## 0. 前置关卡

- [x] 0.1 **人工确认 proposal** —— 用户于 2026-10-05 选择「修复 5 份脚本 + 加幂等门禁」
- [x] 0.2 **修复前实证** —— 对活库跑 5 份脚本，全部 exit 1（`ERROR 1060`），已取证
- [x] 0.3 **安全前置核对** —— 6 个目标列 + 2 个目标索引在活库**全部已就位**，故跑脚本必然在第一条无守卫语句处失败、不改动任何东西

## 1. 快照（补齐前基线）

- [x] 1.1 取活库 `information_schema` 结构快照（列名+类型+默认值+索引+唯一性）存为证据
- [x] 1.2 快照落在 `engineering/qa/` 同目录，供补齐后 diff

## 2. 补齐守卫（7 条语句）

- [x] 2.1 `001`：`ADD COLUMN user_contact.role` **+ `mute_end_time`**（初版漏了后者：单条 ALTER 内含多列）
- [x] 2.2 `002`：`ADD COLUMN chat_message.seq` **+** `ADD INDEX idx_session_seq`（两条）
- [x] 2.3 `006`：`message_report` 的 4 列（`handle_user_id`/`handle_time`/`handle_note`/`handle_action`）**+** `moment_report` 的 2 列 —— 共 6 条
- [x] 2.4 `007`：`ADD COLUMN sensitive_word.delete_flag`
- [x] 2.5 `007`：`DROP INDEX uk_word, ADD UNIQUE INDEX uk_word_flag(...)` —— **以目标索引 `uk_word_flag` 是否存在为守卫条件**（ADR-003）
- [x] 2.6 每条守卫语句后均带 `DEALLOCATE PREPARE stmt`（ADR-002）
- [x] 2.7 同步更新 5 份脚本头部的「执行注意」：删除「重复执行会报 Duplicate column（1060），属预期，可忽略」类表述，改为声明已幂等
- [x] 2.8 保持既有执行方式说明（`mysql < xxx.sql`）不变

## 3. 门禁（防复发机控）

- [x] 3.1 `verify_migration_flyway.mjs` 新增断言：剥注释后所有 `ADD COLUMN` / `ADD [UNIQUE] INDEX` / `DROP INDEX` 均须被 `information_schema` 或 `PREPARE` 守卫
- [x] 3.2 移除「AGENTS 未声称『全部迁移脚本幂等』」这条断言的前提（现状已对齐，改为正向断言）
- [x] 3.3 基线自检：加完断言后脚本须 exit 0
- [x] 3.4 变异检验 **5/5 捕获、0 漏网、0 无效**，豁免 3（1 等价 + 2 不可静态检测）。过程中查出**新增断言自身的两个缺陷**：① 判定取「向上 1200 字符窗口」→ 相邻语句的守卫替当前语句背书，3 条变异假通过（§2.1 第 8 条）；② `STRUCT_DDL_RE` 的尾反引号必需 → 无反引号标识符（002 的 `ADD COLUMN seq`）扫不到，**断言空转通过**（§2.1 第 3 条：断言通过 ≠ 断言在做事）

## 4. 真机验证（DoD 的核心）

- [x] 4.1 `001` 连跑两次，均 exit 0
- [x] 4.2 `002` 连跑两次，均 exit 0
- [x] 4.3 `006` 连跑两次，均 exit 0
- [x] 4.4 `007` 连跑两次，均 exit 0
- [x] 4.5 `009` 连跑两次，均 exit 0
- [x] 4.6 **补齐后结构快照与 1.1 的 diff = 空**（291 行快照逐项比对，证据在 `engineering/qa/evidence/`）
- [x] 4.7 `verify_migration_flyway.mjs` exit 0；`verify_schema_drift.mjs` exit 0

## 5. 文档与收尾

- [x] 5.1 `AGENTS.md` §6.4-3 删除「本条与现状存在偏差」警告块，改为「已全部补齐守卫」
- [x] 5.2 `AGENTS.md` §10 门禁表补上幂等性断言
- [x] 5.3 spec 回填：合入 `openspec/specs/migration-automation/spec.md`
- [x] 5.4 台账 #10 结项 + **订正「6 份 → 5 份、011 已有守卫」**
- [x] 5.5 `engineering/qa/2026-10-05-migration-idempotency-guards.md`：含修复前 1060 实证、修复后跑两次、结构 diff 空、变异输出
- [x] 5.6 `engineering/retro/2026-10-05-migration-idempotency-guards.md`：四段式
- [x] 5.7 归档 → `openspec/archive/2026-10-05-migration-idempotency-guards`
- [x] 5.8 按 scope 拆提交并推送

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 可重复执行且安全 | ADDED: 迁移脚本可重复执行 | 2.1~2.5, 4.1~4.5 |
| C2 最终表结构零变化 | ADDED: 迁移脚本可重复执行 | 4.6 |
| C3 防复发机控 | MODIFIED: 迁移自动化 | 3.1~3.4 |
| C4 组合语句幂等 | ADDED: 组合语句按目标态守卫 | 2.5, 4.5 |
