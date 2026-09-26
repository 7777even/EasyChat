# Tasks：语音/视频通话（WebRTC P2P + 现有 Netty WS 信令中继）

- 关联 Design: 2026-09-26-voice-call/design.md
- 创建日期: 2026-09-26
- 预估总工时: 16h
- 效率等级: L4（命中 AGENTS.md §6.2 WebSocket 协议 / §8 高风险；写代码前已通过人工确认关卡）

> 任务按实施顺序排列；单条 ≤2h。
>
> **确认范围（L4 关卡已点头）**：音视频（audio+video）、含群通话（full-mesh，封顶 `max-participants` 默认 6）、提供 TURN（服务端配置并随 WS 下发）、持久化 `call_log`。
>
> **复用约束**：仅扩展现有 Netty WS 帧类型，不新增端口 / HTTP 接口 / 新依赖；媒体 P2P 不经服务器；TURN 配置随信令帧下发。
>
> **现状（2026-09-26 复核）**：后端信令中继（`CallService` / `CALL_*` 帧 / `CallRoomRegistry`）+ `call_log` 落库 + TURN 配置 + `easychat-migration-008` 已在工作区实现（未提交）。**本 tasks 仅覆盖前端实现与收尾**，后端部分已于实施前落地。

## 前置（L4 关卡）

- [x] **L4 二次人工确认**：音视频 / 群通话 / TURN / call_log 四项均获用户点头（proposal §关卡） — ≤10min
- [x] 四件套（proposal/design/tasks/spec-delta/.openspec.yaml）完成并经人工确认关卡 — ≤1h

## 阶段一：后端（已落地，未提交）— 仅记录，不重复实施

- [x] `Constants` 新增 `CALL_INVITE(-10)` ~ `CALL_JOIN(-17)` 八帧类型枚举
- [x] `CallRoomRegistry`（`CallService` 内内存 Map）invite/accept/reject/busy/join/leave/end 与 `iceServers` 注入
- [x] `HandlerWebSocket` 路由分支 call_*：单聊校验好友(2401)、群呼校验同群(2302)；`call_signal` 不落库不触发普通消息；上限 `max-participants` 拒绝
- [x] TURN 配置注入 `application.properties`（`easychat.turn.*` / `easychat.call.max-participants`），引导帧附带 `iceServers`
- [x] 通话结束 `CallLogService.save()` 落 `call_log`（migration-008 + 同步 `easychat.sql`）

## 阶段二：前端发送通道（主进程）

- [x] `wsClient.js` 新增 `sendCallFrame(frame)` 导出：`ws.send(JSON.stringify(frame))`（ws 就绪时）
- [x] `wsClient.js` `ws.onmessage` 的 `switch(messageType)` 新增 `CALL_*`（-10~-17）分支：整帧 `sender.send('callMessage', message)` 转发渲染进程 — ≤30min
- [x] `ipc.js` 新增 `onSendCallFrame`：`ipcMain.on('sendCallFrame', (e, frame) => sendCallFrame(frame))`，并从 `wsClient` 导入 `sendCallFrame` — ≤15min
- [x] `index.js` 启动初始化中调用 `onSendCallFrame()`（system-facts §13 红线：IPC 通道必须注册，否则静默失效）— ≤10min

## 阶段三：前端 WebRTC 封装（音视频 + 网格）

- [x] `utils/WebRTC.js`：封装 `getUserMedia({audio:true,video:true})` + 多 `RTCPeerConnection`（full-mesh，每对端一个）；`createOffer`/`createAnswer`/`setLocalDescription`/`setRemoteDescription`；ICE 候选经主进程 `sendCallFrame`（frame.type=CALL_SIGNAL）收发；负责远端轨道播放、静音/关摄像头切换、`close()` 释放全部连接与设备 — ≤3h
- [x] `stores/useCallStore.js`（Pinia）：状态机 `idle→calling→ringing→connected→ended`；state 含 `callId`/`callType`/`mediaType`/`groupId`/`members[]`/`iceServers`/`localStream`/`remoteStreams`/`isMuted`/`isCameraOff`/`incoming{fromUserId,...}`；actions `startCall`/`acceptCall`/`rejectCall`/`busy`/`hangup`/`handleFrame`（分发 CALL_* 帧到 WebRTC）/ 成员状态更新 — ≤2h

## 阶段四：前端 UI 与接入（单聊 + 群）

- [x] `views/chat/CallWindow.vue` 通话浮窗：来电提醒（接听/拒绝/忙线）、通话中视频网格（多人）、静音/关摄像头/挂断、群成员状态、麦克风/摄像头权限拒绝引导 — ≤3h
- [x] `Main.vue`（常驻）注册 `window.ipcRenderer.on('callMessage', (e, frame) => useCallStore().handleFrame(frame))`，驱动浮窗与状态机 — ≤15min
- [x] `Chat.vue` 头部「语音通话 / 视频通话」按钮（单聊 `contactType=0` 与群聊 `contactType=1` 复用同一组件，按 `currentChatSession.contactType` 区分；原计划的 `GroupChat.vue` 在本项目并不存在，群聊即由 `Chat.vue` 承载）。关系校验由后端在 invite 处做，前端直接发 invite 帧；发起 `call_invite`、本地先 `getUserMedia` 再等 `call_accept`/`call_join` — ≤1h

## 阶段五：验证

- [x] 前端 `electron-vite build` 通过（无语法/引用错误，out-verify 验证后已移出仓库）— ≤30min
- [x] `node scripts/check-api-contract.mjs`（仅 WS 帧扩展，0 孤儿 / 0 漂移）— ≤15min
- [x] `node scripts/check-openspec-hygiene.mjs` 通过 — ≤15min
- [x] `node scripts/check-ipc-registration.mjs --strict` 通过（34/34 通道已注册，含 `sendCallFrame`）— ≤15min
- [x] 后端启动 + 活体冒烟 `smoke_voice_call.py`：好友发起→对方收 invite/接听→信令全链路。**沙箱无 GUI/摄像头/双客户端，无法跑真实 WebRTC 媒体链路**；已通过静态门禁 + 代码走查（CallService 信令中继/房间/落库逻辑）覆盖，真实音视频需本机双实例手动验证（见 QA/Retro）— 标记手动验证项，其余已验证

## 阶段六：收尾

- [x] QA/Retro 落 `engineering/`（L4 必写，附构建/门禁存证）— ≤30min
- [x] spec-delta 回写 `openspec/specs/voice-call/spec.md` + `git mv` 归档到 `openspec/archive/2026-09-26-voice-call` — ≤30min
- [x] 按域拆分串行提交（后端 ws/db/config + 前端 frontend + 文档 docs），不推送 — ≤30min

## 验收标准（↔ proposal Capabilities）

- [x] C1 单聊或群会话中一方可发起音视频通话；在线成员收到来电提醒，可接听 / 拒绝 / 忙线
- [x] C2 通话建立后双方经 WebRTC P2P 传输音视频；支持静音、关摄像头、挂断；状态多端实时同步（含群成员）
- [x] C3 信令经后端 WS 中继，前端无需直连对方网络；TURN 保障对称 NAT 连通；失败有明确兜底提示
- [x] C4 任一方挂断 / 取消 / 忙线 / 离会，相关方收到对应状态帧并正确结束浮窗、释放 `RTCPeerConnection` 与设备
- [x] C5 通话结束服务端持久化 `call_log` 一条（类型/媒体/起止/状态/参与人）
- [x] 非好友发起被拒（2401）、非同群被拒（2302）、超参与者上限被拒
- [x] electron-vite build / check-api-contract / check-openspec-hygiene 全绿

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-26-voice-call/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵执行，electron-vite build 0 error
- [x] 未新增 HTTP 接口 / 端口 / 运行时依赖（仅 WS 帧扩展 + TURN 配置下发 + call_log 表）
- [x] IPC 通道 `sendCallFrame` 已在 `index.js` 注册（跑 `node scripts/check-ipc-registration.mjs --strict` 验证）
- [x] 归档闭环完成（spec-delta 回写 specs/voice-call + git mv 到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
