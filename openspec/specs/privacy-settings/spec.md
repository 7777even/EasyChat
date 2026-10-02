# Spec — 隐私设置

## MODIFIED Requirements

### Requirement: 加载黑名单

用户可查看自己已拉黑的用户列表（此前只能加黑、无法查看，`UserDetail.vue` 点一次即永久无法退出）。

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

- **WHEN** 查询条件限定 `contact_type=0`（好友维度）
- **THEN** 群组不进入黑名单列表（本能力只针对好友维度的拉黑）

#### Scenario: 空黑名单

- **WHEN** 用户从未拉黑过任何人
- **THEN** 返回空列表（`code=0`），不抛错
- **AND** 前端展示空态提示

#### Scenario: 只返回自己的黑名单

- **WHEN** 甲拉黑了乙，乙同时拉黑了丙
- **THEN** 甲的列表含乙、不含丙；乙的列表含丙
- **AND** 接口不接受任何 `userId` 入参

---

## ADDED Requirements

### Requirement: 更新加我方式

用户可自助修改「加我的方式」，修改后对**之后发起**的好友申请立即生效。

#### Scenario: 切换为「直接加入」

- **WHEN** 用户在账号设置把朋友权限选为「直接加入」并保存
- **THEN** 返回 `code=0`，`user_info.join_type` 更新为 `0`
- **AND** 之后他人申请加我时**无需审批直接成为好友**（`applyAdd` 直读 DB，无缓存需失效）

#### Scenario: 切换为「加我时需验证」

- **WHEN** 用户选为「加我时需验证」并保存
- **THEN** `user_info.join_type` 更新为 `1`
- **AND** 之后他人申请加我时落一条待处理申请，等我处理

#### Scenario: 校验与落库

- **WHEN** `joinType` 不在 `{0, 1}` 内（含 null）
- **THEN** 返回错误码 `1001`（参数非法），数据库原值保持不变
- **AND** 实现只写 `join_type` 一列，**不得**覆盖昵称 / 密码 / 状态 / 性别

#### Scenario: 用户不存在

- **WHEN** 目标用户不存在
- **THEN** 返回错误码 `2101`（用户不存在），不落库

#### Scenario: 不可修改他人

- **WHEN** 接口不接收任何 `userId` 入参
- **THEN** 只能修改当前登录用户自己的 `join_type`

#### Scenario: 界面失败回滚

- **WHEN** 接口返回失败
- **THEN** 前端把单选框回滚到修改前的值，不留下「界面显示已保存、实际未生效」的错位

#### Scenario: 历史 null 值按保守项显示

- **WHEN** 存量 `join_type` 为 NULL
- **THEN** 前端选中更保守的「加我时需验证」并在界面提示
- **AND** 后端**不**主动改写存量 NULL（`applyAdd` 对 null 已走「需审批」分支，安全方向）

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
- **THEN** 返回错误码 `2401`（非好友关系），不删除任何行

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

---

### Requirement: 拉黑陌生人不再静默失效

**本条为缺陷修复**：拉黑此前走 `updateByUserIdAndContactId`，对「搜索到的陌生人」这种尚无 `user_contact` 行的情况是 no-op，界面上看着加了黑、实际完全没加。

#### Scenario: 拉黑从未成为好友的陌生人

- **WHEN** 用户对搜索到、从未成为好友的用户执行拉黑
- **THEN** 关系行被**创建**且 `status=4`（我拉黑他）
- **AND** 反向行被创建且 `status=5` BLACKLIST_BE
- **AND** 该用户随即出现在我的黑名单列表中

#### Scenario: 拉黑已有好友

- **WHEN** 用户拉黑一位已是好友（`status=1`）的用户
- **THEN** 行被**更新**为 `status=4`，双向缓存清理
- **AND** 行为与「拉黑陌生人」在最终状态上一致

#### Scenario: 删除好友语义不受波及

- **WHEN** 用户执行「删除好友」（`DEL`）
- **THEN** 仍走 `updateByUserIdAndContactId`，**不**改用 upsert
- **AND** 不得为「删除陌生人」凭空插入 `status=2` 的行

---

## 客户端行为

| 能力 | 落点 |
|------|------|
| 朋友权限可编辑 | `views/setting/UserInfo.vue`，`el-radio-group` + 乐观更新/失败回滚 |
| 黑名单管理页 | `views/setting/Blacklist.vue`（列表 + 拉黑时间 + 解除 + 空态），菜单与路由 `/setting/blacklist` |
| 加黑出路口提示 | `views/contact/UserDetail.vue` 加黑成功后提示「可在设置 → 黑名单中解除」 |

---

## 门禁与验证资产

| 资产 | 覆盖 |
|------|------|
| `UserContactBlacklistTest`（9 例） | 列表查询条件精确性（含「被拉黑」不入列）、空列表、解除双向删行与缓存清理、**安全红线**（`status=5` 不可解除）、双向拉黑时反向行保留、幂等、不触碰第三方行 |
| `UserInfoServiceImplTest` 新增 6 例 | join_type 写 0/1、只写一列、非法值/值为 null 拒绝且不落库、用户不存在 `2101` |
| `UserContactServiceImplTest` 修正 + 新增 | `removeUserContact_blacklist` 改断言 upsert 语义；新增 `removeUserContact_delStillUsesUpdate` 护栏 DEL 分支未被波及 |
| `scripts/smoke/smoke_blacklist.py`（39 断言） | 端到端：加我方式往返 + 非法值 + **改完立即对新申请生效**、拉黑双向生效、列表含昵称且不含「被拉黑」、只返回自己的、解除双向删行 + 可重新申请 + 幂等 2401、安全红线、双向拉黑反向行保留、群组不入列、自清理 |

---

## 遗留（归 2②-B，L4）

| 项 | 说明 |
|----|------|
| **朋友圈可见范围（用户级）** | 需 `user_info` 新增列（如 `moment_visibility` + 白/黑名单 JSON），且需与既有单条动态级 `moment.visibility`/`visible_list`/`invisible_list` 打通 |
| **在线状态可见性** | 需 `user_info` 新增开关列；`user_status` 已有但无可见性控制 |
| 设置项信息架构 | 「朋友权限」在账号设置、「黑名单」独立页，未合并为统一「隐私」页（ADR-002 已说明理由：2②-B 落地时一并评估） |
| 黑名单分页 | 刻意不分页（ADR-003），与既有 `/contact/loadContact` 范式一致；黑名单量级变大时再补 |
| 前端 GUI 验证与截图 | 沙箱无 GUI，需本机 `npm run dev` 手动验证三项交互并补截图 |
