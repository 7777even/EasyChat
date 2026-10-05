# Spec — 内容治理（content-moderation）

> 能力来源：openspec/changes/2026-09-26-content-moderation（已归档）、openspec/archive/2026-10-05-sensitive-word-masking-determinism（已归档，替换阶段改为「长词优先 + 与词表顺序无关」）、openspec/changes/2026-09-26-sensitive-word-admin（已归档，新增「词库管理」子能力并修改敏感词加载范围）、openspec/archive/2026-09-29-admin-message-delete（已归档，新增「管理端消息删除位」子能力并修改举报处理 dealReport 语义）

## Requirement: 敏感词实时过滤（C1）

系统 SHALL 在聊天消息发送、朋友圈动态发布、朋友圈评论提交的三条写入链路中，对文本内容执行敏感词过滤。过滤规则：

- 命中 `level=3`（禁止发送）的敏感词时，系统 SHALL 拒绝写入并返回错误码 `CODE_2701`，内容不入库、不发送；
- 命中 `level=1/2`（提醒/替换）的敏感词时，系统 SHALL 将内容中的该词替换为 `***` 后继续；替换 SHALL 按**词条长度降序**执行，使互为子串的词条中长词优先获得匹配机会，最终输出 SHALL 与词表行顺序无关。排序 SHALL 在词库加载时预计算，不在消息过滤热路径内执行；
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

## Requirement: 打码结果与词表顺序无关（masking-order-independence）

内容过滤的**替换（打码）阶段**输出 SHALL 是**在册词条集合的纯函数**，不得依赖词条在内存列表 / 数据库结果集中的行顺序。当两个词条互为子串时，顺序 SHALL NOT 影响打码范围。

#### Scenario: 互含词条的两种排列产出相同输出

- **WHEN** 词表含短词 `ab` 与长词 `abcd`，内容为 `abcd`
- **THEN** 无论 `ab` / `abcd` 在内存列表中的先后，`filter` 的输出 SHALL 逐字节相同
- **AND** 该输出 SHALL 等于「仅按长词 `abcd` 替换」的结果（`***`），而非残留片段的 `***cd`

#### Scenario: 任意排列一致性

- **WHEN** 同一组 N 个词条以任意排列存在于内存列表
- **THEN** 对同一输入内容，所有排列下的 `filter` 输出 SHALL 相同

#### Scenario: 替换阶段不得泄露完整在册词

- **WHEN** 对任意在册 `level=1/2`（或 `level IS NULL`）词条与任意输入内容执行 `filter`
- **THEN** 输出 SHALL NOT 包含任何在册词条作为子串
- **AND** 系统 SHALL NOT 承诺「输出不含在册词的任何**片段**」——长词优先替换后残留的片段不属于在册词，不构成整词泄露

---

### Requirement: 长词优先替换（masking-longest-first）

替换阶段 SHALL 以**词条长度降序**遍历，使长词与短词存在包含关系时长词先获得匹配机会，短词在长词已被替换消失后不再重复命中。词条 SHALL 按「空词 / `null` 词排最后、长度降序、同长度按字面量升序」排序，以消除对具体排序算法稳定性实现的依赖。

#### Scenario: 长词先于其前缀被替换

- **WHEN** 词表含 `abcd` 与其前缀 `ab`，内容为 `abcd`
- **THEN** 输出 SHALL 为 `***`（长词整体被替换），SHALL NOT 为 `***cd`

#### Scenario: 空词与 null 词不参与排序比较的 NPE

- **WHEN** 内存词表中含 `word` 为 `null` 或空串的词条
- **THEN** 排序 SHALL 将其置于末尾且 SHALL NOT 抛出 `NullPointerException`
- **AND** 过滤时 SHALL 跳过该词条（不拦截、不替换），不影响其它词条正常生效

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
- `POST /admin/report/dealReport`：将举报 `status` 置为 1（已处理）或 2（已驳回），并记录 `handleUserId`/`handleTime`/`handleNote`/`handleAction`（0仅记录/1删内容/2封禁发布者）；**`handleAction=1` 时对被举报动态/评论软删（`status=0`），对聊天消息执行 `delete_flag` 逻辑删除并推送 20 帧同步（详见 `message-delete-flag` / `admin-delete-frame-20`）**；`handleAction=2` 时封禁发布者（`userInfoService.updateUserStatus(0, publisherId)`）；处置成功后 SHALL 写入一条 `report_audit_log` 审计记录。
- 重复处置/已处置记录（`status≠0`）SHALL 返回 `CODE_2702`。

#### Scenario: 管理员查看举报列表

- **WHEN** 管理员调用 `loadReport`（可带过滤）
- **THEN** 返回统一分页列表，含内容摘要与状态

#### Scenario: 管理员查看举报详情

- **WHEN** 调用 `getReportDetail(id, reportType)`
- **THEN** 返回内容全文 + 举报人/发布者信息

#### Scenario: 管理员处置举报

- **WHEN** 调用 `dealReport`（合法 status/handleAction）
- **THEN** 更新举报状态/处理人/时间/备注，并按动作执行删内容（消息类为逻辑删除+同步）或封禁发布者，写入审计日志

#### Scenario: 不可重复处置

- **WHEN** 举报记录不存在或 `status≠0`
- **THEN** 返回 `CODE_2702`

---

## Requirement: 聊天消息逻辑删除与证据保留（message-delete-flag）— C1

系统 SHALL 通过 `chat_message.delete_flag`（0=存活，非0=删除时间戳ms）为聊天消息提供逻辑删除位：用户侧所有查询路径无条件过滤已删消息，管理端证据读取保留原文。

#### Scenario: 举报处置真实删除

- **WHEN** 管理员对 `reportType=3` 的举报执行 `dealReport(handleAction=1 删除内容)`
- **THEN** 目标消息 `delete_flag` 置为处置时刻 ms，`handle_note` 记录删除动作，写入 `report_audit_log`
- **AND** 同一消息重复处置（已有删除位）幂等：不改写、不重复推送、处置照常成功

#### Scenario: 用户侧全路径不可见

- **WHEN** 消息被删后，任意用户调用 `loadHistoryMessage` / `searchMessage` / `globalSearch`，或经 WS INIT / SYNC 补推拉取消息
- **THEN** 查询条件无条件包含 `AND delete_flag = 0`，被删消息不返回、搜不到
- **AND** 该过滤为 SQL 字面条件，不存在可由 HTTP 绑定绕过的查询开关

#### Scenario: 管理端证据保留原文

- **WHEN** 管理员调用 `getReportDetail` 或 `loadReport` 查看已删消息的举报
- **THEN** 详情（`selectByMessageId` PK 读）与列表摘要（`ReportReadMapper` 独立 SQL）仍返回消息原文，不经过滤
- **AND** 举报列表可正常处置流程不受删除影响

#### Scenario: 会话预览不残留被删内容

- **WHEN** 被删消息是该会话的最新消息
- **THEN** 服务端 `chat_session.last_message` 与 `chat_session_user.last_message` 置「该消息已被管理员删除」，客户端本地会话预览经 20 帧同步为同一占位
- **AND** 被删消息非最新时，双端预览保持指向更新消息不变

---

## Requirement: 管理员删除帧实时同步（admin-delete-frame-20）— C2

管理员删除消息后，系统 SHALL 通过新增 WS 帧类型 `20 ADMIN_DELETE` 向单聊双方与群成员实时同步墓碑状态。

#### Scenario: 单聊接收方在线与离线

- **WHEN** 单聊消息被删，接收方在线 / 离线
- **THEN** 在线经 `sendMsg` 直推 20 帧；离线压入 Redis 离线缓冲，重连 `replayOfflineMessages` 补推
- **AND** 帧经 `CONTACT_CONVERT_TYPES`（含 20）转换后 `contactId=原发送者`，接收方定位到正确会话

#### Scenario: 发送方多端副本

- **WHEN** 单聊消息被删且原发送者有其他在线设备
- **THEN** 转换前的发送方副本（`contactId=会话对方`）直投其全部在线设备（发送方副本条件为 14|20）
- **AND** 原发送者全部设备离线时，副本帧入其 Redis 离线缓冲，重连 `replayOfflineMessages` 补推
- **AND** 补推时 `sendUserId`=收件人本人则跳过联系人转换（`contactId` 保持会话对方，不产生「联系人为自己」的脏会话）

#### Scenario: 群聊同步

- **WHEN** 群内消息被删
- **THEN** `sendMsg2Group` 向在线群成员推送 20 帧（群帧不做联系人转换，`contactId` 保持 groupId）
- **AND** 未收到在线帧的群成员由删除动作逐成员压入离线缓冲，重连补推
- **AND** 补推时 `contactType=1` 的群帧跳过联系人转换（`contactId` 保持 groupId，不产生脏会话）

#### Scenario: 客户端墓碑渲染

- **WHEN** 客户端主进程 `wsClient` 收到 20 帧
- **THEN** 本地 SQLite 该消息行更新为墓碑（已有则更新、离线缺行则补插），渲染层显示「该消息已被管理员删除」
- **AND** 帧带 `lastMessage` 时同步更新本地会话预览；不增加未读数、不触发任务栏闪烁/横幅/提示音（通知白名单 2/5/4 天然排除）
- **AND** 墓碑消息右键菜单隐藏（`messageType∉{2,5}` 不可撤回/转发）

#### Scenario: 与撤回帧语义隔离

- **WHEN** 对比 14（用户撤回）与 20（管理员删除）
- **THEN** 20 帧不改写 DB 中 `message_type` 与 `message_content`（证据保留），文案固定为管理员删除语义；撤回路径与统计不受 20 帧影响

---

## Requirement: 已删消息操作拒绝（deleted-message-guards）— C3

已删消息 SHALL 对普通用户的写操作与资源获取不可用。

#### Scenario: 撤回 / 下载 / 定位被拒

- **WHEN** 消息已被管理员删除，其发送者调用 `recallMessage`，或任意用户调用 `downloadFile` / `locateMessage`
- **THEN** 均返回 `CODE_2201 消息不存在`（守卫先于既有校验链）

#### Scenario: 重复处置幂等

- **WHEN** 已删消息再次被不同举报命中并执行删除内容
- **THEN** 不报错、不改写 `delete_flag`、不重复推送 20 帧，处置状态正常流转

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
