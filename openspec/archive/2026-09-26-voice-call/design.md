# Design — 语音/视频通话

- 关联 Proposal: 2026-09-26-voice-call/proposal.md
- 创建日期: 2026-09-26
- 范围确认: 音视频 + 群通话 + TURN + call_log（L4 二次关卡已点头）

## 架构总览

```
┌────────────┐         WS 5051 (信令中继 + 房间路由)      ┌──────────────────┐
│  Electron   │ ── call_invite / call_signal / call_join ─▶│  Netty WS 服务端   │── relay ──▶ 对方 Electron
│  渲染进程    │ ◀─ call_accept / call_hangup / call_busy ──│ (不碰媒体)         │◀──────────
│ WebRTC 网格 │         (仅转发小体积信令 + 维护房间注册表)    │ CallRoomRegistry  │
│ 音视频 P2P   │ ────────── 媒体 (RTP, 直连, full-mesh) ──▶│ + call_log 落库   │
└────────────┘                                          └──────────────────┘
        ▲                                                         │
        │  TURN 凭据经 WS 信令帧下发（iceServers: STUN+TURN）       │ 通话结束
        └─────────────────────────────────────────────────────────┘ 写 call_log
```

- **媒体平面**：WebRTC `RTCPeerConnection` 在参与者间 **full-mesh** 直连（RTP/RTCP），服务器不转发音视频。
- **信令平面**：SDP offer/answer 与 ICE candidate 经现有 Netty WS（5051）以新增帧类型中继；服务端按房间注册表路由到目标 `userId`。
- **复用而非新造**：沿用既有 `UserChannelContext` 会话映射与 WS 鉴权，不新增 WS 端口、不新增 HTTP 接口。
- **TURN 配置下发**：服务端持有 `easychat.turn.*`，在 `call_invite`/`call_accept` 引导帧附带 `iceServers`（含 TURN 凭据），客户端不入源码。

## ADR

### ADR-1：信令复用现有 Netty WS，而非新增端口 / 接口
- 决策：在现有 `ChannelInboundHandler` 的 `messageType` 分支中新增 call_* 类型，按房间注册表中继。
- 理由：避免新增端口带来的防火墙 / 部署复杂度；复用既有连接与鉴权；断线逻辑统一。
- 代价：handler 分支增多，需在路由处显式排除「call_signal 等不落库、不触发普通消息逻辑」。

### ADR-2：WebRTC 在渲染进程执行，无需新 npm 依赖
- 决策：使用 Chromium 原生 `navigator.mediaDevices.getUserMedia({audio:true,video:true})` + `RTCPeerConnection`（Electron 渲染进程即 Chromium）。
- 理由：零新增前端依赖；仅需确认 `webSecurity` / `contextIsolation` 配置不阻断 `getUserMedia` 与 `video` 播放。
- 代价：需在 `BrowserWindow` webPreferences 确认摄像头/麦克风权限与 `navigator.mediaDevices` 可用。

### ADR-3：ICE 默认公共 STUN，TURN 由服务端配置并下发
- 决策：`RTCConfiguration.iceServers` 由服务端在信令引导帧下发（STUN + 可选 TURN）。前端不再硬编码 STUN。
- 理由：对称 NAT 需 TURN 中转才能连通；凭据放服务端避免泄露；缺省无 TURN 时退化为仅 STUN（降级兜底）。
- 代价：服务端需新增 `easychat.turn.*` 配置项与下发逻辑；TURN 凭据需运维维护（建议限时凭据，后续可接凭证接口）。

### ADR-4：群呼采用 full-mesh + 房间注册表，参与者封顶
- 决策：不引入 SFU/媒体服务器（保持「服务器不碰媒体」）；群呼在参与者间 full-mesh，服务端仅维护轻量 `CallRoomRegistry`（callId → 参与者、接听状态、开始时间）用于信令路由与 `call_log` 落库。参与者上限 `easychat.call.max-participants`（默认 6）。
- 理由：在无媒体服务器的约束下，full-mesh 是唯一可行方案；封顶避免 N*(N-1)/2 连接爆炸。
- 代价：超过上限的群呼被服务端在 invite 阶段拒绝；>6 人的高质量群视频需后续引入 SFU（独立变更，不在本范围）。

### ADR-5：通话记录持久化到 `call_log`
- 决策：新增 `call_log` 表（migration-008）；通话以任一方式结束时，服务端依据房间注册表落地一条记录（主叫、类型、群组/被叫、媒体类型、起止时间、状态、参与人数）。
- 理由：用户确认需回溯通话历史；服务端已持有房间生命周期与开始时间，落库自然。
- 代价：新增表需同步 `easychat.sql`；回退需 `DROP`（已在 L4 风险登记）。v1 仅落库，管理端查询列表为后续独立变更。

## 安全约束（对齐 AGENTS.md §6.2）

- 信令发起方必须为收方好友（单聊）或同群成员（群呼）；服务端 `call_invite` 处校验关系，越权返回 `CODE_2401 非好友关系` / `CODE_2302 不在群组中`。
- 信令帧含 `fromUserId` / `toUserId` / `callId`；服务端以 `fromUserId` 来自 WS 会话绑定身份为准，不可由客户端伪造。
- 媒体虽 P2P，但 ICE 候选交换经服务端中转，服务端对候选做尺寸上限，防异常大帧。
- TURN 凭据服务端下发、不落前端源码；客户端仅按帧内 `iceServers` 使用。

## 依赖与数据影响

- 依赖：无新增后端 / 前端运行时依赖。
- 配置：新增 `easychat.turn.url` / `easychat.turn.username` / `easychat.turn.credential`（可选，缺省空 = 仅 STUN）；`easychat.call.max-participants`（默认 6）。
- 数据：新增 `call_log`（id, caller_id, call_type[1=单聊/2=群], peer_id[单聊对方或空], group_id[群或空], media_type[1=音频/2=音视频], start_time, end_time, status[1=已接/2=未接/3=拒接/4=取消/5=忙线], participant_count, create_time）。migration-008 建表并同步 `easychat.sql`。

## 风险

- NAT 穿透失败（TURN 不可用且为对称 NAT）→ 兜底提示改用语音消息（C3）。
- 麦克风/摄像头权限被系统 / Electron 策略拒绝 → 来电前 `getUserMedia` 失败提示引导开启。
- 多端 / 重连：通话中任一方 WS 断线 → 视为挂断，清理对端浮窗与房间。
- 群呼规模：超出 `max-participants` 由服务端拒绝；full-mesh 在 >6 人时质量不可控（已封顶）。
- 回退：`DROP call_log` 为有数据迁移操作，已在 L4 风险登记；正常发布无回退需求。
