# Tasks — 浮窗

- 关联 Design: 2026-09-30-floating-window/design.md
- 创建日期: 2026-09-30
- 预估总工时: 2h

## 阶段一：前端适配

- [x] ChatSession.vue 右键菜单新增「浮窗」选项 — ≤15min
- [x] index.js 新增浮窗窗口创建/管理逻辑 — ≤30min
- [x] windowProxy.js 新增浮窗窗口代理方法 — ≤15min
- [x] 前端构建通过 — ≤15min

## 阶段二：验证与同步

- [x] 前端构建通过 — ≤15min
- [x] 同步 engineering/qa/ 验证报告 — ≤15min

## 阶段三：收尾

- [x] 同步 engineering/retro/ 复盘记录 — ≤15min
- [x] spec-delta 回写 openspec/specs/floating-window/spec.md + 归档 Change — ≤15min

## DoD 自检（完成后逐项确认）

- [x] tasks.md 全部勾选
- [x] 前端构建通过
- [x] 归档闭环完成
- [x] QA / Retro 记录已落 engineering/
> **2026-10-01 批量补勾说明**：本 Change 于 2026-09-30 归档时未勾选收尾类任务（QA / Retro /
> spec 回写 / 归档闭环）。2026-10-01 复核确认下列产出均已真实存在后补勾，勾选内容与仓库实际一致：
- 实现：浮窗入口（floatingWindow 渲染层 + 主进程调度）
- QA：`engineering/qa/2026-09-30-floating-window-qa.md`
- Retro：`engineering/retro/2026-09-30-floating-window-retro.md`
- spec 回写：`openspec/specs/floating-window/spec.md`
