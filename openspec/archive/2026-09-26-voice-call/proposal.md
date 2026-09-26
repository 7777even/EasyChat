# Proposal — 语音/视频通话（WebRTC P2P + 现有 Netty WS 信令中继）

- 创建日期: 2026-09-26
- 效率等级: L4（新增实时媒体能力，命中 AGENTS.md §6.2 WebSocket 协议与 §8 高风险；涉及 Netty WS 帧类型扩展、前端 getUserMedia / RTCPeerConnection、新增 call_log 表）

## Why

四·新增能力里唯一未启动项。当前体系支持文本 / 图片 / 文件 / 语音消息，但**没有任何实时语音/视频通话能力**。用户无法在桌面端发起、接听通话，与 IM 产品形态不符。其余遗留项已清理并推送，通话是最后一块拼图。经 L4 二次关卡，本变更范围确认为：**音视频 + 群通话 + TURN 中继 + 通话记录持久化**。

## What Changes

- 后端（Netty WS 信令中继，**零媒体处理**，媒体走 P2P）：
  - 新增 WS 帧类型：`call_invite` / `call_accept` / `call_reject` / `call_signal`（SDP/ICE 中继）/ `call_hangup` / `call_cancel` / `call_busy` / `call_join`（群呼成员加入）
  - 在现有 Netty `ChannelInboundHandler` 中按 `messageType` 路由；复用现有 `UserChannelContext` 会话映射，不新建连接
  - **通话房间注册表（内存）**：`callId → {发起方, 类型(1-on-1/群), 参与者集合, 各自接听状态, 开始时间}`。仅用于信令路由与通话记录，服务器绝不接触媒体
  - `Constants` 新增消息类型枚举与协议常量；单聊须为好友、群呼须为同群成员（防骚扰，命中 §6.2-4）
  - **TURN 配置注入**：服务端 `application.properties` 配置 STUN+TURN（`easychat.turn.*`），在通话信令引导帧中随 WS 下发 `iceServers`，**不新增 HTTP 接口**
  - **通话记录**：通话结束时服务端落地 `call_log`（新增表），记录主叫/被叫或群组、媒体类型、起止时间、状态、参与人数
- 前端（Electron 渲染进程 WebRTC，Chromium 原生支持，无新 npm 依赖）：
  - 新增 `views/chat/CallWindow.vue` 通话浮窗（来电提醒 / 通话中视频网格 / 挂断 / 静音 / 关摄像头 / 群成员状态）
  - 新增 `utils/WebRTC.js`（封装 `getUserMedia(audio+video)` + `RTCPeerConnection` 网格(full-mesh) + 信令收发与 ICE 候选收集）
  - `Chat.vue`（单聊）与 `GroupChat.vue`（群）头部「音视频通话」按钮；`WebSocket.js` 客户端新增对应消息监听并分发到 Store
  - 信令与聊天消息共用现有 WS 长连接（5051）
- ICE：默认公共 STUN + 部署环境提供的 TURN（凭据经 WS 下发，客户端不入源码）

## Capabilities

- C1: 单聊或群会话中一方可发起音视频通话；在线成员收到来电提醒，可接听 / 拒绝 / 忙线
- C2: 通话建立后双方经 WebRTC P2P 传输音视频；支持静音、关摄像头、挂断；通话中状态多端实时同步
- C3: 信令（offer / answer / ICE candidate）经后端 WS 中继，前端无需直连对方网络地址；TURN 保障对称 NAT 连通；连接失败有明确兜底提示
- C4: 任一方挂断 / 取消 / 忙线 / 离会，相关方收到对应状态帧并正确结束浮窗、释放 `RTCPeerConnection` 与设备
- C5: 通话结束（任一方式）后服务端持久化一条 `call_log`，可在「通话记录」中回溯（v1 仅落库，管理端查询可后续独立变更）

## Impact

- 对外接口: 无新增 HTTP 接口，仅扩展 WS 帧类型（协议变更，按 L4 评审）+ TURN 配置经 WS 下发
- 存量数据: 新增 `call_log` 表（migration-008），需同步 `easychat.sql` 基线
- 配置: 新增 `easychat.turn.*` 属性（STUN/TURN url+凭据）；缺省仅 STUN 仍可工作（对称 NAT 降级兜底）
- 性能 / 安全: 媒体 P2P 不经服务器；信令须校验好友 / 同群关系；ICE 候选做尺寸上限；TURN 凭据服务端下发、不落前端源码
- 群呼约束: 采用 full-mesh，参与者上限 `easychat.call.max-participants`（默认 6）；超出由服务端在 invite 阶段拒绝并提示，避免无 SFU 下的连接爆炸
- 回退方案: 移除新增 WS 帧类型、前端 `CallWindow.vue` / `WebRTC.js` 与 `call_log` 表即可回退；回退需 `DROP call_log`（有数据迁移，属 L4 风险已评估）

---

## ✓ 人工确认关卡

> 本提案经 **用户（Product Owner）** 于 2026-09-26 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续

> **L4 实施前二次关卡（§7.1，写代码前必过）** —— 已于 2026-09-26 经用户点头，范围确认如下：
> 1. 媒体类型：**音视频（audio+video）**（非仅语音）
> 2. 通话范围：**含群通话**（非仅 1-on-1）
> 3. TURN：**提供 TURN 中继**（服务端配置并下发，对称 NAT 可连通）
> 4. 通话记录：**持久化 call_log**（新增表，记录起止/状态/参与人）
>
> - [x] 范围确认，允许实施
