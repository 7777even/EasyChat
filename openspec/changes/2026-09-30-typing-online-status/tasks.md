# Tasks — 输入状态与在线状态实时感知

- 关联 Design: 2026-09-30-typing-online-status/design.md
- 创建日期: 2026-09-30
- 预估总工时: 6h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## ⚠ 状态：已从 archive/ 移回，变更未完成（2026-10-01 复核）

原于 2026-09-30 归档，但复核发现**验证与收尾阶段全部缺失**，与 AGENTS §7.1「全勾必归档」不符，
故移回 `changes/` 继续执行。当前进度：**功能代码已全部落地，过程记录与 spec 回写为零**。

已落地（复核确认存在）：
- 后端帧类型 `TYPING_STATUS(21)` / `ONLINE_STATUS(22)` / `USER_STATUS_CHANGE(23)`
- `HandlerWebSocket.handleTypingStatus` / `handleUserStatusChange`（`git grep -c` 命中 2）
- 前端链路 `preload/index.js` → `ipc.js` → `wsClient.js` → `MessageSend.vue` 的
  `sendTypingStatus`（三处命中）

**未完成**（复核确认缺失）：
- `engineering/qa/` 下**无** typing-online-status 报告（`git ls-files engineering | grep typing` 为空）
- `engineering/retro/` 下**无** 复盘记录
- `openspec/specs/typing-online-status/spec.md` **不存在**，spec-delta 未回写
- 「后端 `mvn test` 通过（含新增测试）」—— 本 Change 未新增测试用例

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
