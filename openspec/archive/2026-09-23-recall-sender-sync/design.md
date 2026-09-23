# Design — 单聊撤回实时同步到发送方自己的多台设备

- 关联 Proposal: 2026-09-23-recall-sender-sync/proposal.md
- 创建日期: 2026-09-23

## 1. 架构设计

撤回链路现状（多端同步归档后）：

```
A 设备 HTTP /chat/recallMessage
  → ChatMessageServiceImpl.recallMessage        (DB 改写消息内容="该消息已撤回")
  → messageHandler.sendMessage(recallNotify)    (发布 Redis topic)
  → 各节点 MessageHandler 监听器
      → channelContextUtils.sendMessage(dto)
          → USER 分支: send2User(dto)
              → sendMsg(dto, contactId=对端B)   ← 只有对端收到
                 内含 applyContactConvert(type14 白名单): contactId → sendUserId(=A)
          → GROUP 分支: sendMsg2Group           ← 群成员（含发送者）已全收到
```

缺口：USER 分支没有任何一跳把帧送到 `sendUserId=A` 自己的 `USER_CONTEXT_MAP.get(A)`。

改后：

```
          → USER 分支: send2User(dto)
              ├─ [新增] messageType==RECALL 且 sendUserId 非空:
              │     副本 = dto 的未转换快照（contactId 仍为会话对方 B）
              │     直投 USER_CONTEXT_MAP.get(sendUserId)  ← A 的所有在线设备
              └ → sendMsg(dto, contactId=对端B)          （原路径不变）
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | 无 | — |
| Service（`ChatMessageServiceImpl.recallMessage`） | 无 | 既有权限校验、DB 改写保持原样 |
| `ChannelContextUtils.send2User` | 新增撤回副本投递分支 | 分发编排仍在 WS 层，不写业务规则 |
| MessageHandler / Redis topic | 无 | 复用既有监听器：**每个节点**执行 send2User → 副本随 topic 天然多节点扇出 |
| Entity / Mapper / SQL | 无 | — |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 主进程 `wsClient.js` | 无（走查确认 `case 14` + 自发消息跳过豁免 `messageType != 14` 已就绪） | — |
| preload / ipc | 无 | — |
| 渲染进程 `Chat.vue` | 无（走查确认 `messageType == 14` 按 messageId 更新本地消息） | — |

## 2. 接口设计（如有对外接口变更）

无。不新增端点、不改入出参、无错误码。

## 3. 数据模型（如有结构变更）

无。不涉及 `easychat.sql`。

## 4. 安全设计

- 鉴权: 复用 `/chat/recallMessage` 既有 Token 拦截与消息归属校验；本变更不新增端点（任务 1.4 走查归属校验存在性并记入 QA）。
- 数据权限: 副本只投递给 `sendUserId` 本人的 ChannelGroup，不越权扩散。
- 输入校验: 副本为服务端既有 DTO 快照，无新入参。
- SQL 注入: 无 SQL 变动。

## 5. ADR（架构决策记录）

### ADR-001: 发送方副本的投递路径选择

- 状态: 已接受
- 上下文: 需把撤回帧送到发送方自己的设备，且帧内 `contactId` 必须保持"会话对方"语义。约束有三：① `MessageSendDto` 的 `contactId` 被 `sendMsg` 内 `applyContactConvert`（type 14 在白名单）改写为 `sendUserId`，直投若走 `sendMsg` 必然产生 `contactId=自己` 的脏会话（即离线补推历史 bug 模式）；② 给 `MessageSendDto` 加 `targetUserId` 寻址字段 = 改消息分发包络格式，命中 §8 L4 硬门禁；③ 需覆盖多节点。
- 决策: 在 `send2User`（USER 分支、`applyContactConvert` **之前**）对 `RECALL` 帧取未转换副本，直写 `USER_CONTEXT_MAP.get(sendUserId)`；分支以 `contactId` 前缀区分 USER/GROUP，天然不会在群聊路径重复投递。
- 后果:
  - 正面: 不改包络（不触发 L4）、不触发脏会话、随既有 Redis topic 监听器天然多节点扇出、前端零改动。
  - 负面: 撤回专属逻辑下沉到分发层，`send2User` 多一个消息类型分支；发送方无在线设备时不入离线缓冲（由 DB 历史拉取兜底，见 C1）。

> 实施后补记：群聊撤回经 `sendMsg2Group`（发送者本人本就在群 ChannelGroup 内），**不经过** `send2User`，故发送方副本分支在群聊路径不会触发、不存在重复帧——该结论由代码走查确认，QA 未实跑群聊撤回（见 QA「未运行项」）。spec-delta 对应 Scenario 标题定稿为「群聊撤回（分支确认）」。

### ADR-002: 被否决的备选

- 本地直投（`recallMessage` 直调 ChannelContextUtils，仿 `broadcastSyncSession` 先例）: 单节点部署下可用，但多节点时发送方设备连在其他节点即漏投，能力不完整 —— 已拒绝。
- 扩包络加寻址字段: 命中 §8 WebSocket 包络格式 L4，流程重量与风险不成比例 —— 已拒绝。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 副本 `contactId` 被转换成自己 → 脏会话复现 | 中 | 高 | 副本取自 `applyContactConvert` 之前；单聊会话中 `contactId`（未转换）恒为对端；QA 断言帧 JSON `contactId != sendUserId` |
| 取副本与转换的时序写反（先转换后取副本） | 中 | 高 | 实现顺序硬约束写入 tasks 验收标准；QA 用日志/帧内容验证 |
| 群聊重复投递 | 低 | 中 | 分支限定 USER 分支内（`send2User`），GROUP 路径不触达 |
| 发送方在线设备收到重复帧（设备 1 本身已通过 HTTP 响应更新） | 低 | 低 | `Chat.vue` 对 messageId 幂等更新，重复帧无副作用 |
| 撤回权限校验被误删 | 低 | 高 | 本变更不改 `recallMessage`；任务 1.4 走查记录现状 |

## 7. 依赖与前提

- 前置 Change `2026-09-22-multi-device-sync`（已归档）提供的 `USER_CONTEXT_MAP` ChannelGroup 模型与 Redis topic 扇出。
- 无其他 Change 依赖；无部署/配置前提。
- 归档遗留问题 R2（旧 spec-delta 溯源列 `2.4` 笔误）随本 Change 收尾一并修正。
