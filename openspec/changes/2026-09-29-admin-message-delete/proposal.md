# Proposal — 管理端消息删除位（举报处置真实删除 + 20 帧实时同步）

- 创建日期: 2026-09-29
- 效率等级: L4（`chat_message` 表结构变更命中 §8 数据库结构；新增 WS 帧类型命中 §8 WebSocket 协议）

## Why

content-moderation Change 已交付举报处置闭环，但 `reportType=3`（聊天消息）+ `handleAction=1`（删除内容）**只记录不删除**——spec 原文「聊天消息无删除状态位故仅记录」（content-moderation spec C3）。被举报的违规消息仍在会话中对全体成员可见、可搜索、可转发、可下载，处置动作形同虚设；运营点「删除内容」后内容毫无变化，举报闭环断裂，内容治理空转。

## What Changes

- 数据库: **L4** —— `chat_message` 新增 `delete_flag BIGINT NOT NULL DEFAULT 0`（0=存活，非0=删除时间戳ms，对齐 `sensitive_word` 先例；`status` 已被发送态占用故必须新列）；同步 `easychat.sql`；新增 `easychat-migration-009-message-delete.sql`（手动执行）
- 后端:
  - Entity `ChatMessage` 补 `deleteFlag`；`ChatMessageMapper.xml`：`query_condition` 无条件 `AND delete_flag = 0`（**用户侧查询统一过滤，不提供可绑定的查询开关防越权**）、`base_column_list`/`resultMap` 补列、`updateByMessageId` 支持置位
  - `ChatMessageService.adminDeleteMessage(messageId)`：置位 + 会话最新消息预览占位改写 + 组 20 帧推送（幂等：已删消息重复处置不改写不重推）；`recallMessage`/`downloadFile`/`locateMessage` 对已删消息拒绝 `CODE_2201`
  - `AdminReportServiceImpl`：`DELETE_CONTENT` 分支接线真实删除（替换「仅记录」note）；`selectMessage` 由 `selectList` 切换 `selectByMessageId`（PK 读不进 `query_condition`，保管理端证据可读原文）
  - `MessageTypeEnum` 新增 `20 ADMIN_DELETE`；`ChannelContextUtils`：`CONTACT_CONVERT_TYPES` + 20、发送方副本条件扩为 `14|20`
  - `AdminReportServiceImpl` 注入 `ChatMessageService`（删除逻辑归消息域，`MessageHandler` 已在 `ChatMessageServiceImpl`）
- 前端:
  - `wsClient.js`：`case 20` 专用分支（本地行墓碑化：已有则更新、缺行则补插，仿 case 14；帧带 `lastMessage` 时更新会话预览；不计未读、不闪通知、转发 `reciveMessage`）
  - `Chat.vue` 20 帧会话内处理（仿 14 帧路径）；`ChatMessage.vue` 墓碑渲染（文案「该消息已被管理员删除」，右键菜单对 20 天然隐藏）
  - `ReportList.vue`：移除「聊天消息无删除状态位…仅记录、不物理删除」警示，改为删除语义提示
  - `notification.js` 无改动（白名单 2/5/4，20 帧天然不闪不响）
- 数据库 / WS: 均命中 L4 硬门禁，实施前须过本 proposal 末尾「L4 三项决策关卡」

## Capabilities

- C1: 管理员经举报处置「删除内容」可逻辑删除聊天消息：置 `delete_flag`、用户侧查询（历史/搜索/全局搜索/初始化/SYNC 补推）统一不可见，管理端举报详情与列表摘要仍可读原文作证据
- C2: 删除实时同步：向单聊双方与群成员推送 20 帧，在线设备即时墓碑化、本地 SQLite 行同步、会话预览（被删消息为会话最新时）替换为占位文案；**接收方、发送方其他设备、群离线成员的漏帧缺口本期由离线缓冲补推闭环**（含两条防脏会话的补推转换规则，见 design ADR-003 增强）
- C3: 已删消息不可撤回 / 不可下载 / 不可定位（`CODE_2201`）；同一消息重复处置幂等

## Impact

- 对外接口: 无新 HTTP 端点；`dealReport` 行为变化（3 消息类 + `handleAction=1` 从「仅记录」变「真删除」）、`recallMessage/downloadFile/locateMessage` 对已删消息新增 2201 分支；`check-api-contract` 无新路由
- WS 协议: 新增帧类型 20 + 两条补推转换规则（`replayOfflineMessages` 规则①、`applyContactConvert` 规则②）+ 发送方副本/群离线成员入离线缓冲——均为 L4 协议核心改动，客户端与服务端同仓同发，无独立版本部署面；既有单聊离线补推不受两规则影响（不命中）
- 存量数据: `ALTER TABLE ... DEFAULT 0` 对现有行零影响（全部=存活）；MySQL 5.7 加列为 INPLACE 重建，`chat_message` 大表建议低峰执行
- 性能 / 安全: `query_condition` 增一恒定条件（低选择性，与会话条件联合过滤，不加索引）；删除入口收敛为 `checkAdmin` 的 `dealReport` 单点；用户侧无任何绑定开关可绕过过滤
- 回退方案: 停用 20 帧推送与 `adminDeleteMessage` 接线即可回「仅记录」；`delete_flag` 列保留无害（极端可 `DROP COLUMN`）

---

## ☑ L4 三项决策关卡（已于 2026-09-29 人工拍板）

1. **`delete_flag` 列语义**：`BIGINT`，0=存活，非0=删除时间戳ms（对齐 `sensitive_word` 先例）
   - [x] 同意
2. **新增 20 帧 vs 复用 14 撤回帧**：新增 `20 ADMIN_DELETE`（14 语义为用户撤回，与管理员删除正交）
   - [x] 新增 20 帧  - [ ] 复用 14 帧
3. **用户侧查询策略**：**无条件过滤 + 本期实施残余增强**（人工选定）
   - [ ] 无条件过滤（仅此）  - [ ] SQL CASE 掩码  - [x] 过滤 + 本期就做残余增强

   > 增强范围（已纳入本 Change，见 design ADR-003「本期增强」）：
   > ① 发送方副本对离线设备入离线缓冲，`replayOfflineMessages` 增「sendUserId=收件人则跳过联系人转换」规则；
   > ② 群聊删除时枚举群成员，对未收到在线帧者逐成员入离线缓冲，`applyContactConvert` 增「群帧（contactType=1）跳过转换」规则（防 `contactId=groupId` 被覆写成发送者 → 脏会话）。
   > 两项均触碰 WS 协议核心（§8 已命中 L4），冒烟须覆盖发送方离线补推与群离线成员补推。

## ☑ 人工确认关卡（总）

> 本提案经 人工（会话内当场指令） 于 2026-09-29 确认，允许进入实施阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
