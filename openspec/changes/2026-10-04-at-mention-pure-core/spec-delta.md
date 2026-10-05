# Spec Delta — 群聊 @ 提及子系统抽纯决策核心

- 目标 capability: `at-all`（**修改既有**，不新建）
- 创建日期: 2026-10-04

> 纯核心的**代码结构**（`.mjs` 拆分、node 可 import）属实现落点，不写成 Requirement；
> 只有**用户可观测的行为变化**才进 spec。

## ADDED Requirements

### Requirement: 群成员搜索与显示口径一致

@ 面板中能在列表里看到的成员，必须能被搜到。搜索同时匹配成员的**昵称**与 **userId**。

#### Scenario: 按昵称搜索

- **WHEN** 在 @ 面板的搜索框输入某个成员昵称的一部分（大小写不限）
- **THEN** 该成员出现在结果列表中

#### Scenario: 按 userId 搜索未设昵称的成员

- **WHEN** 某成员 `contactName` 为空（列表中显示为其 userId）
- **THEN** 用该 userId 搜索时**能命中该成员**
- **AND** 与该成员已设昵称时的搜索体验**一致**

> 抽离前：过滤只用 `contactName`，而显示用 `contactName || userId`，
> 导致未设昵称者**看得见却搜不到**。本条使两个口径一致。

#### Scenario: 关键词大小写与首尾空白不敏感

- **WHEN** 搜索框输入 `  zhang  `
- **THEN** 与输入 `Zhang` 的结果相同

---

### Requirement: atUserIds 口径统一且去重

同一条消息内，被 @ 的用户 ID 集合在 `extraData.atUserIds` 与消息的 `atUserIds` 字段中
必须一致，且**不含重复项**。

#### Scenario: 重复 @ 同一成员只产生一个 ID

- **WHEN** 正文为 `@U001 你好 @U001 再见`（同一成员被 @ 两次）
- **THEN** `extraData.atUserIds` 为 `["U001"]`，长度 1
- **AND** 消息的 `atUserIds` 字段为 `"U001"`（非 `"U001,U001"`）

#### Scenario: 两处口径不得分叉

- **WHEN** 校验 `extraData.atUserIds` 与消息 `atUserIds` 字段
- **THEN** 二者的元素集合**完全相同**
- **AND** 二者由**同一份** ID 提取实现产出（不得各写一份正则）

> 抽离前：`buildExtraData` 不去重、`buildAtUserIds` 去重，且**各自复制了同一个正则**，
> 两处口径已分叉。本条消除分叉隐患。
> **正则本身本轮不变**（`/@(U[A-Za-z0-9]+)/g`），收紧它属跨端契约变更，见「已知边界」。

---

## MODIFIED Requirements

### Requirement: @所有人

群主/管理员可在群聊中@所有人。**该标记的写入以角色权限为准**。

#### Scenario: @所有人

- **WHEN** 群主/管理员在群聊中点击@按钮并选择「@所有人」
- **THEN** 发送@所有人消息
- **AND** 所有群成员收到消息

#### Scenario: 普通群成员@所有人

- **WHEN** 普通群成员在群聊中点击@按钮
- **THEN** 不显示「@所有人」选项

#### Scenario: 普通成员手工键入「@所有人」不产生标记，且消息正常发出

- **WHEN** 普通群成员（role=2）在正文中**手工键入**「@所有人」并发送
- **THEN** 消息**正常发出**，不返回任何错误码
- **AND** `extraData` 中**不写入** `atAll` 字段
- **AND** 该消息在群内**不具备** @所有人 的样式与提醒

**变更前**: 客户端把「正文含 @所有人」直接当作 @所有人 标记（权限判定只作用于面板点击），
导致普通成员手工键入后 `extraData.atAll=true`，服务端 `checkGroupRole` 抛 `CODE_2305`，
**整条消息发送失败**。用户在界面上的观感是「好好打了条消息突然发不出去」。

**变更后**: `atAll` 的写入条件叠加角色权限判定。普通成员手工键入时标记不生效，
但**消息本身正常送达**——把「权限不足」从「发送失败」降级为「标记不生效」。

#### Scenario: 权限判定的兜底分支仍保留（草稿重发兼容）

- **WHEN** 群主/管理员的消息以草稿形式恢复后重发（`atAllEnabled` 运行时状态已丢失，
      但正文仍含「@所有人」）
- **THEN** `extraData` **仍写入** `atAll: true`
- **AND** 消息具备 @所有人 效果

> 权限是**发送侧**的正确性前提，「正文含 @所有人 也认」是**有意的兜底**
> （兼容草稿恢复）。本变更只做**叠加**权限判定，**不删除**该兜底。

---

## REMOVED Requirements

（无）

---

## 已知边界（本次明确不覆盖）

- **正则 `/@(U[A-Za-z0-9]+)/g` 的误命中**依然存在：`@Ubuntu` 会被提取为用户 ID `buntu`。
  收紧该正则会改变 `extraData.atUserIds` 的产出集合，属**跨端契约变更**
  （服务端 `ExtraDataTools` 与 `MessageSendDto.atUserIds` 消费同一格式），
  须独立 Change 并同步前后端与存量数据评估。
- 本地 SQLite `chat_message` 无 `extra_data` 列，`atAll` 不落本地库；
  重启后靠正文含「@所有人」兜底判定样式，该兜底不受本次变更影响。
