# Tasks — 单聊撤回实时同步到发送方自己的多台设备

- 关联 Design: 2026-09-23-recall-sender-sync/design.md
- 创建日期: 2026-09-23
- 预估总工时: 2h
- 完成日期: 2026-09-23

> 任务按实施顺序排列；单条 ≤2h。
> 项目无单测基建（既有 Change 均以 compile + 冒烟 + 日志证据验收），本 Change 沿用同一口径，不引入新测试依赖。

## 阶段一：后端实现

- [x] 1.1 `ChannelContextUtils.send2User`：在 `applyContactConvert` 之前、仅当 `messageType == RECALL_MESSAGE(14)` 且 `sendUserId` 非空时，取未转换副本直投 `USER_CONTEXT_MAP.get(sendUserId)`（含空组判空） — ≤1h
- [x] 1.2 `mvn compile` 通过 — ≤30min
- [x] 1.3 启动冒烟：单聊撤回接口调用成功，后端日志出现发送方副本投递证据（含 sendUserId、设备数），且对端投递不受影响 — ≤1h
- [x] 1.4 走查 `recallMessage` 既有消息归属/权限校验仍存在（不修改，仅记录进 QA） — ≤15min
- [x] 1.5 走查前端链路：`wsClient.js` 自发消息跳过豁免 `messageType != 14`、`case 14` 按 messageId 更新、`Chat.vue messageType == 14` 分支——确认零改动结论成立 — ≤15min

## 阶段二：验证证据

- [x] 2.1 QA 断言帧语义：发送方副本帧 JSON 的 `contactId` 为会话对方（≠ sendUserId），对端帧经转换后 contactId = 发送方——以日志/抓帧证据入 QA — ≤30min
- [x] 2.2 同步 `engineering/qa/` 验证报告（含实际执行命令、日志快照引用、未运行项说明） — ≤30min

## 阶段三：收尾

- [x] 3.1 同步 `engineering/retro/` 复盘记录（做得好 / 问题 / 原因 / 改进方案） — ≤30min
- [x] 3.2 修正归档遗留 R2：`openspec/archive/2026-09-22-multi-device-sync/spec-delta.md` 溯源列 `2.4` 笔误（一并修 C2/C3 行同源笔误 `2.2`/`2.3`，阶段二仅存在 2.1） — ≤10min
- [x] 3.3 spec-delta 回写 `openspec/specs/multi-device-sync/spec.md` + `git mv` 归档本 Change — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-23-recall-sender-sync/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵对应行执行，`mvn compile` 0 error、接口链路冒烟通过（3/3 断言 PASS，见 QA 证据）
- [x] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方（本变更：无契约/结构变更，前端零改动）
- [x] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
