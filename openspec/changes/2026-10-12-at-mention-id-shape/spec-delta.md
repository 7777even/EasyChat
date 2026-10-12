# Spec Delta — @ 提及 ID 形态收紧

- 关联 Tasks: 2026-10-12-at-mention-id-shape/tasks.md
- 创建日期: 2026-10-12

## ADDED Requirements

### Requirement: 靓号字符集约束（对齐 proposal Capability C1）

管理员创建的靓号，其 `userId` 部分必须为纯数字，与系统自动生成的 userId 形态
（`StringTools.getUserId()` = `U` + 11 位数字）严格一致。

#### Scenario: 合法纯数字靓号可保存

- **WHEN** 管理员保存 `userId = "888888"` 的靓号
- **THEN** 保存成功，落库 `user_id = "U888888"`
- **AND** 不抛异常

#### Scenario: 含字母的靓号被拒

- **WHEN** 管理员保存 `userId = "ABC"` 的靓号
- **THEN** 抛 `CODE_1001`（参数非法）
- **AND** **不落库**（校验发生在唯一性查询与 insert 之前）

#### Scenario: 空值与超长值被拒

- **WHEN** `userId` 为 `null`、空串、或非数字字符（含空格、`-`、`_`）
- **THEN** 一律抛 `CODE_1001`

---

### Requirement: @ 提及 ID 形态精确化（对齐 proposal Capability C2）

从消息正文提取被 @ 的用户 ID 时，匹配形态必须精确等于真实 userId 集合，
即 `U` 后接 11 位数字，或系统账号 `Urobot`；普通英文单词不得被误判为提及。

#### Scenario: 正常提及被正确提取

- **WHEN** 正文含 `@U01234567890` 与 `@Urobot`
- **THEN** 提取结果为 `["U01234567890", "Urobot"]`（去重、保持首次出现顺序）

#### Scenario: 英文单词不再误命中

- **WHEN** 正文含 `@Ubuntu`、`@User`、`@Usernames`
- **THEN** 提取结果为空数组
- **AND** `extraData` 不含 `atUserIds`

#### Scenario: 数字后紧跟字母不误命中

- **WHEN** 正文含 `@U01234567890abc`
- **THEN** 提取结果为空数组（`(?![\w])` 边界要求生效）

#### Scenario: 已选中文本后接 @ 的合法提及不被漏掉

- **WHEN** 用户在输入框选中「你好」后点 @ 面板，产出 `你好@U01234567890 `
- **THEN** 提取结果为 `["U01234567890"]`
- **AND** **不因缺少左词边界而漏掉**（ADR-002 的取舍点，须由该用例钉住）

---

## MODIFIED Requirements

### Requirement: @ 提及的注释与文档表述

`atMentionCore.mjs` 中 `AT_USER_ID_PATTERN` 的注释不得再声称本形态是「跨端契约」，
也不得再记录错误的示例值。

#### Scenario: 注释与实现一致

- **WHEN** 阅读 `atMentionCore.mjs` 的 `AT_USER_ID_PATTERN` 注释
- **THEN** 注释说明「字符集上界来自 userId 的实际取值集合：U+数字 ∪ Urobot」
- **AND** 不出现「跨端契约」字样（服务端并不解析正文）

**变更前**: 「格式 `@U<字母数字>` 是**跨端契约** —— 服务端 `ExtraDataTools.isAtAll` 与
`MessageSendDto.atUserIds` 消费同一形态……已知误命中：`@Ubuntu` 会被提取为 `buntu`」

**变更后**: 说明真实 userId 集合为 `U`+数字 ∪ `Urobot`；记录 2026-10-12 取证结论
（服务端不解析正文、该字段只写不读、`@Ubuntu` 实提取为 `Ubuntu`）；说明为何不加左词边界。

---

## REMOVED Requirements

（无）

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 靓号字符集约束 | 2.1, 2.2 |
| C2 | ADDED: @ 提及 ID 形态精确化 | 1.1, 1.2, 3.1, 3.2 |
| （文档准确性） | MODIFIED: @ 提及的注释与文档表述 | 4.1, 4.2 |