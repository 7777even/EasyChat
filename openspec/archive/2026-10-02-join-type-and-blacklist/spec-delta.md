# Spec Delta — 加我方式可配置 + 黑名单可查可解

- 关联 Tasks: 2026-10-02-join-type-and-blacklist/tasks.md
- 创建日期: 2026-10-02

## ADDED Requirements

### Requirement: 更新加我方式

用户可自助修改「加我的方式」，修改后对**之后发起**的好友申请立即生效。

#### Scenario: 切换为「直接加入」

- **WHEN** 用户在账号设置把朋友权限选为「直接加入」并保存
- **THEN** 返回 `code=0`
- **AND** `user_info.join_type` 更新为 `0`
- **AND** 之后他人申请加我时**无需审批直接成为好友**（`applyAdd` 直读 DB，无缓存需失效）

#### Scenario: 切换为「加我时需验证」

- **WHEN** 用户选为「加我时需验证」并保存
- **THEN** `user_info.join_type` 更新为 `1`
- **AND** 之后他人申请加我时落一条待处理申请，等我处理

#### Scenario: joinType 非法

- **WHEN** 传入的 `joinType` 不在 `{0, 1}` 内
- **THEN** 返回错误码 `1001`（参数非法）
- **AND** 数据库中的原值保持不变

#### Scenario: joinType 缺省

- **WHEN** 未传 `joinType`
- **THEN** 参数校验失败，返回 HTTP 400

#### Scenario: 不可修改他人

- **WHEN** 接口不接收任何 `userId` 入参
- **THEN** 只能修改当前登录用户自己的 `join_type`
- **AND** 他人的设置不受影响

---

### Requirement: 加载黑名单

用户可查看自己已拉黑的用户列表（此前只能加黑、无法查看）。

#### Scenario: 列出已拉黑的用户

- **WHEN** 用户打开黑名单页并调用 `POST /api/contact/loadBlackList`
- **THEN** 返回 `user_contact` 中 `user_id=我`、`contact_type=0`、`status=4`（BLACKLIST）的全部行
- **AND** 每行带对方昵称（`contactName`）
- **AND** 按 `last_update_time` 倒序（最近拉黑的在最前）

#### Scenario: 「被拉黑」不出现在我的黑名单

- **WHEN** 某用户拉黑了我（我的行 `status=5` BLACKLIST_BE）
- **THEN** 该用户**不出现**在我的黑名单列表中
- **AND** 理由：解除他人对我的拉黑不在我的权限范围内

#### Scenario: 群组不出现在黑名单

- **WHEN** 用户曾对某群做过成员管理操作
- **THEN** 群组不进入黑名单列表（本能力只针对好友维度的拉黑）

#### Scenario: 空黑名单

- **WHEN** 用户从未拉黑过任何人
- **THEN** 返回空列表
- **AND** 前端展示空态提示

---

### Requirement: 解除黑名单

用户可把黑名单中的成员移出，移出后对方不再处于「被拉黑」状态。

#### Scenario: 解除成功

- **WHEN** 用户对黑名单中的某成员调用 `POST /api/contact/removeBlackList`
- **THEN** 返回 `code=0`
- **AND** 我指向他的关系行被**删除**（不再是 `status=4`）
- **AND** 他指向我的关系行（`status=5` BLACKLIST_BE）也被**删除**
- **AND** 双向联系人缓存被清理
- **AND** 该成员可重新向我发起好友申请（走正常 `applyAdd` 流程）

#### Scenario: 目标不在我的黑名单中

- **WHEN** 目标 `contactId` 与我之间不存在关系行
- **THEN** 返回错误码 `2401`（非好友关系）
- **AND** 不删除任何行

#### Scenario: 不能解除「别人对我的拉黑」

- **WHEN** 目标对应的是我的 `status=5`（BLACKLIST_BE，即对方拉黑了我）
- **THEN** 返回错误码 `2401`
- **AND** **双方关系行均不被删除**——不得替对方解除拉黑

#### Scenario: 对方同时拉黑了我时反向行保留

- **WHEN** 双方互相拉黑（我→他=4，他→我=4）
- **THEN** 我解除后：我→他的行被删除
- **AND** 他→我的行**保留**（那是他的拉黑记录，不归我处置）

#### Scenario: 重复解除幂等

- **WHEN** 对同一成员连续调用两次 `removeBlackList`
- **THEN** 第一次成功，第二次返回 `2401`
- **AND** 不产生异常、不误删其他行

#### Scenario: 不接受他人 userId

- **WHEN** 接口不接收任何 `userId` 入参
- **THEN** 只能操作当前登录用户自己的黑名单
- **AND** 无法解除他人黑名单中的成员

---

## REMOVED Requirements

无。既有 `POST /api/contact/addContact2BlackList`（加黑）语义与签名完全不变，本变更只补齐「查」与「解」两端。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 更新加我方式 | 1.1, 1.2, 2.1, 3.1 |
| C2 | ADDED: 加载黑名单 | 1.3, 1.4, 2.2, 3.2, 3.3 |
| C3 | ADDED: 解除黑名单 | 1.5, 1.6, 2.3, 3.4 |
