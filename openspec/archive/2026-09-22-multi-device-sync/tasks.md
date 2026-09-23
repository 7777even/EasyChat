# Tasks — 多端漫游与同步

- 关联 Design: 2026-09-22-multi-device-sync/design.md
- 创建日期: 2026-09-22
- 补勾日期: 2026-09-23（归档提交 aeb2e8a 当时未勾选即归档，本次逐条对账代码后补记）

## 阶段一：WebSocket 连接管理模型重构

- [x] 1.1 重构 `ChannelContextUtils`：`USER_CONTEXT_MAP` 改为 `ConcurrentHashMap<String, ChannelGroup>`，addContext/removeContext/sendMsg/sendAck/sendAckNotify 全面支持多端广播 — ≤1h
- [x] 1.2 新增 `Constants.WS_SYNC_SESSION_MESSAGE_TYPE = -6` — ≤5min
- [x] 1.3 `ChatMessageServiceImpl` 消息入库后广播 SYNC_SESSION 帧给发送方其他设备 — ≤30min
- [x] 1.4 撤回消息广播：确认已有撤回逻辑已广播到所有设备（通过 ChannelGroup 自然实现） — ≤10min

## 阶段二：后端编译验证

- [x] 2.1 `mvn compile` 通过 — ≤10min

## 阶段三：前端适配

- [x] 3.1 `wsClient.js` 处理 `messageType = -6` (SYNC_SESSION) 并通知渲染层 — ≤30min
- [x] 3.2 跨端会话同步事件送达渲染层：按现实实现为 `wsClient.js` 持有 `sender` 直连 `sender.send('syncSession', ...)`，无需在 `ipc.js` / `index.js` 注册中转 handler（原设想的 IPC handler 方案作废） — ≤15min
- [x] 3.3 `Chat.vue` 监听 `syncSession` 事件并刷新本地会话列表 — ≤30min

## 阶段四：验证与收尾

- [x] 4.1 `mvn compile` 最终确认 — ≤10min
- [x] 4.2 spec-delta 回写 `openspec/specs/multi-device-sync/spec.md` + 归档 Change — ≤30min

## 逐条对账证据（2026-09-23 补勾依据）

- 1.1 `ChannelContextUtils.java`：`ConcurrentMap<String, ChannelGroup> USER_CONTEXT_MAP = new ConcurrentHashMap()`
- 1.2 `entity/constants/Constants.java:109`：`WS_SYNC_SESSION_MESSAGE_TYPE = -6`
- 1.3 `ChatMessageServiceImpl.java:271`：`channelContextUtils.broadcastSyncSession(sendUserId, sessionData)`（普通消息发送路径）
- 1.4 撤回链路 `recallMessage → messageHandler.sendMessage → Redis topic → sendMessage → send2User(contactId)`：**对端**所有设备经 ChannelGroup 收到 messageType=14；**确认动作已完成，但结论是发送方自己多端未覆盖，见遗留问题 R1**
- 2.1 / 4.1 2026-09-23 实际执行 `mvn compile` exit=0
- 3.1 `wsClient.js:179`：`case -6:` 分支解析并 `sender.send('syncSession', ...)`
- 3.3 `Chat.vue:458`：`ipcRenderer.on('syncSession', ...)` 更新 `lastMessage/lastReceiveTime` 并 `sortChatSessionList`
- 4.2 `openspec/specs/multi-device-sync/spec.md` 已存在；Change 已归档于 `aeb2e8a`

## 遗留问题（归档对账时发现，待人工决策）

- **R1**：spec-delta/spec 的 C4 Scenario 要求「服务端广播撤回帧（messageType=14）给 U 的**所有设备**」（U 为发送方），但实际实现只投递对端（`send2User` 以 `contactId` 为目标），发送方的其他在线设备不会实时看到自己这端发起的撤回。修复属于改业务能力（L3）：需新建 Change 补实现，或修订 spec 降级该 Scenario 承诺，二选一。
- **R2**：spec-delta.md 第 92 行 C4 溯源列写的 `2.4` 任务在 tasks.md 中不存在（阶段二只有 2.1），系笔误，需随 R1 一并修订。
