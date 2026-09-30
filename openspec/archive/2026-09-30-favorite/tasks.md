# Tasks — 收藏功能

- 关联 Design: 2026-09-30-favorite/design.md
- 创建日期: 2026-09-30
- 预估总工时: 4h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## 阶段一：后端基础结构

- [x] 新增 `favorite` 表 + `Favorite` 实体 + `FavoriteMapper` — ≤1h
- [x] 新增 `FavoriteService` — ≤1h
- [x] 新增 `FavoriteController` 接口 — ≤1h

## 阶段二：后端验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min

## 阶段三：前端

- [ ] `ChatMessage.vue` 消息右键菜单添加"收藏"选项 — ≤1h
- [ ] `Favorite.vue` 收藏列表页面 — ≤1h

## 阶段四：验证与同步

- [ ] 前端 ESLint / Prettier / Vite build 通过 — ≤30min
- [ ] 同步 `engineering/qa/` 验证报告 — ≤30min
- [ ] 同步 `engineering/retro/` 复盘记录 — ≤30min

## 阶段五：收尾

- [ ] spec-delta 回写 `openspec/specs/favorite/spec.md` — ≤30min
- [ ] 归档 Change 到 `openspec/archive/2026-09-30-favorite` — ≤15min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-09-30-favorite/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [ ] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方
- [ ] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [ ] QA / Retro 记录已落 `engineering/`
