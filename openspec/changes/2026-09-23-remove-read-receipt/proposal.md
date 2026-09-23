# Proposal — 删除已读回执功能（协议 / 服务 / 端点 / UI / 回执表）

- 创建日期: 2026-09-23
- 效率等级: L4（命中 §8：WebSocket 协议帧 -3/-5 变更 + 数据库表结构变更，实施前须人工确认）

## Why

用户明确要求"把已读功能删掉"。已读回执体系（客户端 `-3` 上报、服务端 `-5` 通知、`/chat/markRead`、`/chat/batchGetAck`、`message_read_record` 表、消息旁"已读/已送达"徽标）横跨协议、服务、端点、UI 五层，属完整特性下线，不是局部缺陷修复。

## What Changes

- 后端（easychat-java）：
  - **协议帧**：删除 `-3`（CLIENT_ACK 上报）在 `HandlerWebSocket` 的 case 分发与 `handleClientAck` 处理；删除 `-5`（ACK_NOTIFY）的生产方 `ChannelContextUtils.sendAckNotify`。`HandlerWebSocket` 的 switch **无 default 分支**，移除后旧客户端仍发送的 `-3` 帧将静默忽略（兼容成立）。
  - **常量**：删除 `Constants.WS_CLIENT_ACK_MESSAGE_TYPE(-3)`、`WS_ACK_NOTIFY_MESSAGE_TYPE(-5)`。
  - **端点**：删除 `ChatController` 的 `POST /api/chat/markRead`、`GET /api/chat/batchGetAck` 及 `messageReadService` 注入。
  - **服务**：删除 `MessageReadService` 接口与 `MessageReadServiceImpl` 实现（含 `markRead` / `markDelivered` / `batchAck` / `batchGetAckType` / `markAck`）；删除 `MessageStatusEnum.DELIVERED(2)` 成员（仅该服务引用）。
  - **不动**：`-1` 持久化 ACK、`pendingMap`/`registerPendingAck` 可靠性机制、`chat_message.status`、会话未读数 `noReadCount`（grep 证实未读数不经过 `messageReadService`，且其多端同步走 `-6 SYNC_SESSION`）。
- 前端（easychat-front）：
  - **主进程**：删除 `wsClient.js` 的 `sendClientAck`（发 `-3`）与 `case -5` 分支；删除 `ipc.js` 的 `sendClientAck` 通道（`onSendClientAck`）及 `index.js` 装配。保留 `onRegisterPendingAck`/`registerPendingAck`（-1 可靠性）。
  - **渲染层**：删除 `Chat.vue` 进入会话批量已读上报（`sendReadAckForSession`）、收到消息即时已读上报、`ackNotify` 监听与 `ackType` 维护；删除 `ChatMessage.vue` 发送方"已读/已送达"徽标块（L34-40）与 `.read-status-tip` 样式；删除 `Api.js` 的 `markRead`、`batchGetAck` 定义（**两者均无现存调用方**）。
  - **不动**：会话列表未读红点（`noReadCount`，与本特性独立）。
- 数据库：
  - 删除 `message_read_record` 表——**运行库 `DROP TABLE IF EXISTS` + `easychat.sql` 同步删除 L66-69 起的建表块**（§6.4）。
  - 存量数据：回执表数据随表删除（回执为衍生数据，无业务主体依赖；回退方案见下）。

## Capabilities

- C1: 已读回执功能整体下线——双端不再产生/消费 `-3`/`-5` 帧，`/markRead`、`/batchGetAck` 端点不存在，消息旁无"已读/已送达"徽标，`message_read_record` 表不存在；消息收发、未读红点、撤回、-1 可靠性 ACK 行为不变。

## Impact

- 对外接口：删除 2 个 HTTP 端点；WS 帧类型集合收缩（-3/-5），帧包络结构、`Result<T>` 包络、错误码分段、`/api/` 前缀均不变（不触 §8 其余硬门禁）。
- 兼容性：旧客户端连新后端——`-3` 无 case 且无 default → no-op；旧后端连新客户端——不再有 -5 监听方，无影响。
- 存量数据：`message_read_record` DROP；`easychat.sql` 同步删块，运行库与初装脚本恢复一致。
- spec：`multi-device-sync` 的 Requirement「跨端已读状态广播」移除；其下「未读数多端一致」Scenario（基于 `-6`，与已读回执无关）**迁移**至「跨端会话状态同步」，不得连坐删除。
- 回退方案：revert 本变更代码提交；数据库回退 = git revert 恢复 `easychat.sql` 建表块后重新 `CREATE TABLE`（回执表为衍生数据，丢失不需还原）。

---

## ☐ 人工确认关卡

> 本提案待人工确认后才允许进入实施（L4，§1 / §7.1）。
>
> - [x] 同意方案，允许继续（含：已读与已送达徽标一并删除；`message_read_record` 表 DROP 并同步 `easychat.sql`）（2026-09-23 人工确认）
> - [ ] 同意但保留 `message_read_record` 表（仅删代码与 UI，不动表结构）
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
