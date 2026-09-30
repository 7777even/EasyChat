# Tasks — 操作日志

- 关联 Design: 2026-09-30-operation-log/design.md
- 创建日期: 2026-09-30
- 预估总工时: 4h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## 阶段一：后端基础结构

- [x] 新增 `operation_log` 表 + `OperationLog` 实体 + `OperationLogMapper` — ≤1h
- [x] 新增 `OperationLogService` — ≤1h
- [x] 在 `UserInfoServiceImpl` 登录/改密时记录日志 — ≤30min
- [x] 在 `ChatMessageServiceImpl` 删消息时记录日志 — ≤30min
- [x] 在 `UserInfoServiceImpl` 强制下线时记录日志 — ≤30min

## 阶段二：后端验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min

## 阶段三：收尾

- [x] spec-delta 回写 `openspec/specs/operation-log/spec.md` — ≤30min
- [ ] 归档 Change 到 `openspec/archive/2026-09-30-operation-log` — ≤15min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-30-operation-log/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [x] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方
- [ ] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [ ] QA / Retro 记录已落 `engineering/`
