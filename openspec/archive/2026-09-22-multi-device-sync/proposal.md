# Proposal — 多端漫游与同步（Multi-Device Roaming & Sync）

- 创建日期: 2026-09-22
- 效率等级: L4（改变 WebSocket 连接语义、影响所有用户的连接管理方式）

## Why

当前 `ChannelContextUtils` 用 `ConcurrentHashMap<String, Channel>` 管理连接——**单用户同一时刻只允许一个活跃连接**。当用户在新设备登录（或重启重连），旧连接被静默覆盖，导致：
1. 多端同时在线时只有最后一个连接能收到实时消息
2. 一端的"已读"操作无法实时同步到其他端
3. 消息漫游仅在重连时通过 INIT 帧批量推送，缺少跨端实时增量同步
4. 用户在 A 端的会话列表变更无法实时反映到 B 端

WhatsApp / Telegram / WeChat 等 IM 产品的核心体验就是"多端无缝漫游"，本特性是该类应用的标杆能力。

## What Changes

- 后端: 重构 `ChannelContextUtils` 连接管理模型：`ConcurrentHashMap<String, Channel>` → `ConcurrentHashMap<String, ChannelGroup>`，同一用户多连接并发广播；新增跨端实时同步帧（session 变更、已读状态、撤回等）；扩展 Constants 协议常量
- 前端: `wsClient.js` 处理新增的 `SYNC_SESSION` 帧类型；`Chat.vue` 监听 session 同步事件并刷新本地会话列表；心跳断线重连时保持 deviceId 不变
- 数据库: 本特性**不改表结构**——复用已有 `chat_message`（含 seq/clientId）和 `message_read_record` 表

## Capabilities

- C1: **多端同时在线**——同一账号可在多个 Electron 实例（或未来的 Web 端）同时登录，所有端都能实时收发消息（不再覆盖旧连接）
- C2: **跨端已读状态广播**——任一端标记已读后，其他端通过 ACK_NOTIFY 帧实时收到通知，会话未读计数和多端消息状态一致
- C3: **跨端会话状态同步**——A 端置顶/删除/新增会话等操作，B 端通过 SYNC_SESSION 帧实时感知并刷新
- C4: **跨端消息撤回同步**——一端撤回消息，其他端也实时看到撤回提示（不再依赖下次 INIT 重连）
- C5: **离线消息多端共享**——任一端登录后拉取离线消息，已推送到其他端的消息不再重复推送（基于 `message_read_record` 去重）

## Impact

- 对外接口: **无新增 HTTP 端点**，仅扩展 WS 协议帧类型（向后兼容——旧客户端不认识新帧类型会忽略）
- 存量数据: 无需迁移，无表结构变更
- 性能 / 安全: 单用户连接数广播带来轻微内存 + 写放大（通常 ≤3 端），可接受；心跳不增不减（每个 channel 独立心跳）
- 回退方案: 将 `USER_CONTEXT_MAP` 恢复为 `Map<String, Channel>` 单连接模型即可完全回退，无需数据回滚

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 2026-09-22 确认，同意方案，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
