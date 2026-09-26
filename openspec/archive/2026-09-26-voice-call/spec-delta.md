# Spec Delta — 语音/视频通话（voice-call）

- 关联 Tasks: 2026-09-26-voice-call/tasks.md
- 创建日期: 2026-09-26

> 格式对齐 `openspec/specs/<capability>/spec.md`。
> 与 proposal Capabilities C1–C5 一一对应。
> 本 delta **新建** `voice-call` capability（实时音视频通话子能力）。
> 确认范围：音视频 + 群通话（full-mesh，封顶默认 6）+ TURN 中继 + call_log 持久化。

## ADDED Requirements

### Requirement: 通话发起与来电提醒（voice-call-invite）— C1

单聊（好友）或群会话（同群成员）中一方必须能够发起音视频通话，在线成员须收到来电提醒并可接听 / 拒绝 / 忙线。

#### Scenario: 单聊发起

- **WHEN** 用户 A（与 B 为好友）在单聊会话点击「音视频通话」
- **THEN** A 本地先请求麦克风+摄像头权限（`getUserMedia({audio,video})`），成功后经现有 WS（5051）向 B 发送 `call_invite`（含 `callId`/`callType=1`/`mediaType`/`fromUserId`/`toUserId` + 服务端下发的 `iceServers`）；A 进入 calling 状态

#### Scenario: 群呼发起

- **WHEN** 用户 A 在群 G（A 为成员）点击「音视频通话」
- **THEN** 服务端校验 A 为 G 成员，向 G 在线成员广播 `call_invite`（`callType=2`, `groupId=G`），并建立 `CallRoomRegistry` 房间；参与者数超过 `max-participants` 时服务端拒绝并提示

#### Scenario: 收到来电提醒

- **WHEN** 目标用户在线且收到 `call_invite`
- **THEN** 渲染进程弹出通话浮窗（来电提醒），显示来电方/群与媒体类型，给出「接听 / 拒绝 / 忙线」；未接听前不占用摄像头/麦克风

#### Scenario: 接听

- **WHEN** 目标点击「接听」并授权设备
- **THEN** 回 `call_accept`（单聊）/ `call_join`（群呼加入）；双端进入 connected，开始 WebRTC P2P 音视频

#### Scenario: 拒绝 / 忙线

- **WHEN** 目标点击「拒绝」→ 回 `call_reject`；或当前已在通话中点击「忙线」→ 回 `call_busy`
- **THEN** 发起方收到对应帧，提示并结束呼叫、释放本地设备

#### Scenario: 非好友 / 非同群被拒

- **WHEN** 单聊向非好友、或群呼由非群成员发起
- **THEN** 服务端校验失败，拒绝信令（单聊 `CODE_2401 非好友关系` / 群 `CODE_2302 不在群组中`），目标不收到任何提醒

---

### Requirement: WebRTC P2P 音视频传输与媒体控制（voice-call-media）— C2

通话建立后，参与者必须经由 WebRTC P2P 直连传输音视频，并支持静音、关摄像头与挂断；状态多端实时同步（含群成员）。

#### Scenario: P2P 音视频建立

- **WHEN** 双端完成 offer/answer 与 ICE 协商
- **THEN** `RTCPeerConnection` 进入 connected，远端音视频轨道被播放；媒体流 P2P 直连，不经服务器转发

#### Scenario: 群呼 full-mesh

- **WHEN** 群呼有 N 个已接听参与者
- **THEN** 每对参与者间建立一条 `RTCPeerConnection`，形成 full-mesh；N 不超过 `max-participants`（默认 6），超出部分不被加入

#### Scenario: 静音 / 关摄像头

- **WHEN** 任一方点击「静音」或「关摄像头」
- **THEN** 本地对应轨道 `enabled=false`，对方听到的音频/看到的视频被关闭；再次点击恢复

#### Scenario: 挂断

- **WHEN** 任一方点击「挂断」
- **THEN** 向相关方发送 `call_hangup`，本地关闭全部 `RTCPeerConnection` + 停止音视频轨道、释放设备；浮窗进入 ended 后关闭

#### Scenario: 状态实时同步

- **WHEN** 通话状态在任一端变化（connected / muted / cam-off / ended / 成员加入离开）
- **THEN** 相关端浮窗依据收到的信令帧同步展示（含群成员列表与各自状态）

---

### Requirement: 信令中继、TURN 与失败兜底（voice-call-signaling-relay）— C3

所有 SDP/ICE 必须经由后端 Netty WS 中继，前端无需直连对方网络；TURN 保障对称 NAT 连通；失败有明确兜底。

#### Scenario: 信令中继与房间路由

- **WHEN** 一端生成 offer/answer 或收集到 ICE candidate
- **THEN** 以 `call_signal` 帧经 WS 发往服务端，服务端按 `CallRoomRegistry` 路由给目标；`call_signal` 不落库、不触发普通消息逻辑

#### Scenario: TURN 配置下发

- **WHEN** 服务端 `application.properties` 配置 `easychat.turn.*`
- **THEN** 引导帧（`call_invite`/`call_accept`）携带 `iceServers`（STUN+TURN 凭据），客户端据此配置 `RTCPeerConnection`；前端源码不含 TURN 凭据

#### Scenario: 信令帧尺寸上限

- **WHEN** 收到的 `call_signal` 帧（SDP / ICE candidate）超过尺寸上限
- **THEN** 服务端丢弃该帧并在对端触发连接失败兜底，不造成服务器异常

#### Scenario: 连接失败兜底

- **WHEN** ICE 协商失败（无 TURN 且对称 NAT，或设备不可用）
- **THEN** 前端检测 `RTCPeerConnection` 失败，提示「当前网络/设备不支持，请改用语音消息」，并清理本地资源

#### Scenario: 不在线路由

- **WHEN** `call_invite`/`call_signal` 目标不在线
- **THEN** 服务端静默丢弃或回状态帧，发起方得到「对方不在线」/超时提示而非无限等待

---

### Requirement: 通话生命周期与资源释放（voice-call-lifecycle）— C4

任一方挂断 / 取消 / 忙线 / 离会，相关方必须收到对应状态帧并正确结束浮窗、释放 `RTCPeerConnection` 与设备。

#### Scenario: 挂断 / 取消通知

- **WHEN** A 挂断（`call_hangup`）或在接听前取消（`call_cancel`）
- **THEN** 相关方收到对应帧，立即结束浮窗、关闭全部连接、停止并释放设备，状态回到 idle；服务端结束房间

#### Scenario: 忙线 / 拒绝通知

- **WHEN** 目标回 `call_busy` / `call_reject`
- **THEN** 发起方收到后提示并释放本地已申请的设备资源

#### Scenario: 群成员离会

- **WHEN** 群呼中某成员挂断/离开
- **THEN** 其余成员收到该成员离会状态，移除其视频网格与对应 `RTCPeerConnection`，房间参与者计数递减；最后一人离开时服务端结束房间

#### Scenario: 断线视为挂断

- **WHEN** 通话中任一方 WS 断线
- **THEN** 相关端在心跳/超时后视为对方挂断，自动结束浮窗并释放资源；服务端清理房间

---

### Requirement: 通话记录持久化（voice-call-log）— C5

每次通话以任一方式结束后，服务端必须持久化一条 `call_log`，可供后续回溯。

#### Scenario: 落库时机与内容

- **WHEN** 一次通话以接听后挂断 / 未接 / 拒接 / 取消 / 忙线 任一种方式结束
- **THEN** 服务端依据 `CallRoomRegistry` 写入一条 `call_log`：`caller_id`、`call_type`(1单聊/2群)、`peer_id`(单聊对方) 或 `group_id`(群)、`media_type`(1音频/2音视频)、`start_time`、`end_time`、`status`(1已接/2未接/3拒接/4取消/5忙线)、`participant_count`、`create_time`

#### Scenario: 单聊与群呼记录区分

- **WHEN** 分别为单聊通话与群呼写入记录
- **THEN** 单聊记录 `call_type=1` 且 `peer_id` 非空、`group_id` 空；群呼记录 `call_type=2` 且 `group_id` 非空、`peer_id` 空、`participant_count>1`

#### Scenario: 查询可回溯（落库口径）

- **WHEN** 调用方按 `caller_id` / `peer_id` / `group_id` 查询
- **THEN** 返回对应 `call_log` 列表（分页遵循 `PageRequest`/`PageResult`）；v1 仅落库，管理端列表查询为后续独立变更，不在本范围

---

## MODIFIED Requirements

（无 —— 本变更为全新 capability，不修改既有 spec）

## REMOVED Requirements

（无）

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 发起与来电提醒 | ADDED: 通话发起与来电提醒（voice-call-invite） | 阶段一、阶段四 |
| C2 P2P 音视频与控制 | ADDED: WebRTC P2P 音视频传输与媒体控制（voice-call-media） | 阶段三、阶段四 |
| C3 信令/TURN/兜底 | ADDED: 信令中继、TURN 与失败兜底（voice-call-signaling-relay） | 阶段一、阶段三、阶段五 |
| C4 生命周期与释放 | ADDED: 通话生命周期与资源释放（voice-call-lifecycle） | 阶段一、阶段四 |
| C5 通话记录持久化 | ADDED: 通话记录持久化（voice-call-log） | 阶段二、阶段五 |
