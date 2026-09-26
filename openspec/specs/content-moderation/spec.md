# Spec — 内容治理（content-moderation）

> 能力来源：openspec/changes/2026-09-26-content-moderation（已归档）

## Requirement: 敏感词实时过滤（C1）

系统 SHALL 在聊天消息发送、朋友圈动态发布、朋友圈评论提交的三条写入链路中，对文本内容执行敏感词过滤。过滤规则：

- 命中 `level=3`（禁止发送）的敏感词时，系统 SHALL 拒绝写入并返回错误码 `CODE_2701`，内容不入库、不发送；
- 命中 `level=1/2`（提醒/替换）的敏感词时，系统 SHALL 将内容中的该词替换为 `***` 后继续；
- 敏感词库来自 `sensitive_word` 表 `status=1` 的记录，应用启动时加载到内存；空词库时不产生任何拦截或替换。

#### Scenario: 发送含禁止词的消息被拦截

- **WHEN** 用户发送内容包含 `level=3` 敏感词
- **THEN** 系统返回 `CODE_2701`，消息不入库、不推送

#### Scenario: 发送含替换词的消息被替换

- **WHEN** 用户发送内容包含 `level=1/2` 敏感词
- **THEN** 该词被替换为 `***` 后正常入库并推送

#### Scenario: 空词库不拦截

- **WHEN** `sensitive_word` 表中无启用词
- **THEN** 所有内容原样通过

---

## Requirement: 内容举报（C2）

用户 SHALL 能够对朋友圈动态、朋友圈评论、聊天消息发起举报，选择举报理由（0色情/1暴力/2诈骗/3侵权/4其他）并填写补充说明。系统 SHALL 将举报落库到 `moment_report` / `message_report`（`status=0` 待处理）。对同一举报人、同一被举报对象、状态为待处理（status=0）的举报，系统 SHALL 幂等处理（已存在则直接返回成功，不重复落库）。被举报对象不存在时 SHALL 返回对应错误码（动态/评论 `CODE_2501`、消息 `CODE_2201`）。

#### Scenario: 举报朋友圈动态

- **WHEN** 用户对一条他人朋友圈动态发起举报（`POST /report/moment`，`momentId` + `reason`）
- **THEN** `moment_report` 新增一条 `status=0` 记录

#### Scenario: 举报评论

- **WHEN** 用户对一条他人评论发起举报（`POST /report/moment`，`commentId` + `reason`）
- **THEN** `moment_report` 新增一条 `status=0` 记录（`comment_id` 填充）

#### Scenario: 举报聊天消息

- **WHEN** 用户对一条聊天消息发起举报（`POST /report/chat`，`messageId` + `reason`）
- **THEN** `message_report` 新增一条 `status=0` 记录

#### Scenario: 重复举报幂等

- **WHEN** 同一用户对同一对象再次举报
- **THEN** 系统返回成功且不新增重复记录

#### Scenario: 举报不存在的内容

- **WHEN** 举报对象（动态/评论/消息）不存在
- **THEN** 系统返回错误码提示（`CODE_2501` / `CODE_2201`）

---

## Requirement: 举报处理（C3）

管理员（`token` 中 `admin=true`）SHALL 能够查看、过滤、查看详情并处置用户举报。系统 SHALL 提供：

- `POST /admin/report/loadReport`：跨 `moment_report` / `message_report` 的统一分页列表，支持按 `reportType`(1动态/2评论/3消息)、`status`(0待处理/1已处理/2已驳回)、`reason`、`createTime` 范围过滤；
- `POST /admin/report/getReportDetail`：返回举报字段 + 被举报内容全文 + 举报人/发布者信息；
- `POST /admin/report/dealReport`：将举报 `status` 置为 1（已处理）或 2（已驳回），并记录 `handleUserId`/`handleTime`/`handleNote`/`handleAction`（0仅记录/1删内容/2封禁发布者）；`handleAction=1` 时对被举报动态/评论软删（`status=0`），聊天消息无删除状态位故仅记录；`handleAction=2` 时封禁发布者（`userInfoService.updateUserStatus(0, publisherId)`）；处置成功后 SHALL 写入一条 `report_audit_log` 审计记录。
- 重复处置/已处置记录（`status≠0`）SHALL 返回 `CODE_2702`。

#### Scenario: 管理员查看举报列表

- **WHEN** 管理员调用 `loadReport`（可带过滤）
- **THEN** 返回统一分页列表，含内容摘要与状态

#### Scenario: 管理员查看举报详情

- **WHEN** 调用 `getReportDetail(id, reportType)`
- **THEN** 返回内容全文 + 举报人/发布者信息

#### Scenario: 管理员处置举报

- **WHEN** 调用 `dealReport`（合法 status/handleAction）
- **THEN** 更新举报状态/处理人/时间/备注，并按动作执行删内容或封禁发布者，写入审计日志

#### Scenario: 不可重复处置

- **WHEN** 举报记录不存在或 `status≠0`
- **THEN** 返回 `CODE_2702`

---

## Requirement: 管理端审计（C4）

系统 SHALL 对每一次举报处置动作保留一条不可变审计记录（`report_audit_log`），可供查询追溯。

#### Scenario: 审计日志留痕

- **WHEN** 一次 `dealReport` 成功
- **THEN** `POST /admin/report/loadAuditLog` 可按 `reportId`+`reportType` 或 `adminId`/`action`/时间过滤返回该次处置审计记录

---

## Requirement: 管理端权限隔离（C5）

所有 `/admin/report/*` 接口 SHALL 使用 `@GlobalInterceptor(checkAdmin = true)`；非管理员调用 SHALL 被拦截返回 `CODE_404`。

#### Scenario: 非管理员调用

- **WHEN** 普通用户调用 `/admin/report/loadReport`
- **THEN** 返回 `CODE_404`
