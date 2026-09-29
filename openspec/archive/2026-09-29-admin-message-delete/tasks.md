# Tasks — 管理端消息删除位

- 关联 Design: 2026-09-29-admin-message-delete/design.md
- 创建日期: 2026-09-29
- 预估总工时: 13.5h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。本变更无既有后端测试目录，后端 TDD 以「先写失败断言的活体冒烟用例（curl + SQL 断言，不入库）」代替单测；前端以先写失败的渲染断言为准（仿 `2026-09-26-sensitive-word-admin` 先例）。

## 前置（L4 关卡，已完成）

- [x] **L4 三项决策关卡**（2026-09-29 人工拍板）：①`delete_flag BIGINT 0/时间戳` ✅ ②新增 20 帧 ✅ ③**过滤 + 本期做残余增强** ✅ — ≤20min
- [x] 四件套（proposal/design/tasks/spec-delta/.openspec.yaml）完成并过总关卡 — ≤1.5h

## 阶段一：数据库与实体

- [x] **[TDD]** 先写失败断言：migration-009 执行后 `chat_message.delete_flag` 存在且默认 0；`SELECT ... WHERE delete_flag=0` 返回全量存量 — ≤30min
  - 红灯：`scripts/smoke/smoke_admin_msg_delete.py` P0 2/2 FAIL（列不存在）→ 执行 migration-009 后 4/4 PASS（total=60 alive=60）
- [x] `easychat-migration-009-message-delete.sql`（ALTER + 低峰执行注释）+ 同步 `easychat.sql` 基线 — ≤30min
- [x] **[TDD]** `ChatMessage` PO 补 `deleteFlag`；`ChatMessageMapper.xml`：`base_column_list`/`resultMap` 补列、`query_condition` 无条件 `AND delete_flag = 0`、`updateByMessageId` 加置位 `<if>` — ≤1h

## 阶段二：后端删除链路

- [x] **[TDD]** `MessageTypeEnum` 新增 `ADMIN_DELETE(20)`；`ChannelContextUtils`：`CONTACT_CONVERT_TYPES` + 20、发送方副本条件扩为 `14|20`（注释同步中性化） — ≤1h
- [x] **[TDD]** 补推转换增强（ADR-003 本期增强）：`sendRecallToSenderDevices` 对 20 帧发送方离线时入其离线缓冲（14 维持「跳过+DB 兜底」现状）；`replayOfflineMessages` 规则①（`sendUserId`=收件人跳过 `applyContactConvert`）；`applyContactConvert` 规则②（`contactType=1` 群帧跳过转换） — ≤1.5h
  - 另新增 `ChannelContextUtils.isUserOnline(userId)` 公共方法供离线补推分流
- [x] **[TDD]** `ChatMessageService.adminDeleteMessage(messageId, admin)`：幂等守卫、置位、最新消息预览占位改写（`chat_session`+`chat_session_user`）、事务后推 20 帧（sendUserId=原发送者、lastMessage 条件携带）；**群聊分支：枚举群成员，对未收到在线帧者逐成员 `pushOfflineMessage`**；`recallMessage`/`downloadFile`/`locateMessage` 已删守卫 `CODE_2201` — ≤2h
  - 实现注记：`@Transactional` + `TransactionSynchronization.afterCommit` 实现「事务后推」；**事实修正**——服务端 `chat_session_user` 无 `last_message` 列（PO 字段为 JOIN `chat_session` 别名），预览占位仅落 `chat_session` 单源，客户端本地 `chat_session_user.last_message` 由 20 帧 `lastMessage` 更新（与 saveMessage 预览写入路径一致）
- [x] `AdminReportServiceImpl`：注入 `ChatMessageService`、DELETE_CONTENT 分支接线（替换「仅记录」note）、`selectMessage` 切 `selectByMessageId` — ≤1h
  - 删除先于处置记录落库，`handleNote` 同时进报告行与审计日志；`selectByMessageId` 保留已删消息证据链（举报详情 + BAN_PUBLISHER 分支）
- [x] 后端 `mvn compile` 通过 — ≤30min

## 阶段三：前端同步链路

- [x] **[TDD]** `wsClient.js` `case 20` 专用分支：本地行墓碑化（有则更新/缺则补插）、帧带 `lastMessage` 才更新会话预览、不计未读不闪通知、转发 `reciveMessage` + extendData — ≤1.5h
  - 实现注记：新增 `ChatSessionUserModel.updateSessionPreviewOnly(contactId, lastMessage)`（Model 层仅 SQL）——复用 `saveOrUpdate4Message` 会无条件刷 `last_receive_time` 并计未读，违反 ADR-004，故单独建函数
- [x] `Chat.vue` 20 帧会话内处理（仿 14 帧 ~431 行路径）；`ChatMessage.vue` 墓碑渲染分支与文案「该消息已被管理员删除」（仿撤回渲染，右键菜单天然隐藏） — ≤1.5h
  - `Chat.vue` 20 分支：墓碑化 + 仅帧带 `lastMessage` 时同步 `chatSessionList` 预览（不排序不计未读）+ `updateLocalMessage` 幂等对齐
  - `ChatMessage.vue`：两视角 `recalled-message` 类/渲染分支扩 20、昵称行跳过 20、文案三元；右键菜单经 `!isNormalMessage → return` 对 20 天然隐藏，无改动
- [x] `ReportList.vue` 移除「仅记录、不物理删除」警示 → 删除语义提示（「将删除该消息，会话双方实时收到删除通知」） — ≤30min
  - 实际文案：「将逻辑删除该消息（会话双方与群成员实时收到删除通知，会话内显示墓碑），举报详情保留原文供审计」
- [x] `npm run build` 通过 — ≤30min

## 阶段四：验证

- [x] 手动执行 migration-009，活体确认列与默认值 — ≤10min
  - 阶段一已执行；冒烟 P0 断言 `bigint#NO#0` + 存量 `delete_flag=0` 全量存活
- [x] 后端启动 + 活体冒烟（curl + SQL 断言）：举报消息→处置删除 → `delete_flag` 置位；用户侧 `loadHistoryMessage`/`searchMessage`/`globalSearch` 均不可见；举报详情与列表摘要仍返回原文；`recallMessage/downloadFile/locateMessage` 返回 2201；重复处置幂等不报错；**Redis 队列断言：发送方离线时其离线队列出现 20 帧 JSON 且 `contactId` 为会话对方（规则①生效）、群离线成员队列出现 20 帧且 `contactId` 为 groupId（规则②生效）、既有单聊普通消息离线队列补推 JSON 不受两规则影响**；`dealReport` 其余分支（0仅记录/2封禁/朋友圈类）无回归 — ≤1.5h
  - 结果：`scripts/smoke/smoke_admin_msg_delete.py` **43/43 PASS**，存证 `engineering/qa/2026-09-29-admin-msg-delete-smoke.txt`；finally 自动回滚全部 fixture（delete_flag/会话预览/举报与审计行/封禁与朋友圈状态/离线队列）
- [x] **双实例 WS 冒烟（需用户本机 GUI，沙箱不可执行，列入移交清单）**：单聊接收方在线收 20 帧墓碑+预览占位；接收方离线重连经离线缓冲补推墓碑；**发送方其他设备离线重连补推墓碑且会话联系人不为「自己」；群聊离线成员重连补推墓碑且会话仍为群聊（不出脏会话）**；群聊在线成员墓碑；右键菜单隐藏；通知不闪 — ≤1h
  - 移交执行状态：服务端队列侧已由阶段四冒烟 P1/P3 断言覆盖（规则①②帧 JSON 语义）；客户端渲染侧清单已随 QA「未运行项」（`engineering/qa/2026-09-29-admin-message-delete-qa.md`）移交用户本机执行，结果由用户回填，不阻断归档
- [x] `check-api-contract` + `check-openspec-hygiene` 通过 — ≤30min
  - 结果：contract 0 孤儿/0 漂移（98 后端路由 vs 96+3 调用）；hygiene 0 错误 0 警告

## 阶段五：收尾

- [x] QA/Retro 落 `engineering/`（L4 必写，附 curl/SQL 冒烟存证；双实例项标注移交用户执行） — ≤30min
  - `engineering/qa/2026-09-29-admin-message-delete-qa.md` + 冒烟终端存证 `2026-09-29-admin-msg-delete-smoke.txt`（同目录）；`engineering/retro/2026-09-29-admin-message-delete-retro.md` 四段式
- [x] spec-delta 回写 `openspec/specs/content-moderation/spec.md` + `git mv` 归档 Change + 按域分笔提交（`type(scope): 单行`） — ≤30min
  - 回写：3 个 ADDED Requirement 合入 + 举报处理 dealReport 语义 MODIFIED；`git mv` 至 `openspec/archive/2026-09-29-admin-message-delete`；分笔提交见 git log
- [x] 同步 `docs/system-facts.md`（消息删除位、20 帧、migration-009） — ≤20min
  - §5 WS 表增「管理员删除帧」行、§12 增「消息删除位」行、举报处理行改删除语义、变更日志追加 L4 条目

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-29-admin-message-delete/tasks.md` 全部勾选，L4 三项决策已拍板留痕
- [x] 按 AGENTS.md §2 矩阵「L3/L4」行执行：`mvn compile` 0 error + 接口链路冒烟通过（§7.4 回归全绿）
  - 补充：`npm run build` 0 error；冒烟 43/43；`check-api-contract`/`check-openspec-hygiene` 双绿
- [x] 文档同步：`easychat.sql` + migration-009 + `docs/system-facts.md` + 前端调用方（无新端点，`Api.js` 无变更）
- [x] 归档闭环完成（spec-delta 回写 `specs/content-moderation/` + git mv 到 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`
