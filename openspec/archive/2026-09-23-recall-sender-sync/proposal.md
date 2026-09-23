# Proposal — 单聊撤回实时同步到发送方自己的多台设备

- 创建日期: 2026-09-23
- 效率等级: L3

## Why

已归档 Change `2026-09-22-multi-device-sync` 的 C4 承诺「撤回帧（messageType=14）广播给发送方 U 的**所有设备**」，spec 已回写 `openspec/specs/multi-device-sync/spec.md`；但对账（归档遗留问题 R1）发现实现只投递对端——A 端撤回后，**A 自己的 B/C 设备仍显示旧消息**，规格与实现不一致，需补实现对齐既有 spec。

## What Changes

- 后端:
  - `ChannelContextUtils.send2User`（单聊分发分支）：新增「撤回帧发送方副本」投递——在 `applyContactConvert` 改写 `contactId` **之前**取副本，将未经转换的撤回帧直投 `sendUserId` 自己的 `ChannelGroup`；仅 `RECALL_MESSAGE(14)` 触发，群聊路径不动（发送者本就是群成员，已天然收到）。
  - `ChatMessageServiceImpl.recallMessage`：**不改**（其经 Redis topic 的单次发布已驱动所有节点的监听器执行上述分发）。
  - Controller / 错误码 / 拦截器：无变动。
- 前端: **零改动**——`wsClient.js` 对「自己发的消息」的跳过规则为 `messageType != 14`（自发撤回已放行），`case 14` 按 messageId 更新本地消息；`Chat.vue` 已处理 14。仅走查确认 + 记入 QA。
- 数据库: 无表结构 / 字段变更。

## Capabilities

- C1: 单聊撤回实时同步到发送方多端——A 在设备 1 撤回，A 的其他在线设备实时把该消息更新为已撤回，且帧内 `contactId` 保持为会话对方，不产生"以自己为联系人"的脏会话；发送方无在线设备时由既有 DB 持久化（撤回已改写消息内容）在下次拉取历史时兜底。

## Impact

- 对外接口: 无新增/修改端点，无错误码变动，前端契约调用方无需同步。
- 存量数据: 无影响，无迁移。
- 性能 / 安全: 每次单聊撤回多一次本机 ChannelGroup writeAndFlush（可忽略）；不新增攻击面，撤回复用既有权限校验（任务中走查确认）。
- 回退方案: revert 单个 commit 即回到"仅对端可见撤回"状态，无数据残留。

---

## ☐ 人工确认关卡

> 本提案经 人工（用户会话确认） 于 2026-09-23 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
