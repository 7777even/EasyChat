# Spec Delta — 多端漫游与同步

- 关联 Tasks: 2026-09-22-multi-device-sync/tasks.md
- 创建日期: 2026-09-22

## ADDED Requirements

### Requirement: 多端同时在线（对齐 C1）

系统应支持同一用户账号在多个客户端设备上同时保持 WebSocket 连接，任一设备都能实时接收其他用户发来的聊天消息。

#### Scenario: 双端在线收消息

- **WHEN** 用户 U 在设备 A 和设备 B 同时在线
- **THEN** 其他用户向 U 发送消息时，设备 A 和设备 B 都能在毫秒级收到该消息
- **AND** 服务端 `ChannelContextUtils.sendMsg()` 向 U 的 `ChannelGroup` 中所有通道广播

#### Scenario: 第三端登录加入

- **WHEN** 用户 U 已在 A、B 两端在线，此时在设备 C 登录
- **THEN** 设备 C 通过 INIT 帧获取全量会话和消息，同时 A/B 连接保持不断
- **AND** U 的 `ChannelGroup` 新增第三个通道，后续消息三台广播

---

### Requirement: 跨端已读状态广播（对齐 C2）

任一设备标记已读后，其他设备应实时感知并更新会话未读计数和消息的已读/送达标记。

#### Scenario: A 端已读，B 端实时同步

- **WHEN** 用户 U 在设备 A 打开某会话，触发 `markRead`
- **THEN** 发送方通过 ACK_NOTIFY (-5) 收到已读通知（已有功能二实现）
- **AND** U 的设备 B 也收到 ACK_NOTIFY (-5)（变更前 B 端不会收到，因为其连接被覆盖或根本不存在）

#### Scenario: 未读数多端一致

- **WHEN** 用户 U 在设备 A 查看会话 S 后清除未读
- **THEN** SYNC_SESSION (-6) 帧通知 U 的其他设备更新会话 S 的 `noReadCount = 0`

---

### Requirement: 跨端会话状态同步（对齐 C3）

会话元数据变更（最后一条消息、未读数、置顶等）应在多端实时同步。

#### Scenario: A 端收到新消息，B 端会话列表实时更新

- **WHEN** 用户 U 的设备 B 在线，他人向 U 发来新消息
- **THEN** 服务端在处理新消息入库后，向 U 的其他在线设备广播 SYNC_SESSION (-6) 帧
- **AND** 设备 B 收到后更新本地会话的 `lastMessage`、`noReadCount`（若未在 S 会话）并触发 UI 刷新

---

### Requirement: 跨端消息撤回同步（对齐 C4）

任一设备撤回的消息，其他在线设备也应看到撤回提示。

#### Scenario: A 端撤回消息，B 端实时看到

- **WHEN** 用户 U 在设备 A 撤回一条消息
- **THEN** 服务端广播撤回帧（messageType=14）给 U 的所有设备
- **AND** 设备 B 同步更新本地消息为撤回状态

---

## MODIFIED Requirements

### Requirement: 用户连接管理语义

**变更前（单端）**: `USER_CONTEXT_MAP: Map<String, Channel>` —— 一用户一连接，后登录覆盖前连接。

**变更后（多端）**: `USER_CONTEXT_MAP: Map<String, ChannelGroup>` —— 一用户多连接，并发广播写。`addContext` 向 ChannelGroup 添加通道；`removeContext` 从 ChannelGroup 移除通道（移除后为空则清理 key）。

---

### Requirement: 离线消息投递范围

**变更前**: 向 `USER_CONTEXT_MAP.get(userId)` 单个通道写；为 null 时缓冲到 Redis。

**变更后**: 向 `ChannelGroup` 广播写；Group 为空（或全部 offline）时缓冲到 Redis。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 多端同时在线 | 1.1, 2.1, 3.1 |
| C2 | ADDED: 跨端已读状态广播 | 1.2, 2.1, 3.2 |
| C3 | ADDED: 跨端会话状态同步 | 1.3, 2.1, 3.3 |
| C4 | ADDED: 跨端消息撤回同步 | 1.4, 2.1 |
| 已有连接管理 | MODIFIED: 用户连接管理语义 | 1.1, 2.1 |
| 已有离线消息 | MODIFIED: 离线消息投递范围 | 1.2 |
