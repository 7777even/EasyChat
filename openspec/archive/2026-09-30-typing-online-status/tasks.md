# Tasks — 输入状态与在线状态实时感知

- 关联 Design: 2026-09-30-typing-online-status/design.md
- 创建日期: 2026-09-30
- 预估总工时: 6h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## 阶段一：后端基础结构

- [x] 新增 `MessageTypeEnum` 帧类型：`TYPING_STATUS(21)` / `ONLINE_STATUS(22)` / `USER_STATUS_CHANGE(23)` — ≤30min
- [x] 新增 `OnlineStatusEnum` 枚举：`ONLINE(1)` / `BUSY(2)` / `OFFLINE(3)` — ≤15min
- [x] `RedisComponet` 新增 `updateUserStatus` / `getUserStatus` 方法 — ≤30min
- [x] `ChannelContextUtils` 断线时自动更新用户状态为离线 — ≤30min
- [x] `MessageHandler` 处理输入状态帧并中继给对方 — ≤1h
- [x] `MessageHandler` 处理状态变更帧并更新 Redis — ≤30min

## 阶段二：后端验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [ ] 后端 `mvn test` 通过（含新增测试） — ≤30min

## 阶段三：前端主进程

- [x] `wsClient.js` 新增 `sendTypingStatus` 方法（防抖 3 秒） — ≤30min
- [x] `wsClient.js` 新增 `sendUserStatusChange` 方法 — ≤15min
- [x] `wsClient.js` 监听 `ONLINE_STATUS` 帧并更新本地状态 — ≤30min
- [x] 用户退出/断线时自动发送离线状态 — ≤15min

## 阶段四：前端渲染进程

- [x] `Chat.vue` 显示"正在输入..."提示 — ≤30min
- [x] `Contact.vue` 显示好友在线/离线状态 — ≤30min
- [x] `Setting.vue` 用户可设置自己的状态（在线/忙碌/离线） — ≤30min

## 阶段五：验证与同步

- [x] 前端 ESLint / Prettier / Vite build 通过 — ≤30min
- [ ] 同步 `engineering/qa/` 验证报告 — ≤30min
- [ ] 同步 `engineering/retro/` 复盘记录 — ≤30min

## 阶段六：收尾

- [ ] spec-delta 回写 `openspec/specs/typing-online-status/spec.md` — ≤30min
- [ ] 归档 Change 到 `openspec/archive/2026-09-30-typing-online-status` — ≤15min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-09-30-typing-online-status/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [ ] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方
- [ ] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [ ] QA / Retro 记录已落 `engineering/`
