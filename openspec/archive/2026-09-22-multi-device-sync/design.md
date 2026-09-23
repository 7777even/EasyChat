# Design — 多端漫游与同步

- 关联 Proposal: 2026-09-22-multi-device-sync/proposal.md
- 创建日期: 2026-09-22

## 1. 架构设计

### 核心思路

将 `USER_CONTEXT_MAP: ConcurrentHashMap<String, Channel>` 改为 `ConcurrentHashMap<String, ChannelGroup>`，利用 Netty 的 `ChannelGroup` 实现单用户多 channel 的线程安全管理与广播写。

```
┌─────────────────────────────────────────────────────┐
│                 ChannelContextUtils                   │
│                                                       │
│  USER_CONTEXT_MAP:                                    │
│    userId ──→ ChannelGroup [ch1, ch2, ch3, ...]        │
│                                                       │
│  GROUP_CONTEXT_MAP (不变):                            │
│    groupId ──→ ChannelGroup [chA, chB, ...]            │
└─────────────────────────────────────────────────────┘
```

### 时序图：多端消息投递

```
Device-A (ch1)                     Server                    Device-B (ch2)
    │                                 │                           │
    │ ──── WebSocket connect ──────→  │                           │
    │                                 │ ←──── WebSocket connect ─│
    │                                 │                           │
    │  双方 USER_CONTEXT_MAP           │  USER_CONTEXT_MAP         │
    │  均加入同一 ChannelGroup         │                           │
    │                                 │                           │
    │                                 │  ←── 他人发消息 ───       │
    │                                 │                           │
    │  ←── broadcast to all ch ───   │  ←── broadcast to all ch ─│
    │                                 │                           │
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `ChannelContextUtils` | `USER_CONTEXT_MAP` → `ChannelGroup` 映射；addContext/removeContext/sendMsg/sendAck/sendAckNotify 全面多端广播 | 连接管理 |
| `Constants` | 新增 `WS_SYNC_SESSION_MESSAGE_TYPE = -6`（跨端会话同步） | 协议常量 |
| `ChatMessageServiceImpl` | 消息入库后广播 SYNC_SESSION 帧给发送方的所有其他设备 | 业务编排 |
| `HandlerWebSocket` | 处理 SYNC_SESSION 类型的回显确认（可选）；扩展 acknowledge | WS 帧处理 |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `wsClient.js` | 新增处理 `messageType = -6` (SYNC_SESSION)：刷新 session 列表并本地持久化 | WS 帧分发 |
| `Chat.vue` | 监听 `syncSession` IPC 事件，实时替换/合并会话列表；多端 ACK 后实时更新 UI | 渲染层 |
| `ipc.js` | 新增 `onSyncSession` handler，向渲染层转发 SYNC_SESSION | IPC 转发 |

## 2. 接口设计

**无新增 HTTP 端点**。本特性仅扩展 WS 协议。

### 新增 WS 帧类型

| messageType | 方向 | 用途 |
|-------------|------|------|
| `-6` (SYNC_SESSION) | server→client | 通知其他设备：当前某会话的状态已变更（未读数、最后一条消息等），请刷新 |

### SYNC_SESSION 帧格式

```json
{
  "messageType": -6,
  "extendData": {
    "sessionId": "xxx",
    "lastMessage": "新消息内容",
    "lastReceiveTime": 1727000000000,
    "noReadCount": 3,
    "status": 1
  }
}
```

> **广播策略**: SYNC_SESSION 帧仅广播给 senderUserId 的其他设备（排除触发此次变更的那台设备），避免回环。

## 3. 数据模型

**无表结构变更**。复用以下已有表：

- `chat_message`（含多端已读通过 `message_read_record` 表）
- `message_read_record`（功能二新增的已读记录表）
- `chat_session_user`（会话元数据，多端各自本地 SQLite 维护一份）

## 4. 安全设计

- 鉴权:  Token 机制不变；每个 channel 独立验证 Token
- 数据权限:  SYNC_SESSION 帧只推送给同一 `userId` 的其他 ChannelGroup 成员
- 回环排除:  在广播 SYNC_SESSION 时，通过 `channel` 引用比较跳过源 channel

## 5. ADR

### ADR-001: 选用 ChannelGroup 而非手动 List<Channel>

- 状态: 已接受
- 上下文: 多端并发读写用户连接集合，需保证线程安全
- 决策: 复用 Netty `DefaultChannelGroup`（内部 `ConcurrentMap`），`writeAndFlush` 自动迭代所有 channel 并忽略已关闭 channel
- 后果: 正面——零额外同步代码；负面——ChannelGroup 是 Netty 框架类，与 GROUP_CONTEXT_MAP 结构不矛盾（用途场景已区分）

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 单用户 ChannelGroup 无限增长（僵尸 channel） | 低 | 内存 | `removeContext` 确保在 channel inactive/close 时移除 |
| 广播风暴（高频消息 × 多端） | 低 | 网宽 | 通常 ≤3 端；SYNC_SESSION 仅在有会话元变更时触发（非每条消息） |
| 回环通知 | 中 | UX | 广播时排除源 channel |
