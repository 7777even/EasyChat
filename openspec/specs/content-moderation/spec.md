# Spec — 内容治理（content-moderation）

> 能力来源：openspec/changes/2026-09-26-content-moderation（已归档）、openspec/changes/2026-09-26-sensitive-word-admin（已归档，新增「词库管理」子能力并修改敏感词加载范围）

## Requirement: 敏感词实时过滤（C1）

系统 SHALL 在聊天消息发送、朋友圈动态发布、朋友圈评论提交的三条写入链路中，对文本内容执行敏感词过滤。过滤规则：

- 命中 `level=3`（禁止发送）的敏感词时，系统 SHALL 拒绝写入并返回错误码 `CODE_2701`，内容不入库、不发送；
- 命中 `level=1/2`（提醒/替换）的敏感词时，系统 SHALL 将内容中的该词替换为 `***` 后继续；
- 敏感词库来自 `sensitive_word` 表 `status=1 AND delete_flag=0` 的记录，应用启动时加载到内存，且管理端每次词库写变更（保存/删除/导入）后自动 `reload()` 热更；空词库时不产生任何拦截或替换。

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

---

## Requirement: 词库筛选与维护（word-admin-crud，C1）

管理员（token `admin=true`）SHALL 能够分页筛选（`keyword`/`level`/`status`）并维护敏感词库，列表仅返回 `delete_flag=0` 的存活词条。

- `POST /admin/sensitiveWord/saveWord`：新增或编辑词条（带 `id` 即编辑），`level∈{1,2,3}`、`status∈{0,1}` 由 `@Valid` 强制；新增或编辑改名遇同名存活词条 SHALL 返回 `CODE_2704`；编辑不存在的词条 SHALL 返回 `CODE_2705`。
- `POST /admin/sensitiveWord/deleteWord`：逻辑删除（`delete_flag` 置当前时间戳 ms，不物理删除），词条不存在或已删除 SHALL 返回 `CODE_2705`。
- 保存/删除成功后 SHALL 自动 `reload()` 生效。

#### Scenario: 管理员分页筛选词库

- **WHEN** 调用 `POST /admin/sensitiveWord/loadWord`（可选 `keyword/level/status` + `pageNo/pageSize`）
- **THEN** 返回 `Result<PaginationResultVO<SensitiveWordVO>>`，只含 `delete_flag=0` 的存活词条

#### Scenario: 新增重复词条被拒

- **WHEN** 新增的 `word` 已存在存活词条（或命中唯一索引）
- **THEN** 返回 `CODE_2704`，不落库不 reload

#### Scenario: 删除不存在的词条

- **WHEN** 调用 `deleteWord`，`id` 不存在或已删除
- **THEN** 返回 `CODE_2705`

---

## Requirement: 批量导入（word-bulk-import，C2）

管理员 SHALL 能够批量导入词条：`.txt`（一行一词，使用请求指定的统一 `level`/`status`）或 `.csv`（三列 `word,level,status`，可带表头），按扩展名识别。系统 SHALL 逐行容错并返回三态计数 `ImportResultVO{success, skipped, failed}`；重复词条（含文件内重复与库内存活重复）计入 `skipped` 且不重复入库；空行、列数不符、长度超 50、`level/status` 越界计入 `failed`。文件 >2MB、>5000 行或扩展名非 `.txt`/`.csv` SHALL 返回 `CODE_1001`。有新增成功行时 SHALL 自动 `reload()`。

#### Scenario: 导入 txt / csv

- **WHEN** 上传 ≤5000 行、≤2MB 的 `.txt` 或 `.csv`
- **THEN** 返回三态计数，成功行入库并触发一次 `reload()`

#### Scenario: 重复词条跳过

- **WHEN** 导入行的 `word` 已存在存活词条或在文件内重复
- **THEN** 该行计入 `skipped`，不报错、不整体失败、不产生重复行

#### Scenario: 文件超限或格式不支持

- **WHEN** 文件 >2MB、>5000 行，或扩展名非 `.txt`/`.csv`
- **THEN** 返回 `CODE_1001`，不解析

---

## Requirement: 词库导出与往返（word-export-roundtrip，C3）

管理员 SHALL 能够将当前词库导出为 CSV，且导出文件可原样导回。

- `GET /admin/sensitiveWord/exportWords` 返回 `text/csv` 文件流（UTF-8 BOM，列 `word,level,status`），仅含存活词条；对 `=+-@` 开头的值前置单引号防 Excel 公式注入。
- 导入侧 SHALL 对 `'` + `=+-@` 开头的值剥离该防护引号，保证往返等价。

#### Scenario: 导出

- **WHEN** 调用 `GET /admin/sensitiveWord/exportWords`
- **THEN** 返回带 BOM 的 CSV 文件流，已删词条不出现

#### Scenario: 往返等价

- **WHEN** 将导出文件原样通过 `importWords` 重新上传
- **THEN** 全部行计入 `skipped`（`success=0`），词库内容与级别/状态不发生变化

---

## Requirement: 变更即时生效（word-hot-reload，C4）

词库任意写变更（保存/删除/导入）后 SHALL 即时生效于发送链路过滤。

#### Scenario: 新词即刻拦截

- **WHEN** 新增一条 `level=3` 词条并保存成功
- **THEN** 随后发送的含该词消息被拦截返回 `CODE_2701`（不入库不推送）

#### Scenario: level2 替换即刻生效

- **WHEN** 新增一条 `level=2` 词条并保存成功
- **THEN** 随后发送的含该词消息内容被替换为 `***` 后正常送达

#### Scenario: 删除即刻失效

- **WHEN** 管理员删除某词条
- **THEN** reload 后该词不再参与过滤（加载 SQL 限定 `delete_flag=0`）

---

## Requirement: 逻辑删除与唯一性共存（word-softdelete-unique，C5）

`sensitive_word` SHALL 以 `delete_flag BIGINT`（0=存活，非0=删除时间戳 ms）实现逻辑删除，并以唯一索引 `uk_word_flag(word, delete_flag)` 保证：存活行同词唯一，已删行不占用 `word` 唯一位。

#### Scenario: 删后重导

- **WHEN** 某存活词条被逻辑删除后，再次导入含同一 `word` 的文件
- **THEN** 该行计入 `success` 正常入库（删除行时间戳互异，不冲突）

#### Scenario: 删后重新新增

- **WHEN** 词条被逻辑删除后，通过 `saveWord` 新增同一 `word`
- **THEN** 保存成功，返回 `code=0`

---

## Requirement: 词库管理端权限隔离（word-admin-only）

所有 `/admin/sensitiveWord/*` 接口 SHALL 使用 `@GlobalInterceptor(checkAdmin = true)`；非管理员调用 SHALL 被拦截返回 `CODE_404`，无 token SHALL 返回 `CODE_901`（含导出文件流）。

#### Scenario: 非管理员调用

- **WHEN** 普通用户调用 `/admin/sensitiveWord/loadWord` 或 `deleteWord`
- **THEN** 返回 `CODE_404`

#### Scenario: 无 token 调用

- **WHEN** 无 token 调用 `loadWord` 或 `exportWords`
- **THEN** 返回 `CODE_901`
