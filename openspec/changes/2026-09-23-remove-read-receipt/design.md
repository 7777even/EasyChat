# Design — 删除已读回执功能

- 关联 Proposal: 2026-09-23-remove-read-receipt/proposal.md
- 创建日期: 2026-09-23

## 1. 现状链路（下线对象全景）

```
【上报】Chat.vue 打开会话/收到消息
  → ipcRenderer.send('sendClientAck', {ackType:3, messageIds})
  → ipc.js onSendClientAck → wsClient.sendClientAck
  → WS 帧 messageType=-3 {ackType, messageIds}
  → HandlerWebSocket case -3 → handleClientAck
  → MessageReadServiceImpl.batchAck（写 message_read_record）

【通知】MessageReadServiceImpl.batchAck/markAck
  → ChannelContextUtils.sendAckNotify → WS 帧 -5 {messageId, contactId=ackUserId, extendData=ackType}
  → wsClient case -5 → sender.send('ackNotify')
  → Chat.vue onAckNotify → msg.ackType 升级（2→3 只升不降）
  → ChatMessage.vue 徽标「已读 / 已送达」

【HTTP】Api.markRead(/chat/markRead)、Api.batchGetAck(/chat/batchGetAck)
  → ChatController markRead()/batchGetAck()
  → MessageReadServiceImpl.markRead()/batchGetAckType()
  （两端点在前端均无现存调用方，属死契约）

【数据】message_read_record 表（运行库存在；easychat.sql L66-69 建表块）
```

## 2. 决策（ADR）

- **ADR-001 范围：已读与"已送达"徽标一体删除。**
  两者共用同一套 `-3/-5` 帧、同一张表、同一 `ackType` 字段（2=送达 / 3=已读），UI 只是同一徽标的两个取值分支。仅删"已读"会留下无法产生的 ackType=2 死链路。用户指令"删掉已读功能"按整套回执体系理解；若人工确认要求保留送达，需另行提案（意味着保留 -3/-5，仅删部分场景，收益趋近于零）。
- **ADR-002 数据：DROP `message_read_record` 并同步 `easychat.sql`。**
  运行库删表而初装脚本保留会造成双向漂移；同步删除后"运行库 = 初装脚本"。回执为衍生数据，无业务主体引用（`markDelivered`/`markRead` 为唯一写入方），删除不破坏其他功能。回退 = revert 恢复 `easychat.sql` 建表块后重建。
- **ADR-003 不动独立体系：`noReadCount` 未读数、`-1` 持久化 ACK、`chat_message.status`。**
  证据：`messageReadService` 全仓注入点仅 `ChatController`、`HandlerWebSocket` 两处（G1）；`noReadCount` 的 service/controller 层引用为 0（G4），多端未读同步走 `-6 SYNC_SESSION`；`registerPendingAck`/`pendingMap` 属消息可靠性协议（clientId + -1），与回执无关。前端未读红点不经过 `ackType`。
- **ADR-004 兼容：删 case 不加 default。**
  `HandlerWebSocket` 的消息类型 switch 当前无 default 分支（全文件 grep 为 0），删除 -3 case 后，旧客户端发送的 `-3` 自然落入"无匹配即跳过"，静默忽略；不引入新分支，不改帧包络。旧后端 + 新客户端：新客户端不再监听 -5，无感知差异。
- **ADR-005 spec 处置：移除 + 迁移，不连坐。**
  `multi-device-sync/spec.md` 的「跨端已读状态广播」（L26-34）随特性移除；其下「未读数多端一致」Scenario（L36-39，基于 `-6` 帧）描述的是未读数多端一致，与 -3/-5 无关，迁入相邻的「跨端会话状态同步」Requirement（L43）之下，保持规格覆盖不缩水。

## 3. 风险与依赖

- `Chat.vue` 删除块行号密集（L203/L207-234、L339-343、L439-452、L578），按**符号**（函数名/监听器名）定位删除，禁止按行号盲删，避免误伤相邻 -1 ACK 逻辑（L140-156 区域）。
- `ipc.js` 中 `sendClientAck` 与 `registerPendingAck` 同文件相邻导入（L5），删除须精确到符号。
- 冒烟依赖双端在线：当前 A' 实例渲染层停在登录页（主进程经 CDP 注入连接），B 实例登录态正常；冒烟可复用 B 实例 + CDP 注入法，或人工登录后执行（记录于 tasks 冒烟任务）。
- 无测试基建（项目无 src/test）：验证口径 = `mvn compile` + 双仓 grep 残留归零 + `@vue/compiler-sfc` 解析 + 双端冒烟存证（与既往 Change 一致，不虚构 TDD）。

## 4. 数据影响

- `DROP TABLE IF EXISTS message_read_record`（运行库）；`easychat.sql` 删除 L66 起建表块（含 DROP/CREATE/约束行，删除时以实际块边界为准）。
- 无字段/索引变更、无数据迁移；其他表零影响。

## 5. 三层交互时序图（下线链路；对齐前端 AGENTS §4）

链路跨「渲染层 → 主进程 → Netty 后端」；该链路不经 preload（渲染层直接使用 `window.ipcRenderer`，为既有历史用法，本变更只删不增）：

【删除前】

```
Chat.vue（打开会话 / 收到消息）
  │ renderer: ipcRenderer.send('sendClientAck', {ackType:3, messageIds})
  ▼
ipc.js onSendClientAck ──► wsClient.sendClientAck
  │ main → 后端: WS 帧 messageType=-3 {ackType, messageIds}
  ▼
HandlerWebSocket case -3 ──► MessageReadServiceImpl.batchAck ──► INSERT message_read_record
  │ 后端 → 发送方: WS 帧 messageType=-5 {messageId, contactId=ackUserId, extendData=ackType}
  ▼
wsClient case -5 ──► sender.send('ackNotify')
  │ main → renderer
  ▼
Chat.vue onAckNotify ──► msg.ackType 升级(2→3) ──► ChatMessage.vue 徽标「已读/已送达」
（HTTP 支线：Api.markRead/batchGetAck → ChatController /markRead /batchGetAck → 同一 Service）
```

【删除后】

```
renderer：无上报(sendReadAckForSession / 即时已读)、无 ackNotify 监听、无徽标、Api 死端点定义删除
main：无 sendClientAck / onSendClientAck / case -5
后端：-3 落入 switch「无匹配」静默忽略（未新增 default）；-5 发送方(sendAckNotify)删除；端点 404；表 DROP
保留：-1 持久化 ACK(registerPendingAck/pendingMap/case -1)、-2 SYNC、-4 心跳、-6 SYNC_SESSION、
      noReadCount 未读红点、chat_message.status 发送状态 —— 均零改动
```
