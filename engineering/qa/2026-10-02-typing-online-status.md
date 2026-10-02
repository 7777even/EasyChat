# QA 报告 — 输入状态与在线状态实时感知（收尾验证）

- 日期: 2026-10-02
- 关联 Change: openspec/changes/2026-09-30-typing-online-status（收尾阶段）
- 说明: 本变更 2026-09-30 曾被归档，2026-10-01 复核发现「验证与收尾阶段全部缺失」而移回。本次收尾时发现**链路存在一处真实断点**，已一并修复。

## 范围

- 后端：帧类型 `TYPING_STATUS(21)`/`ONLINE_STATUS(22)`/`USER_STATUS_CHANGE(23)`、`HandlerWebSocket.handleTypingStatus`/`handleUserStatusChange`、`ChannelContextUtils` 断线置离线
- 前端主进程：`wsClient.js` 的 `sendTypingStatus`（3 秒防抖）/`sendUserStatusChange`、收到 `case 21/22` 后转发渲染层
- 前端渲染层：`MessageSend.vue` 输入上报、`Chat.vue` "正在输入..."提示、`Contact.vue` 好友在线状态、`UserInfo.vue` 状态切换

## 本次收尾发现的真实缺陷（已修）

`ipc.js` 定义并导出了 `onSendTypingStatus`、`onSendUserStatusChange`，**但 `src/main/index.js` 从未调用它们**。后果：渲染进程 `window.api.sendTypingStatus(...)` 发出的 IPC 事件无人监听，「正在输入...」与「状态变更」**完全失效且构建零报错**。

这正是 AGENTS §13「IPC 通道注册中心 + 必须调一次才生效，项目不做自动扫描」描述的静默失效场景。

修复：`index.js` 补 `onSendTypingStatus();` / `onSendUserStatusChange();`（含注释说明「必须注册否则静默失效」）。

## 验收口径

1. 仓库自带 IPC 注册门禁 `--strict` 通过（通道定义与 index.js 调用一一对应）。
2. 三层链路静态可追溯：渲染层 → preload → ipc.js → wsClient → WS 5051。
3. 前端 lint 0 error、`npm run build` 通过。
4. 后端 `mvn test` 全绿。

## 实际执行命令与用例数

| 命令 | 结果 |
|------|------|
| `node scripts/check-ipc-registration.mjs --strict`（修复前） | **exit 1**：`onSendTypingStatus` / `onSendUserStatusChange` 已导出但 index.js 未调用 |
| 同上（修复后） | **exit 0**：39 定义 / 39 导出 / 41 调用（含 `on`、`once` 来自其他模块，已忽略） |
| `npx eslint src/main/index.js` | 0 error |
| `npm run build` | built in 18.97s |
| `mvn -B -o test` | Tests run: **120**, Failures: 0 |
| `node scripts/check-openspec-hygiene.mjs` | 通过 |

链路静态核对（`git grep`）：

- 渲染层发起：`MessageSend.vue:392,403` `window.api.sendTypingStatus(...)`；`UserInfo.vue:131` `window.api.sendUserStatusChange(...)`
- preload 转发：`preload/index.js:12,19` `ipcRenderer.send('sendTypingStatus'|'sendUserStatusChange')`
- 主进程处理：`ipc.js:140,147` → `wsClient.js:65,95`
- 服务端中继：`HandlerWebSocket.java:110,113` → `handleTypingStatus`/`handleUserStatusChange`；断线置离线 `ChannelContextUtils.java:572`
- 帧类型定义：`MessageTypeEnum.java:26-28`
- 渲染层接收：`Chat.vue:640-658` 监听 `typingStatus` 显示/隐藏提示

## 未运行项

- **双客户端 GUI 端到端**：沙箱无 GUI，未能实测「A 输入 → B 窗口出现提示」。需本机双实例手动验证。
- **新增测试用例**：本变更未新增单测。`handleTypingStatus` 强依赖 Netty `Channel` 与 `ChannelContextUtils`，现有 Mockito 单测框架下构造成本高于收益；已改用 IPC 门禁 + 静态链路核对覆盖回归，风险为「未来再漏注册」——门禁已能拦截该类问题。
- **好友在线状态广播**：需两个互为好友的在线账号，未构造。

## 结论

**通过（附条件）**。链路断点已修复且被仓库门禁锁定（今后再漏注册会 exit 1）。双客户端 GUI 观察未覆盖，属沙箱限制，列入手工验证项。

遗留：帧 `TYPING_STATUS` 在服务端不做落库、不进离线缓冲（设计如此，仅在线实时），断线期间的状态变化不补偿。
