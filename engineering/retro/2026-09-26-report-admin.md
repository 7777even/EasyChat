# Retro：举报处理与管理端审计（2026-09-26）

## 做得好
- 复用既有 `checkAdmin` 拦截与 `updateUserStatus` 封禁逻辑，未新增鉴权机制，权限边界清晰。
- 举报跨两张表（moment_report / message_report）用一条 UNION 读视图统一分页，避免服务层两表合并分页的麻烦；审计日志独立 append-only 表，天然满足「不可变审计源」。
- 前端 `ReportList.vue` 严格镜像 `GroupList.vue` 的 `Table` 组件惯用法，无新范式引入，PR 评审成本低。
- 迁移与 `easychat.sql` 基线同步，保证新库与存量库一致。

## 问题
- `message_report` 此前缺 `handle_*` 字段、`moment_report` 缺 `handle_note/handle_action`，两张表字段不对齐，导致处置写入需要分别 update——已在 migration-006 对齐，属历史债清理。
- 聊天消息无删除状态位，`handleAction=1` 对消息类型只能「仅记录」，前端已用告警提示，避免误导。

## 原因
- 举报能力前序 Change（content-moderation）只做了「用户侧入口 + 敏感词」，未规划管理端闭环，字段未铺到位。
- 消息表设计早期未预留软删状态位（与 moment/comment 不一致）。

## 改进方案
- 后续若要做「消息撤回/删除」类管理动作，应给 `chat_message` 增加 `status` 删除位（独立于发送态），与 moment/comment 对齐。
- 跨表统一视图模式（UNION + 统一 VO）可沉淀为通用模板，复用于未来「通知/审核」类跨实体列表。
