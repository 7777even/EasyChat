# QA — 管理端消息删除位（delete_flag + 20 帧实时同步）

- 日期: 2026-09-29
- 效率等级: L4
- 范围: `easychat-migration-009-message-delete.sql`（chat_message 列）、`easychat.sql` 基线；后端 `MessageTypeEnum`/`ChannelContextUtils`/`ChatMessageService(+Impl)`/`AdminReportServiceImpl`/`ChatMessageMapper.xml`/`ChatMessage` PO；前端 `wsClient.js`/`Chat.vue`/`ChatMessage.vue`/`ReportList.vue`/`ChatSessionUserModel.js`；冒烟脚本 `scripts/smoke/smoke_admin_msg_delete.py`。关键路径：举报处置删除链、用户侧三查询过滤、20 帧在线/离线推送（规则①②）、三路 2201 守卫、幂等。

## 验收口径

- migration-009：`chat_message.delete_flag` 存在且 `bigint NOT NULL DEFAULT 0`，存量全量存活（`WHERE delete_flag=0` 返回全量）。
- 处置删除：`dealReport(reportType=3, handleAction=1)` → `delete_flag` 置位（处置时刻 ms），`handle_note` 双写报告行与审计日志；同消息重复处置幂等（不改写、不重推、code=0）。
- 用户侧不可见：`loadHistoryMessage` / `searchMessage` / `globalSearch` 均不过滤返回被删消息（SQL 字面 `AND delete_flag=0`，无 HTTP 开关）。
- 管理端证据保留：`getReportDetail`（PK 读）与 `chat_message` PK 行仍返回原文。
- 2201 守卫：已删消息的 `locateMessage` / `recallMessage` / `downloadFile` 返回 HTTP 400 + body `code=2201`。
- Redis 离线队列（规则①②）：发送方离线时其队列出现 20 帧且 `contactId`=会话对方；群离线成员（含发送者成员）队列 20 帧且 `contactId`=groupId；既有单聊普通消息离线补推 JSON（`contactId`=发送者）不受两规则影响。
- 服务端预览占位：被删消息为会话最新时 `chat_session.last_message`=「该消息已被管理员删除」，非最新不动。
- `dealReport` 其余分支回归：0 仅记录（delete_flag 不动）、2 封禁发布者、朋友圈类处置均 code=0。
- 契约与卫生门禁：`check-api-contract` 0 孤儿 0 漂移；`check-openspec-hygiene` 0 错误。
- 构建：后端 `mvn compile` 0 error；前端 `npm run build` 0 error。

## 实际执行命令与结果

- `mvn compile` → 0 error（阶段二收尾）。
- `npm run build`（easychat-front）→ BUILD_EXIT=0。
- `python scripts/smoke/smoke_admin_msg_delete.py engineering/qa/2026-09-29-admin-msg-delete-smoke.txt` → **43/43 PASS**（P0 迁移 4、P1 单聊链 17、P2 幂等 4、P3 群聊 7、P4 分支回归 6、后端可达 1、登录 0 计入流程），`SMOKE PASS`，退出码 0。
- `node scripts/check-api-contract.mjs` → 0 潜在孤儿 / 0 潜在漂移（98 后端路由 vs 96 前端 + 3 主进程调用），退出码 0。
- `node scripts/check-openspec-hygiene.mjs` → 0 错误 / 0 警告，退出码 0。
- 冒烟 finally 自动回滚全部 fixture：`delete_flag` 还原、双会话 `last_message` 还原、`message_report`/`report_audit_log`/`moment_report` fixture 行删除、朋友圈 `status` 与 karina `status` 还原、4 个离线队列清空（脚本输出第 51–60 行逐项留痕）。

## 未运行项

- **双实例 WS 冒烟（移交用户本机 GUI 执行）**：单聊接收方在线收 20 帧墓碑+预览占位、离线重连补推、发送方其他设备补推且会话联系人非「自己」、群离线成员重连补推不出脏会话、群在线成员墓碑、右键菜单隐藏、通知不闪。服务端队列侧语义已由冒烟 P1/P3 断言，剩余为客户端渲染侧。
- **页面截图（前端 AGENTS §4 要求，随双实例项一并补附）**：`ChatMessage.vue` 墓碑渲染、`ReportList.vue` 处置警示新文案——沙箱无 GUI，无法截图；截图待用户本机验证时补入本目录。
- 双实例通话/媒体验证、虚拟滚动/桌面通知补测（①②遗留项，与本变更无关，同样移交）。

## 证据附件

- `2026-09-29-admin-msg-delete-smoke.txt`：冒烟全量终端输出（43 项逐条断言 + 帧 JSON 片段 + cleanup 回滚留痕）。
- 关键帧语义摘录（见附件 L18/L41/L42）：发送方 20 帧 `contactId=U29953535216`（会话对方，规则①）、群成员 20 帧 `contactId=G08427252986`（规则②）、普通消息 20 帧前基线 `contactId=U04259455805`（转换不受影响）。

## 结论

- **达成**：验收口径全部通过（冒烟 43/43 + 双门禁绿 + 双端构建 0 error）。遗留：双实例 WS 渲染侧验证与 UI 截图移交用户本机执行（清单见归档交付说明）。
- 既有缺陷（不在本变更范围，不修复）：`ChatMessageServiceImpl.downloadFile` 中 `UserContactTypeEnum.GROUP.getType().equals(contactTypeEnum)` 为 Integer 比 enum 恒 false，群文件成员/归属校验实际未生效——建议另立变更修复。
