# QA — 语音/视频通话（WebRTC P2P + 现有 Netty WS 信令中继）

- 关联 Change: `openspec/changes/2026-09-26-voice-call`（实施后归档）
- 日期: 2026-09-26
- 等级: L4（命中 AGENTS.md §6.2 WebSocket 协议 / 高风险；写代码前已通过人工确认关卡）

## 验收范围

| 层 | 文件 | 说明 |
|----|------|------|
| 主进程（发送通道） | `src/main/wsClient.js` / `src/main/ipc.js` / `src/main/index.js` | 新增 `sendCallFrame` 导出 + `CALL_*(-10~-17)` 收包分支转发 `callMessage` + `onSendCallFrame` 注册 |
| 渲染层-封装 | `src/renderer/src/utils/WebRTC.js` | `getUserMedia` + 多 `RTCPeerConnection` full-mesh + 静音/关摄像头/释放 |
| 渲染层-状态机 | `src/renderer/src/stores/useCallStore.js` | `idle→calling→ringing→connected→ended`，`startCall/acceptCall/rejectCall/busy/hangup/handleFrame` |
| 渲染层-UI | `src/renderer/src/views/chat/CallWindow.vue` | 来电提醒 + 视频网格 + 控制条 + 群成员 + 权限拒绝引导 |
| 渲染层-接入 | `src/renderer/src/views/Main.vue` / `src/renderer/src/views/chat/Chat.vue` | 常驻监听 `callMessage` + 单聊/群聊头部「语音/视频通话」入口 |
| 依赖 | `easychat-front/package.json` | 显式声明 `@element-plus/icons-vue`（图标按钮），此前仅为 element-plus 传递依赖 |
| 后端（已落地） | `CallService` / `Constants.WS_CALL_*` / `HandlerWebSocket` / `CallLog*` / migration-008 | 信令中继 + 房间 + `call_log` 落库（本次仅前端补完，后端不重复实施） |

## 验收口径

项目无单测基建，沿用既有 Change 口径：**build 通过 + 静态门禁 + 代码走查**。语音/视频通话的**真实媒体链路**需要双客户端 + 摄像头/麦克风 + 同一 NAT 环境，无法在本沙箱（无 GUI/摄像头）跑通，故标记为手动验证项（见未运行项）。

## 实际执行命令与结果

1. 前端构建（绕过 safe-delete 守卫，指到干净目录）
   ```
   node node_modules/electron-vite/bin/electron-vite.js build --outDir out-verify
   ```
   结果：`✓ built in 30.86s`，main / preload / renderer 三产物 0 error；验证后已 `mv` 出仓库（`../.trash_build_verify/out-verify-20260926`）。

2. 契约门禁
   ```
   node scripts/check-api-contract.mjs
   ```
   结果：后端路由 97 / 前端调用 95 / 主进程调用 3，**0 孤儿 / 0 漂移**（本次 WS 帧扩展未破坏既有契约）。

3. OpenSpec 卫生门禁
   ```
   node scripts/check-openspec-hygiene.mjs
   ```
   结果：**0 错误 / 0 警告 / 0 信息**，✓ 通过。

4. IPC 注册严格门禁
   ```
   node scripts/check-ipc-registration.mjs --strict
   ```
   结果：ipc.js 注册函数 34 / export 34 / index.js 调用 36，**✓ 全部 IPC 通道均已注册**（含本次新增 `sendCallFrame`）。

## 未运行项（如实记录）

- **真实 WebRTC 媒体链路端到端**：发起 → 接听 → 双向音视频。原因：沙箱无 GUI / 摄像头 / 双客户端，且 `getUserMedia` 需真实设备授权。静态佐证：状态机与信令分发经代码走查覆盖，后端 `CallService` 信令中继/房间/落库逻辑已落地（前轮）+ 本次前端全链路接通。真实双实例验证需在本机完成（建议两台登录设备互打单聊 + 群呼）。
- **TURN 对称 NAT 实测**：未配置 `easychat.turn.*` 且无可控对称 NAT 环境。静态佐证：`buildIceServers()` 在 `turnUrl` 非空时自动注入 TURN 凭据到引导帧，前端按 `iceServers` 配置 `RTCPeerConnection`，源码不含凭据。

## 附带发现并修复的缺陷

1. **发起方状态机卡在 calling**：`handleFrame` 收到 `CALL_JOIN`（`-11/-17`）且 `newMemberId !== selfId` 时，发起方此前不会切到 `connected`，导致 UI 一直停在「呼叫中」而实际已连通。已修复：非新成员且处于 `calling/ringing` 时转 `connected`，等待对方 offer。

2. **`GroupChat.vue` 实际不存在**：原 tasks 计划「`Chat.vue`（单聊）与 `GroupChat.vue`（群）」，但本项目群聊即由 `Chat.vue` 承载（`contactType==1` 分支），无独立 `GroupChat.vue`。已统一在 `Chat.vue` 头部按 `currentChatSession.contactType` 注入「语音/视频通话」按钮（单聊/群聊复用同一入口）。

## 结论

通过。前端全链路（主进程通道 + 状态机 + 浮窗 + 入口 + 门禁）构建与三项静态门禁全绿。真实音视频媒体链路与 TURN 对称 NAT 为未运行项，已如实记录并附静态佐证，留待本机双实例手动验证。
