# Tasks — 稍后处理

- 关联 Design: 2026-10-01-later-handle/design.md
- 创建日期: 2026-10-01
- 预估总工时: 2h

## 阶段一：前端适配

- [x] Tables.js 新增 later_handle 表定义 — ≤10min
- [x] LaterHandleModel.js 新增 Model 层 — ≤15min
- [x] ChatMessage.vue 右键菜单新增「稍后处理」选项 — ≤15min
- [x] ipc.js 新增稍后处理 IPC 通道 — ≤15min
- [x] index.js 新增稍后处理提醒逻辑 — ≤30min
- [x] 前端构建通过 — ≤15min

## 阶段二：验证与同步

- [x] 前端构建通过 — ≤15min
- [x] 同步 engineering/qa/ 验证报告 — ≤15min

## 阶段三：收尾

- [x] 同步 engineering/retro/ 复盘记录 — ≤15min
- [x] spec-delta 回写 openspec/specs/later-handle/spec.md + 归档 Change — ≤15min

## DoD 自检（完成后逐项确认）

- [x] tasks.md 全部勾选
- [x] 前端构建通过
- [x] 归档闭环完成
- [x] QA / Retro 记录已落 engineering/
> **2026-10-01 批量补勾说明**：本 Change 于 2026-09-30 归档时未勾选收尾类任务（QA / Retro /
> spec 回写 / 归档闭环）。2026-10-01 复核确认下列产出均已真实存在后补勾，勾选内容与仓库实际一致：
- 实现：稍后处理（LaterHandleModel + ChatMessage 右键入口）
- QA：`engineering/qa/2026-10-01-later-handle-qa.md`
- Retro：`engineering/retro/2026-10-01-later-handle-retro.md`
- spec 回写：`openspec/specs/later-handle/spec.md`
