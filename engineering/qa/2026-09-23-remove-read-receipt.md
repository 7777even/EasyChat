# QA — 删除已读回执功能（-3/-5 帧、markRead/batchGetAck 端点、已读/送达徽标、message_read_record 表）

- 日期: 2026-09-23
- 效率等级: L4
- 范围: `easychat-java`（HandlerWebSocket / ChatController / MessageReadService(+Impl，已删) / MessageReadRecord PO·Query·Mapper·XML(已删) / Constants(-3,-5) / ChannelContextUtils.sendAckNotify / MessageStatusEnum(DELIVERED,READ)）、`easychat.sql`（message_read_record 建表块）、运行库表（DROP）、`easychat-front`（wsClient.js / ipc.js / index.js / Chat.vue / ChatMessage.vue / Api.js）、`openspec/specs/multi-device-sync/spec.md`（T5 回填）。不影响：未读红点 noReadCount、-1 可靠性 ACK、chat_message.status、-2/-4/-6 帧。

## 验收口径

- T1/T2 后端：`mvn compile` exit 0；`messageReadService|handleClientAck|WS_CLIENT_ACK|WS_ACK_NOTIFY|sendAckNotify|batchGetAck|markRead|DELIVERED` 与 `message_read` 双仓 grep 0 命中；运行库 `SHOW TABLES LIKE 'message_read_record'` 空；switch 未新增 default、-1 ACK 链路不受影响。
- T3 前端主进程：esbuild 解析 OK；`sendClientAck|ackNotify|messageType: -3` grep 0；`onRegisterPendingAck` ≥3（保留项未误删）。
- T4 渲染层：`@vue/compiler-sfc` 解析 0 errors；`ackType|ackNotify|sendClientAck|markRead|batchGetAck|已送达` grep 0；未读红点与发送状态显示路径走查未触碰。
- T5 spec：`已读|markRead|ACK_NOTIFY` 在 `openspec/specs` grep 0；「未读数多端一致」Scenario 仍存在（迁移后）；`check-openspec-hygiene --strict` 0/0/0。
- T6 冒烟：①服务端/前端主进程无 -3/-5/ackNotify 踪迹；②消息收发、撤回（发送方多端撤回归）、图片消息链路回归正常；③`/api/chat/markRead`、`/api/chat/batchGetAck` 返回 404；冒烟数据回滚至基线。

## 实际执行命令与结果

- `mvn compile -q` → EXIT=0（通过 1/1）。
- 双仓残留 grep（后端 2 组、前端主进程 2 组、渲染层 1 组）→ 共 0 命中（通过 5/5）；保留项 `onRegisterPendingAck`=4。
- esbuild `transformSync` ×4 → OK 4/4；`@vue/compiler-sfc` parse+compileScript+compileTemplate ×2 → OK 2/2，0 errors。
- 运行库 `DROP TABLE` + `SHOW TABLES` → 空；归档前 `information_schema` 复查 residue=0。
- curl 死端点：markRead → HTTP 404（code=1003 资源不存在）、batchGetAck → HTTP 404；对照组 checkCode → 200（通过 3/3，证据 E2）。
- 双端冒烟（新后端 + 新代码 A' + 旧代码 B 自动重连）：发送 1831 → A' 收帧+落库；撤回 1831 → A' 收 type14 并更新（多端撤回副本）；上传+图片消息 1832 → 收帧+落库+状态帧(6) status=1 更新；-3/-5 踪迹全程累计扫描 0 命中（证据 E3/E4）。
- 数据回滚：MySQL 会话 12 行、预览字面 '2'/1790149769028；本地 U2995 会话行 11 / U042 会话行 8（总行数差额均为 Urobot 历史会话行，非本变更残留）；冒烟行 residue=[]；redis seq=23 未漂；上传残留文件已删（证据 E5）。回滚核对 6/6 通过。

## 未运行项

- **UI 页面截图与页面级目验**（消息旁徽标消失、会话列表未读红点视觉、发送状态气泡、图片渲染）：前置条件为登录态渲染窗口；当前双实例渲染层均处登录页（A' 冒烟后进程已退出、B 仅主进程在线，不代登录、不触碰其进程）。未执行，**不计入通过**；待人工登录任一实例后补验，或由人工自行目验确认。
- `mvn package -DskipTests` 未跑：§2 矩阵本变更命中「局部修改 → mvn compile + 启动验证」行（pom 未动），已执行启动验证（后端 5050/5051 正常服务）。

## 证据附件

- `2026-09-23-remove-read-receipt-evidence.md`（同目录）：E1 静态验证快照、E2 404 curl、E3 收发/撤回/图片冒烟终端摘录、E4 踪迹归零扫描、E5 回滚对账。
- 页面截图：**缺失**（见未运行项第 1 条，不以其他证据冒充）。

## 结论

- 功能验收达成：T1–T6 验收口径逐条满足（静态 10/10、冒烟 3/3、回滚 6/6），-3/-5 回执体系与 message_read_record 全量下线且零残留、旧客户端 B 兼容在线无异常；遗留 1 项未运行（UI 截图目验，依赖登录态，补齐前不声称 UI 项通过）。
