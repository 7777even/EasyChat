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
