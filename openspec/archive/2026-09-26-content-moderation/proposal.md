# 提案：内容治理（举报 + 敏感词过滤）

## Why

EasyChat 需要基础的内容治理能力，否则违规内容无法被用户反馈、敏感词可自由传播。

后端三张表 `moment_report`（朋友圈/评论举报）、`message_report`（聊天消息举报）、`sensitive_word`（敏感词库）已由迁移脚本 `004-im-complete.sql` 建好，但**零实现**：没有过滤引擎、没有举报接口、前端无入口。本变更补齐这两块纯新增能力，闭环"四·新增能力"剩余项中的举报/敏感词。

## What

**C1 敏感词实时过滤（拦截 + 替换）**
- 新增 `SensitiveWordService`：应用启动时加载 `status=1` 的全部敏感词到内存；`filter(content)` 方法遍历词库：
  - 命中 `level=3`（禁止发送）→ 抛出业务异常（新增错误码 `CODE_2701`），消息不入库、不发送；
  - 命中 `level=1/2`（提醒/替换）→ 将内容中该词替换为 `***` 后继续；
  - v1 不区分"提醒"与"替换"语义，统一替换（表 `level` 字段保留，供后续细化）。
- 注入三条写入链路（统一在持久化前、内容已 reset 之后调用）：
  - 聊天发送：`ChatMessageServiceImpl.saveMessage`（L217 之后）
  - 朋友圈发布：`MomentServiceImpl.publish`（L73 之后）
  - 朋友圈评论：`MomentServiceImpl.addComment`（L189 之后）

**C2 内容举报**
- 新增 `ReportController`（`@GlobalInterceptor`）：
  - `POST /moment/report`：参数 `momentId` / `commentId`（二选一）、`reason`、`description` → 落 `moment_report`
  - `POST /chat/report`：参数 `messageId`、`reason`、`description` → 落 `message_report`
- `ReportService` 校验被举报对象存在（复用 `CODE_2501` 动态不存在 / `CODE_2201` 消息不存在），并对同一举报人、同一对象、状态为待处理（status=0）的举报做幂等（已存在则直接返回成功，不重复落库）。
- 前端 `ReportDialog.vue` 通用举报弹窗（reason 单选 0色情/1暴力/2诈骗/3侵权/4其他 + description），分别挂到：
  - 聊天消息右键菜单 → type=message
  - 朋友圈动态菜单 → type=moment（仅他人动态）
  - 朋友圈评论 → type=comment（仅他人评论）

## Capabilities

- C1：敏感词实时过滤（聊天 / 朋友圈发布 / 评论）
- C2：内容举报（朋友圈动态 / 评论 / 聊天消息）

## Impact

- **新增（后端）**：`SensitiveWord`/`MomentReport`/`MessageReport` PO；`SensitiveWordMapper`+XML、`MomentReportMapper`+XML、`MessageReportMapper`+XML；`SensitiveWordService`+impl（含 `@PostConstruct` 加载与 `filter`）；`ReportService`+impl；`ReportController`；错误码 `CODE_2701`(2701, 内容包含敏感词，禁止发送)。
- **修改（后端）**：`ChatMessageServiceImpl.saveMessage`、`MomentServiceImpl.publish`、`MomentServiceImpl.addComment` 各注入一处 `sensitiveWordService.filter(...)`。
- **新增（前端）**：`Api.js` 两端点；`ReportDialog.vue`；`ChatMessage.vue` 右键"举报"；`Moment.vue` 动态/评论"举报"。
- **新增（数据）**：`easychat-migration-005-sensitive-word-seed.sql`（可选执行的示例敏感词种子，不自动跑）。
- **契约**：纯新增端点，既有接口零改动 → **契约零漂移**。
- **数据库**：无结构变更（表已就绪），仅运行时读写。

## 人工确认关卡

1. 敏感词 level 语义落地：v1 将 1/2 合并为"替换"，3 为"禁止"。确认可接受（后续可经管理端细化）。
2. 举报仅"落库 + 防重复"，**不做处理端**（处理/审计留待后续管理端能力）；`status` 默认 0 待处理。
3. 不在本变更自动执行种子 SQL（避免污染既有数据）；由用户在 MySQL 客户端按需执行初始化词库。

> 用户已多轮授权"继续做举报/敏感词"，视为对上述范围的确认。
