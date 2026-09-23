# Capability: 多端漫游与同步

- 创建日期: 2026-09-22
- 状态: 已发布

---

## Requirement: 多端同时在线

系统应支持同一用户账号在多个客户端设备上同时保持 WebSocket 连接，任一设备都能实时接收其他用户发来的聊天消息。

### Scenario: 双端在线收消息

- **WHEN** 用户 U 在设备 A 和设备 B 同时在线
- **THEN** 其他用户向 U 发送消息时，设备 A 和设备 B 都能在毫秒级收到该消息
- **AND** 服务端 `ChannelContextUtils.sendMsg()` 向 U 的 `ChannelGroup` 中所有通道广播

### Scenario: 第三端登录加入

- **WHEN** 用户 U 已在 A、B 两端在线，此时在设备 C 登录
- **THEN** 设备 C 通过 INIT 帧获取全量会话和消息，同时 A/B 连接保持不断
- **AND** U 的 `ChannelGroup` 新增第三个通道，后续消息三台广播

---

## Requirement: 跨端已读状态广播

任一设备标记已读后，其他设备应实时感知并更新会话未读计数和消息的已读/送达标记。

### Scenario: A 端已读，B 端实时同步

- **WHEN** 用户 U 在设备 A 打开某会话，触发 `markRead`
- **THEN** 发送方通过 ACK_NOTIFY (-5) 收到已读通知
- **AND** U 的设备 B 也收到 ACK_NOTIFY (-5)

### Scenario: 未读数多端一致

- **WHEN** 用户 U 在设备 A 查看会话 S 后清除未读
- **THEN** SYNC_SESSION (-6) 帧通知 U 的其他设备更新会话 S 的 `noReadCount = 0`

---

## Requirement: 跨端会话状态同步

会话元数据变更（最后一条消息、未读数、置顶等）应在多端实时同步。

### Scenario: A 端发送消息后，B 端会话列表实时更新

- **WHEN** 用户 U 在设备 A 发送一条聊天消息
- **THEN** 服务端向 U 的其他在线设备广播 SYNC_SESSION (-6) 帧
- **AND** 设备 B 收到后更新本地会话的 `lastMessage`、`lastReceiveTime` 并触发 UI 排序

---

## Requirement: 跨端消息撤回同步

任一设备撤回的消息，其他在线设备也应看到撤回提示。

### Scenario: A 端撤回消息，B 端实时看到

- **WHEN** 用户 U 在设备 A 撤回一条消息
- **THEN** 服务端广播撤回帧（messageType=14）给 U 的所有设备
- **AND** 设备 B 同步更新本地消息为撤回状态

---

## Requirement: 用户连接管理

`USER_CONTEXT_MAP: ConcurrentHashMap<String, ChannelGroup>` 管理同一用户的多个连接。

### Scenario: 连接添加

- **WHEN** 任意设备建立 WS 连接并认证通过（addContext）
- **THEN** 该通道被加入该用户的 ChannelGroup；若 Group 不存在则新建

### Scenario: 连接移除

- **WHEN** 某通道关闭/断线（removeContext）
- **THEN** 从该用户的 ChannelGroup 中移除该通道；若 Group 变为空则清理整个 key

### Scenario: 强制下线

- **WHEN** 管理员调用 closeContext(userId)
- **THEN** 该用户的所有通道均被关闭

---

## Requirement: 离线消息多端共享

任一端登录后拉取离线消息，已推送到其他端的消息不再重复推送。

### Scenario: A 端已收到消息，B 端不再重复推送

- **WHEN** 用户 U 在设备 A 在线并收到新消息，之后设备 B 重连
- **THEN** 设备 B 通过 INIT + SYNC 获取消息时，已通过 `message_read_record` 去重
- **AND** 设备 A 和 B 不会在同一会话中看到重复消息
