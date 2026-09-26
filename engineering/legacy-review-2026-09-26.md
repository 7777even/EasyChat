# EasyChat 遗留盘点 — 2026-09-26

> 只读排查，未改动任何代码。证据取自 `git status`、源码搜索、openspec 目录、system-facts.md。
> 严重度：P0 半成品/事实失真 > P1 流程与配置 > P2 技术债。

## P0 — 半成品特性 + 事实失真（高优先）

### 1. voice-call 音视频通话：后端未提交 + 前端完全缺失 + 文档误记
- **后端已实现但未提交**：`git status` 显示以下为未跟踪（`??`）新文件，从未进版本库——
  - `easychat-java/.../entity/po/CallLog.java`、`CallLogQuery.java`
  - `easychat-java/.../mappers/CallLogMapper.java` + `resources/com/easychat/mappers/CallLogMapper.xml`
  - `easychat-java/.../service/CallLogService.java`、`service/impl/CallLogServiceImpl.java`
  - `easychat-java/.../websocket/CallService.java`
  - `easychat-migration-008-voice-call.sql`
  - 另有 `Constants.java` / `HandlerWebSocket.java` / `ChannelContextUtils.java` / `application.properties` / `easychat.sql` 为已修改未提交（`M`）
- **前端完全缺失**：后端 `src` 存在 `CallRoomRegistry`/`CALL_*` 帧/`call_log` 落库，但前端 `easychat-front/src` 搜不到 `WebRTC`/`CallWindow`/`useCallStore`/`CALL_INVITE` 任何匹配。即用户无法发起/接听通话，特性不可用；后端已实现部分沦为「孤儿能力」。
- **文档失真**：`docs/system-facts.md` §12 末行（变更日志 2026-09-26）把它记成「新增语音/视频通话……L4 四件套 + 人工确认关卡已通过」，与实际（前端零实现、后端未提交）不符。
- **openspec 未闭环**：`openspec/changes/2026-09-26-voice-call/` 仍在 `changes/`（未 `git mv` 归档），`tasks.md` 全部任务 `[ ]` 未勾选；`engineering/` 缺 voice-call 的 QA/Retro；`openspec/specs/` 无 `voice-call`（spec-delta 要求新建该 capability）。

**建议收尾**：要么（a）补全前端并真正跑通，再勾 tasks / 写 QA+Retro / 回写 spec / 归档 / 按域提交；要么（b）若暂不做，先把已写后端代码提交（避免工作区丢失），并将 system-facts 相应行改为「后端信令+call_log 已落地、前端待实现」，tasks.md 标注真实进度。无论哪种，system-facts 的失真行必须先纠正。

## P1 — 流程与配置遗留（中优先）

### 2. 工作区存在未提交改动散落
`git status` 列出：`docs/system-facts.md`、`easychat-java/.../Constants.java`、`websocket/ChannelContextUtils.java`、`websocket/netty/HandlerWebSocket.java`、`application.properties`、`easychat.sql` 已修改未提交；外加 voice-call 后端 8 个未跟踪文件。整批改动未纳入版本控制，有丢失/与远端不一致风险。

### 3. 日志路径未配置 → `log.path_IS_UNDEFINED`
`easychat-java/src/main/resources/logback-spring.xml:10` 用 `<springProperty scope="context" name="log.path" source="project.folder"/>`，但 `application.properties` 未注入 `project.folder`，导致 `${log.path}` 解析为字面量 `log.path_IS_UNDEFINED`，日志实际落到仓库内 `easychat-java/log.path_IS_UNDEFINED/logs/`。影响可观测性，且可能污染仓库（该目录已存在运行产物）。
**建议**：在 `application.properties` 补 `project.folder=...`（或 logback 给默认路径），并清理已生成的 `log.path_IS_UNDEFINED` 目录。

### 4. migration-003 编号缺口
迁移文件序列：`001-group-management` / `002-message-reliability` / **（缺 003）** / `004-im-complete` / `005-sensitive-word-seed` / `006-report-admin` / `007-sensitive-word-admin` / `008-voice-call`。全仓无任何对 `migration-003` 的引用或说明。需确认 003 是被合并进其他迁移还是遗漏，避免后续撞号或误解基线。
**注意**：`easychat.sql` 基线已含所有新表（call_log/sensitive_word/report_*/group_file 均在），说明迁移内容已合入基线；缺口主要是编号连续性，而非功能缺失。

## P2 — 技术债（低优先，可择机清理）

### 5. `@Deprecated` 旧错误码仍被广泛使用（标注误导）
`ResponseCodeEnum` 第 74–98 行标注 `@Deprecated` 的旧码：`CODE_404`/`CODE_500`/`CODE_600`/`CODE_601`/`CODE_602`/`CODE_901`/`CODE_902`/`CODE_903` 共 8 个。但源码中仍有 50+ 处调用（如 `GlobalOperationAspect.java:53/56/66/69`、`ChatController.java:73/127/138`、`ChatMessageServiceImpl.java:204/206/364…`、`GroupInfoServiceImpl`、`MomentServiceImpl`、`UserContactApplyServiceImpl` 等）。
`@Deprecated` 标注与实际使用矛盾：要么先把这些调用方迁移到新码（CODE_1001/1002/2001/2102/2104/2301/2302/2602）再删旧码；要么移除过早的 `@Deprecated` 标注。属「误导型技术债」，不紧急但应消除歧义。

### 6. 前端兼容分支小债
- `easychat-front/src/renderer/src/utils/Request.js:31` 「向后兼容旧码」分支。
- `views/chat/ChatMessage.vue:150` 消息扩展数据兼容「对象与 JSON 字符串两种形态」。
两者均为正常运行的兼容逻辑，长期应统一为单一形态，优先级低。

## 附：已确认「不是遗留」的项（免于误报）
- `easychat.sql` 基线已包含全部新表（call_log / sensitive_word / moment_report / message_report / report_audit_log / group_file），迁移内容未丢失。
- `ResponseVO` 旧响应包络已删除（system-facts §12 已记），非遗留。
- IPC 注册门禁 `check-ipc-registration.mjs --strict` 已建，5 个漏注册通道已修（system-facts §13 已记）。
