# 任务：内容治理（举报 + 敏感词过滤）

> 验收口径：后端 `mvn compile` BUILD SUCCESS；前端 `electron-vite build` 通过；三道门禁（IPC 注册 / 接口契约 / openspec 卫生）全绿；提供手动验证清单。

## 敏感词过滤（C1）

- [x] T1 错误码：新增 `CODE_2701(2701, "内容包含敏感词，禁止发送")`，并补注释段 `2700-2799 内容治理域`。
- [x] T2 `SensitiveWord` PO（映射 `sensitive_word` 全字段）+ `SensitiveWordMapper` + XML（selectByStatus）。
- [x] T3 `SensitiveWordService`/impl：`@PostConstruct` 加载 `status=1` 词库到内存；`String filter(String)` —— level3 抛 `CODE_2701`，level1/2 替换为 `***`；`reload()` 预留。
- [x] T4 注入过滤：`ChatMessageServiceImpl.saveMessage`（L217 后）、`MomentServiceImpl.publish`（L73 后）、`MomentServiceImpl.addComment`（L189 后）调用 `sensitiveWordService.filter(...)` 并回写 content。

## 内容举报（C2）

- [x] T5 `MomentReport`/`MessageReport` PO（映射两表全字段）+ 各自 Mapper + XML（insert / countPending）。
- [x] T6 `ReportService`/impl：`reportMoment(momentId, commentId, user, reason, description)`、`reportMessage(messageId, user, reason, description)`；校验对象存在（2501/2201）；同人同对象 status=0 幂等。
- [x] T7 `ReportController`：`POST /report/moment`、`POST /report/chat`，均 `@GlobalInterceptor`。

## 前端

- [x] T8 `Api.js` 新增 `reportMoment: "/report/moment"`、`reportChat: "/report/chat"`。
- [x] T9 新建 `ReportDialog.vue`（props type/targetId，reason 单选 + description，确认调对应 API）。
- [x] T10 `ChatMessage.vue` 右键菜单加"举报"（type=message）；`Moment.vue` 动态（非自己）与评论（非自己）加"举报"（type=moment / comment）。

## 数据 & 收尾

- [x] T11 新增 `easychat-migration-005-sensitive-word-seed.sql`（可选执行示例词库）。
- [x] T12 `docs/system-facts.md` 回写敏感词过滤 + 举报能力；openspec spec 回填 `openspec/specs/content-moderation/spec.md`；归档 Change；写 QA/Retro。
