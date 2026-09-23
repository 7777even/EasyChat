# Tasks — 删除已读回执功能

- 关联 Proposal / Design: 2026-09-23-remove-read-receipt
- 说明：项目无测试基建，各任务 DoD = 编译 / 解析 + 双仓 grep 残留归零 + 冒烟存证；不标记 [TDD]（无可先行失败的测试载体，诚实留空）。

## T1 后端契约层下线（-3/-5 分发、端点、服务、常量）

删除 `HandlerWebSocket` 的 -3 case 与 `handleClientAck`（含 `messageReadService` 字段注入）、`ChatController` 的 `/markRead`、`/batchGetAck` 及字段与 import、`Constants` 的 `-3`/`-5` 常量、`ChannelContextUtils.sendAckNotify`、`MessageReadService` 接口与 `MessageReadServiceImpl`、`MessageStatusEnum` 的 `DELIVERED`/`READ` 成员。**保留** `MessageStatusEnum.SENDING/SENDED`。

- [x] DoD: `mvn compile` exit 0（2026-09-23 实测 EXIT=0）
- [x] DoD: `git grep -n 'messageReadService|handleClientAck|WS_CLIENT_ACK|WS_ACK_NOTIFY|sendAckNotify|batchGetAck|markRead' -- easychat-java/src/main/java` 与 `git grep -n 'DELIVERED' -- easychat-java/src/main/java` 均为 0 命中（实测均 0 hits）
- [x] DoD: 走查 `HandlerWebSocket` switch 未新增 default、`-1` ACK 分发（`registerPendingAck` 对应的服务端发送）不受影响（仅删 -3 else-if 与 handleClientAck；`WS_ACK_MESSAGE_TYPE=-1` 常量保留）

## T2 后端数据层下线（PO / Mapper / XML / 建表脚本 / 运行库表）

删除 `MessageReadRecord` PO、`MessageReadRecordQuery`、`MessageReadRecordMapper` 接口与 XML 映射文件；`easychat.sql` 删除 `message_read_record` 建表块（L66 起）；运行库执行 `DROP TABLE IF EXISTS message_read_record`。

- [x] DoD: `mvn compile` exit 0（与 T1 同一次实测 EXIT=0）
- [x] DoD: `git grep -in 'message_read' -- easychat-java/src/main easychat.sql` 为 0 命中（实测 0 hits；6 个文件 git status 显示 D）
- [x] DoD: `mysql -e "SHOW TABLES LIKE 'message_read_record'"` 空结果（2026-09-23 实测输出为空，证据入 QA）

## T3 前端主进程下线（-3 发送、-5 消费、IPC 通道）

删除 `wsClient.js` `sendClientAck`（L46-48）与导出（L319）、`case -5` 分支（L158-167）；`ipc.js` 删除 `sendClientAck` 导入（L5 局部）、`onSendClientAck`（L127-130）与导出（L351）；`index.js` 删除 `onSendClientAck` 导入（L12）与调用（L220）。**保留** `registerPendingAck` / `onRegisterPendingAck` / `pendingMap` / `case -1`（L137-156）。

- [x] DoD: esbuild `transformSync` 对 `wsClient.js`、`ipc.js`、`index.js` 均 OK（实测 ESBUILD OK ×3）
- [x] DoD: `git grep -n 'sendClientAck|ackNotify|messageType: -3' -- easychat-front/src/main` 为 0；`git grep -n 'onRegisterPendingAck' -- easychat-front/src/main` 仍 ≥3（实测 0 hits / 4 hits）

## T4 前端渲染层下线（上报调用、监听、徽标、死 API 定义）

`Chat.vue`：删除 `sendReadAckForSession` 及调用（L203/L207-234）、收到消息即时已读上报（L339-343 及其包裹判断）、`ackNotify` 监听注册（L439-452）与 `removeAllListeners('ackNotify')`（L578）、`ackType` 全部维护点。`ChatMessage.vue`：删除 L34-40 徽标块与 L208 起 `.read-status-tip` 样式。`Api.js`：删除 `markRead`（L67）、`batchGetAck`（L68）。

- [x] DoD: `@vue/compiler-sfc` `parse` + `compileTemplate` 对 `Chat.vue`、`ChatMessage.vue` 0 errors（实测 SFC OK ×2，含 compileScript）
- [x] DoD: `git grep -n 'ackType|ackNotify|sendClientAck|markRead|batchGetAck|已送达' -- easychat-front/src/renderer` 为 0（实测另加 `readBy` 共 0 hits；Api.js esbuild OK）
- [x] DoD: 走查会话列表未读红点（noReadCount）与 -1 发送状态（发送中/已发送）显示路径未被误删（走查：Chat.vue 未读维护点与 ChatMessage.vue `status==0` 骨架屏分支未触碰）

## T5 spec 回填与迁移（归档任务执行）

从 `openspec/specs/multi-device-sync/spec.md` 移除 Requirement「跨端已读状态广播」（L26-34）；将其下 Scenario「未读数多端一致」（L36-39，基于 -6）迁移至 Requirement「跨端会话状态同步」（L43）之下；按 `spec-delta.md` 合入。

- [x] DoD: `git grep -n '已读\|markRead\|ACK_NOTIFY' -- openspec/specs` 为 0；「未读数多端一致」Scenario 仍存在于 specs（2026-09-23 实测：0 hits OK；Scenario 在 L36 存在；`message_read` 在 specs 亦 0）
- [x] DoD: `node scripts/check-openspec-hygiene.mjs --strict` 0/0/0（2026-09-23 实测 0 错误 / 0 警告 / 0 信息，exit=0）

## T6 双端冒烟（-3/-5 消失 + 回归不变式）

双端登录互发消息、打开会话、发送图片、撤回一条消息，验证：①服务端日志与前端主进程无 `-3`/`-5`/`ackNotify` 踪迹；②消息收发、未读红点、发送状态、撤回（含发送方多端撤回）全部回归正常；③`/api/chat/markRead`、`/api/chat/batchGetAck` 返回 404（或路由不存在）。证据（curl / 终端输出快照）存 `engineering/qa/` 同目录。

- [x] DoD: 三项验证全部通过，证据文件落盘（2026-09-23：①FE/BE 踪迹累计 0 命中 ②收发/图片/撤回回归通过 ③双端点 HTTP 404+对照 200；证据 `engineering/qa/2026-09-23-remove-read-receipt-evidence.md` E2–E4）
- [x] DoD: 冒烟数据回滚至基线（消息数 / 会话预览 / 双端本地库核对）（2026-09-23 实测：MySQL 会话 12 行、预览字面 '2'/1790149769028、redis seq=23 未漂；本地 U2995 会话 11 行 / U042 会话 8 行、锚点与 no_read=0 吻合、冒烟行 residue=[]；上传残留已删；证据 E5）

## T7 QA / Retro / 归档闭环

完成后即刻写 `engineering/qa/2026-09-23-remove-read-receipt.md` 与 `engineering/retro/`（§7.2）；tasks 全勾后：spec-delta 合入 specs → `git mv openspec/changes/2026-09-23-remove-read-receipt openspec/archive/2026-09-23-remove-read-receipt`。

- [x] DoD: QA 含范围 / 验收口径 / 实际命令与用例数 / 未运行项 / 结论，证据附件齐（2026-09-23 已写 `engineering/qa/2026-09-23-remove-read-receipt.md` + 同目录证据附件；UI 截图为明确未运行项）
- [x] DoD: Retro 四段式（2026-09-23 已写 `engineering/retro/2026-09-23-remove-read-receipt.md`，做好/问题/原因/改进四段齐）
- [ ] DoD: 归档完成且 hygiene --strict 通过
