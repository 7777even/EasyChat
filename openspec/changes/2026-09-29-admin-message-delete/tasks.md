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

- [ ] **[TDD]** 先写失败断言：migration-009 执行后 `chat_message.delete_flag` 存在且默认 0；`SELECT ... WHERE delete_flag=0` 返回全量存量 — ≤30min
- [ ] `easychat-migration-009-message-delete.sql`（ALTER + 低峰执行注释）+ 同步 `easychat.sql` 基线 — ≤30min
- [ ] **[TDD]** `ChatMessage` PO 补 `deleteFlag`；`ChatMessageMapper.xml`：`base_column_list`/`resultMap` 补列、`query_condition` 无条件 `AND delete_flag = 0`、`updateByMessageId` 加置位 `<if>` — ≤1h

## 阶段二：后端删除链路

- [ ] **[TDD]** `MessageTypeEnum` 新增 `ADMIN_DELETE(20)`；`ChannelContextUtils`：`CONTACT_CONVERT_TYPES` + 20、发送方副本条件扩为 `14|20`（注释同步中性化） — ≤1h
- [ ] **[TDD]** 补推转换增强（ADR-003 本期增强）：`sendRecallToSenderDevices` 对 20 帧发送方离线时入其离线缓冲（14 维持「跳过+DB 兜底」现状）；`replayOfflineMessages` 规则①（`sendUserId`=收件人跳过 `applyContactConvert`）；`applyContactConvert` 规则②（`contactType=1` 群帧跳过转换） — ≤1.5h
- [ ] **[TDD]** `ChatMessageService.adminDeleteMessage(messageId, admin)`：幂等守卫、置位、最新消息预览占位改写（`chat_session`+`chat_session_user`）、事务后推 20 帧（sendUserId=原发送者、lastMessage 条件携带）；**群聊分支：枚举群成员，对未收到在线帧者逐成员 `pushOfflineMessage`**；`recallMessage`/`downloadFile`/`locateMessage` 已删守卫 `CODE_2201` — ≤2h
- [ ] `AdminReportServiceImpl`：注入 `ChatMessageService`、DELETE_CONTENT 分支接线（替换「仅记录」note）、`selectMessage` 切 `selectByMessageId` — ≤1h
- [ ] 后端 `mvn compile` 通过 — ≤30min

## 阶段三：前端同步链路

- [ ] **[TDD]** `wsClient.js` `case 20` 专用分支：本地行墓碑化（有则更新/缺则补插）、帧带 `lastMessage` 才更新会话预览、不计未读不闪通知、转发 `reciveMessage` + extendData — ≤1.5h
- [ ] `Chat.vue` 20 帧会话内处理（仿 14 帧 ~431 行路径）；`ChatMessage.vue` 墓碑渲染分支与文案「该消息已被管理员删除」（仿撤回渲染，右键菜单天然隐藏） — ≤1.5h
- [ ] `ReportList.vue` 移除「仅记录、不物理删除」警示 → 删除语义提示（「将删除该消息，会话双方实时收到删除通知」） — ≤30min
- [ ] `npm run build` 通过 — ≤30min

## 阶段四：验证

- [ ] 手动执行 migration-009，活体确认列与默认值 — ≤10min
- [ ] 后端启动 + 活体冒烟（curl + SQL 断言）：举报消息→处置删除 → `delete_flag` 置位；用户侧 `loadHistoryMessage`/`searchMessage`/`globalSearch` 均不可见；举报详情与列表摘要仍返回原文；`recallMessage/downloadFile/locateMessage` 返回 2201；重复处置幂等不报错；**Redis 队列断言：发送方离线时其离线队列出现 20 帧 JSON 且 `contactId` 为会话对方（规则①生效）、群离线成员队列出现 20 帧且 `contactId` 为 groupId（规则②生效）、既有单聊普通消息离线队列补推 JSON 不受两规则影响**；`dealReport` 其余分支（0仅记录/2封禁/朋友圈类）无回归 — ≤1.5h
- [ ] **双实例 WS 冒烟（需用户本机 GUI，沙箱不可执行，列入移交清单）**：单聊接收方在线收 20 帧墓碑+预览占位；接收方离线重连经离线缓冲补推墓碑；**发送方其他设备离线重连补推墓碑且会话联系人不为「自己」；群聊离线成员重连补推墓碑且会话仍为群聊（不出脏会话）**；群聊在线成员墓碑；右键菜单隐藏；通知不闪 — ≤1h
- [ ] `check-api-contract` + `check-openspec-hygiene` 通过 — ≤30min

## 阶段五：收尾

- [ ] QA/Retro 落 `engineering/`（L4 必写，附 curl/SQL 冒烟存证；双实例项标注移交用户执行） — ≤30min
- [ ] spec-delta 回写 `openspec/specs/content-moderation/spec.md` + `git mv` 归档 Change + 按域分笔提交（`type(scope): 单行`） — ≤30min
- [ ] 同步 `docs/system-facts.md`（消息删除位、20 帧、migration-009） — ≤20min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-09-29-admin-message-delete/tasks.md` 全部勾选，L4 三项决策已拍板留痕
- [ ] 按 AGENTS.md §2 矩阵「L3/L4」行执行：`mvn compile` 0 error + 接口链路冒烟通过（§7.4 回归全绿）
- [ ] 文档同步：`easychat.sql` + migration-009 + `docs/system-facts.md` + 前端调用方（无新端点，`Api.js` 无变更）
- [ ] 归档闭环完成（spec-delta 回写 `specs/content-moderation/` + git mv 到 `archive/`）
- [ ] QA / Retro 记录已落 `engineering/`
