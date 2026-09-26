# Spec Delta：content-moderation（扩展举报处理与审计）

> 本 delta 在既有 `content-moderation` capability（举报入口 + 敏感词引擎）基础上，新增「举报处理 + 管理端审计」子能力。

## ADDED

### Requirement：举报处理（report-handling）
管理员（token `admin=true`）必须能够查看、过滤、查看详情并处置用户举报。

- Scenario：管理员查看举报列表
  - Given 管理员已登录
  - When 调用 `POST /admin/report/loadReport`（可选 reportType/status/reason/时间过滤）
  - Then 返回跨 `moment_report`/`message_report` 的统一分页列表，含内容摘要与状态

- Scenario：管理员查看举报详情
  - Given 存在举报记录
  - When 调用 `POST /admin/report/getReportDetail`(id, reportType)
  - Then 返回举报字段 + 被举报内容全文 + 举报人/发布者信息

- Scenario：管理员处置举报
  - Given 举报记录 `status=0`（待处理）
  - When 调用 `POST /admin/report/dealReport`(id, reportType, status∈{1,2}, handleAction∈{0,1,2}, handleNote)
  - Then 更新举报状态/处理人/时间/备注，并按 handleAction 执行删内容或封禁发布者
  - And 写入一条 `report_audit_log` 审计记录

- Scenario：重复/已处置举报不可重复处置
  - Given 举报记录不存在或 `status≠0`
  - When 调用 `dealReport`
  - Then 返回 `CODE_2702`

### Requirement：管理端审计（admin-audit）
系统必须对每一次举报处置动作保留不可变审计轨迹，可供查询追溯。

- Scenario：审计日志留痕
  - Given 一次成功的 `dealReport`
  - When 查询 `POST /admin/report/loadAuditLog`(reportId, reportType 或 adminId/action/时间)
  - Then 返回该次处置的审计记录（动作、处理人、时间、备注）

### Requirement：权限隔离（admin-only）
- Scenario：非管理员调用管理端举报接口
  - When 普通用户调用 `/admin/report/*`
  - Then 被 `checkAdmin` 拦截，返回 `CODE_404`

## MODIFIED

（无既有能力变更；仅扩展字段）
- `moment_report` 新增 `handle_note`、`handle_action`
- `message_report` 新增 `handle_user_id`、`handle_time`、`handle_note`、`handle_action`

## REMOVED

（无）
