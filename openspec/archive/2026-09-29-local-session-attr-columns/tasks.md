# Tasks：本地 SQLite 会话表补 no_disturb / draft 两列

- 关联 Design: `2026-09-29-local-session-attr-columns/design.md`
- 创建日期: 2026-09-29
- 预估总工时: 1.5h

> 任务按实施顺序排列；`[TDD]` 任务先写失败测试再实现。
> **执行说明**：本变更先经人工确认（L4 关卡）后实现，[TDD] 任务的失败测试以「补列前 dump 真实库结构证明两列缺失」形式先行取证，再补列实现。

## 阶段一：取证与结构变更

- [x] **[TDD]** 失败取证：dump 活体 `~/.easychatdev/local.db` 结构，证明 `chat_session_user` 缺 `no_disturb`/`draft` 两列（0.2h）
- [x] **[TDD]** `Tables.js#add_tables` 补两列 DDL + `alter_tables` 补两条存量 ALTER �0.2h

## 阶段二：验证

- [x] **[TDD]** 迁移验证脚本打真实 sqlite3：列存在 + `draft` 写入读回 + `noDisturb` 写入读回 + null 草稿仍被守卫短路 → **5/5 PASS**（0.4h）
- [x] 前端 `electron-vite build --outDir out-verify` 通过（23.33s） �0.2h
- [x] 三门禁：api-contract **0 漂移** / ipc-registration **35 全注册** / openspec-hygiene 通过（0.1h）

## 阶段三：文档与同步

- [x] 回写 `docs/system-facts.md`（本地 SQLite 段 + 变更日志两行） �0.2h
- [x] 同步 `engineering/qa/`（证据：迁移脚本输出 5/5 PASS + build/门禁输出） �0.2h
- [x] 同步 `engineering/retro/`（含四件套滞后于实现的流程偏差复盘） �0.2h
- [x] spec-delta 回写 `openspec/specs/local-sqlite-cache/spec.md` + 归档 Change �0.2h

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] 按 §2 矩阵「Entity / Mapper / SQL / 分页」行对应执行（本地 db 结构变更 → 结构验证 + 构建验证，`mvn` 不适用：后端零改动）
- [x] 数据结构变更已同步（`Tables.js` 即本地 DDL 真源；服务端 `easychat.sql` 无涉及）
- [x] 归档闭环完成
- [x] QA / Retro 记录已落 `engineering/`
