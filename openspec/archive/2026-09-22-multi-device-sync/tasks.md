# Tasks — 多端漫游与同步

- 关联 Design: 2026-09-22-multi-device-sync/design.md
- 创建日期: 2026-09-22

## 阶段一：WebSocket 连接管理模型重构

- [ ] 1.1 重构 `ChannelContextUtils`：`USER_CONTEXT_MAP` 改为 `ConcurrentHashMap<String, ChannelGroup>`，addContext/removeContext/sendMsg/sendAck/sendAckNotify 全面支持多端广播 — ≤1h
- [ ] 1.2 新增 `Constants.WS_SYNC_SESSION_MESSAGE_TYPE = -6` — ≤5min
- [ ] 1.3 `ChatMessageServiceImpl` 消息入库后广播 SYNC_SESSION 帧给发送方其他设备 — ≤30min
- [ ] 1.4 撤回消息广播：确认已有撤回逻辑已广播到所有设备（通过 ChannelGroup 自然实现） — ≤10min

## 阶段二：后端编译验证

- [ ] 2.1 `mvn compile` 通过 — ≤10min

## 阶段三：前端适配

- [ ] 3.1 `wsClient.js` 处理 `messageType = -6` (SYNC_SESSION) 并通知渲染层 — ≤30min
- [ ] 3.2 `ipc.js` / `index.js` 注册 `onSyncSession` IPC handler — ≤15min
- [ ] 3.3 `Chat.vue` 监听 `syncSession` 事件并刷新本地会话列表 — ≤30min

## 阶段四：验证与收尾

- [ ] 4.1 `mvn compile` 最终确认 — ≤10min
- [ ] 4.2 spec-delta 回写 `openspec/specs/multi-device-sync/spec.md` + 归档 Change — ≤30min
