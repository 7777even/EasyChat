# Tasks — 位置分享

- 关联 Design: 2026-09-30-location-share/design.md
- 创建日期: 2026-09-30
- 预估总工时: 4h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## ⚠ 状态：已从 archive/ 移回，变更未完成（2026-10-01 复核）

原于 2026-09-30 归档，但复核发现**前端阶段完全未实施**，与 AGENTS §7.1「全勾必归档」不符，
故移回 `changes/` 继续执行。当前进度：**后端消息类型已定义，前端为零**。

已落地（复核确认存在）：
- `MessageTypeEnum.LOCATION(25, "", "位置消息")`

**未完成**（复核确认不存在）：
- `ChatMessageLocation.vue` —— 文件不存在；`git ls-files easychat-front/src/renderer/src/views/chat` 无此文件
- `MessageSend.vue` 位置选择入口 —— `git grep -i location -- easychat-front/src` 仅命中 moment 模块（朋友圈定位），聊天模块零命中
- 前端无人渲染 `messageType == 25`，该消息类型**当前无法产生也无法展示**

## 阶段一：后端基础结构

- [x] 新增 `MessageTypeEnum.LOCATION(25)` — ≤15min
- [x] 修改 `ChatMessageServiceImpl` 支持位置消息 — ≤30min

## 阶段二：后端验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min

## 阶段三：前端（**未开始**）

- [ ] `MessageSend.vue` 添加位置选择功能 — ≤1h
- [ ] `ChatMessageLocation.vue` 位置消息展示组件 — ≤1h

## 阶段四：验证与同步

- [ ] 前端 ESLint / Prettier / Vite build 通过 — ≤30min
- [ ] 同步 `engineering/qa/` 验证报告 — ≤30min
- [ ] 同步 `engineering/retro/` 复盘记录 — ≤30min

## 阶段五：收尾

- [ ] spec-delta 回写 `openspec/specs/location-share/spec.md` — ≤30min
- [ ] 归档 Change 到 `openspec/archive/2026-09-30-location-share` — ≤15min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-09-30-location-share/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [ ] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方
- [ ] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [ ] QA / Retro 记录已落 `engineering/`
