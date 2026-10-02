# Design — 输入状态与在线状态实时感知

- 关联 Proposal: 2026-09-30-typing-online-status/proposal.md
- 创建日期: 2026-09-30

## 1. 架构设计

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `MessageTypeEnum` | 新增 `TYPING_STATUS(21)` / `ONLINE_STATUS(22)` / `USER_STATUS_CHANGE(23)` | 帧类型枚举 |
| `MessageHandler` | 处理输入状态帧，中继给对方；处理状态变更帧，更新 Redis | 消息路由 |
| `ChannelContextUtils` | 维护用户在线状态，断线时更新 Redis | 通道管理 |
| `RedisComponet` | 新增 `updateUserStatus` / `getUserStatus` 方法 | Redis 操作 |
| `wsClient.js` | 新增发送输入状态帧、状态变更帧方法 | WS 客户端 |
| `Chat.vue` | 显示"正在输入..."提示 | UI 展示 |
| `Contact.vue` | 显示好友在线状态 | UI 展示 |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 主进程 `wsClient.js` | 发送输入状态帧（防抖 3 秒） | WS 通信 |
| 主进程 `wsClient.js` | 监听在线状态帧，更新本地状态 | 状态同步 |
| 渲染进程 `Chat.vue` | 显示"正在输入..."提示 | UI 展示 |
| 渲染进程 `Contact.vue` | 显示好友在线/离线状态 | UI 展示 |
| 渲染进程 `Setting.vue` | 用户可设置自己的状态 | 设置入口 |

## 2. 接口设计

无新增 HTTP 接口，仅新增 WebSocket 帧类型。

### WebSocket 帧类型

| 帧类型 | type | 说明 | 方向 |
|--------|------|------|------|
| `TYPING_STATUS` | 21 | 正在输入状态 | 双向 |
| `ONLINE_STATUS` | 22 | 在线状态变更 | 服务端→客户端 |
| `USER_STATUS_CHANGE` | 23 | 用户状态变更请求 | 客户端→服务端 |

### 帧格式

```json
{
  "type": 21,
  "contactId": "U99999999999",
  "typing": true,
  "sessionId": "session-001"
}
```

## 3. 数据模型

无表结构变更。用户状态存 Redis：
- Key: `easychat:ws:user:status:{userId}`
- Value: `1`（在线）/ `2`（忙碌）/ `3`（离线）
- TTL: 7 天

## 4. 安全设计

- 鉴权: WebSocket 连接已鉴权，帧类型无需额外鉴权
- 数据权限: 用户只能发送自己的状态，只能接收好友的状态
- 输入校验: 输入状态帧需校验 `contactId` 和 `typing` 字段
- SQL 注入防护: 无 SQL 操作

## 5. ADR

### ADR-001: 输入状态帧防抖策略

- 状态: 已接受
- 上下文: 输入状态帧频率较高，需要防抖
- 决策: 客户端防抖 3 秒，服务端不防抖（直接中继）
- 后果: 减少网络流量，但可能丢失部分输入状态

### ADR-002: 在线状态存储位置

- 状态: 已接受
- 上下文: 在线状态需要快速读写
- 决策: 存 Redis，不落库
- 后果: 重启后状态丢失，但符合即时通讯场景

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 输入状态帧过多导致网络拥堵 | 中 | 中 | 客户端防抖 3 秒 |
| 在线状态更新延迟 | 低 | 低 | WebSocket 实时推送 |
| 前端不识别新帧类型 | 低 | 低 | 静默忽略，不影响现有功能 |

## 7. 依赖与前提

- 需要 WebSocket 连接已建立
- 需要好友关系已建立（才能看到对方状态）
