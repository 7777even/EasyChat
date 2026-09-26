# 设计：举报处理与管理端审计

## 架构与接口

管理端统一前缀 `/admin/report`，所有接口加 `@GlobalInterceptor(checkAdmin = true)`，
复用既有 `GlobalOperationAspect` 的 `checkLogin(true)` → 非管理员抛 `CODE_404`。

| 接口 | 方法 | 入参 | 出参 |
|---|---|---|---|
| `loadReport` | POST | `ReportQuery`(pageNo,pageSize,reportType,status,reason,startTime,endTime) | `Result<PaginationResultVO<AdminReportVO>>` |
| `getReportDetail` | POST | `id`,`reportType` | `Result<AdminReportDetailVO>` |
| `dealReport` | POST | `id`,`reportType`,`status`(1/2),`handleAction`(0/1/2),`handleNote` | `Result<Void>` |
| `loadAuditLog` | POST | `pageNo,pageSize,reportId,reportType,adminId,action,startTime,endTime` | `Result<PaginationResultVO<ReportAuditLogVO>>` |

## 数据模型

### report_type 取值（统一视图判别）
- `1` 朋友圈动态（moment）
- `2` 朋友圈评论（comment）
- `3` 聊天消息（message）

### AdminReportVO（列表行）
`id, reportType, targetId, reportUserId, reportUserName, reason, description, status,
handleUserId, handleTime, createTime, contentExcerpt(前120字)`

### 统一读视图（UNION）
`ReportReadMapper` 用一条 UNION 把 `moment_report`(含 comment 判别) 与 `message_report` 投影为统一列，
`moment_report` 通过 `LEFT JOIN moment` / `moment_comment` 取内容摘要；`message_report` 通过
`LEFT JOIN chat_message` 取 `message_content` 摘要。外层套 `<where>` 过滤 + `ORDER BY create_time DESC`
+ `LIMIT #{query.simplePage.start},#{query.simplePage.end}`（沿用 `BaseParam.simplePage` 分页惯用法）。

### dealReport 处置逻辑
1. 按 `id + reportType` 取举报记录；`null` 或 `status != 0` → `CODE_2702`（不存在或已处理）。
2. 更新举报：`status`、`handleUserId`(admin.userId)、`handleTime`(now)、`handleNote`、`handleAction`。
3. 动作分支：
   - `handleAction=1` 删除内容：type1 `momentMapper` 取后 `setStatus(0)`+`updateById`；type2 同理作用于 comment；
     type3 聊天消息无删除状态位 → 不物理删除，仅在 `handleNote` 中提示人工处理。
   - `handleAction=2` 封禁发布者：解析发布者 `userId`（moment.userId / comment.userId / message.sendUserId），
     调 `userInfoService.updateUserStatus(0, publisherId)`（`UserStatusEnum.DISABLE`）。
4. **无论动作如何，始终写入一条 `report_audit_log`**（append-only，不可变，作为审计源）。

### report_audit_log 表
`id, report_id, report_type, target_id, admin_id, action(1处理/2驳回), handle_action, handle_note, create_time`

## 错误码
- 新增 `CODE_2702(2702, "举报记录不存在或已处理")`（复用于详情/处置取不到或已处置）。

## 前端
- `views/admin/ReportList.vue` 镜像 `GroupList.vue` 风格（`Table` 组件 + `proxy.Request/Confirm/Message`）。
- 搜索区：reportType 下拉、status 下拉、日期范围；表格列：类型/内容摘要/举报人/理由/状态/时间/操作。
- 「查看」弹窗：展示被举报内容全文 + 举报人/发布者信息 + **审计时间线**（`loadAuditLog` 按举报过滤）。
- 「处理」弹窗：已处理/已驳回 单选、handleAction 下拉（仅记录/删除内容/封禁发布者）、handleNote 文本域。
- `Admin.vue` 菜单新增「举报管理」；`router` 新增 `/admin/reportList` 子路由；`Api.js` 新增 4 个端点。

## 依赖与数据影响
- 依赖 `userInfoService`（已存在）、`momentMapper`/`momentCommentMapper`（有 `updateById`）、`chatMessageMapper`。
- 数据库：migration-006 执行 2 次 ALTER + 1 次 CREATE；并同步 `easychat.sql` 基线（保证新库一致）。
- 无对外契约破坏性变更，纯新增。

## 验证（验收口径）
- 后端 `mvn clean compile` 通过。
- 前端 `electron-vite build` 通过（绕过 safe-delete 守卫）。
- 三道门禁（IPC 注册 / 接口契约 / openspec 卫生）全绿。
- 手动验证清单（QA 文档）：看列表→看详情→处置(已处理/驳回)→审计时间线出现→封禁/删内容副作用生效。
