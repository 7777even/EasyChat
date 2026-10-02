# Spec Delta — 群入群审批闭环

- 关联 Tasks: 2026-10-02-group-join-approval/tasks.md
- 创建日期: 2026-10-02

## MODIFIED Requirements

### Requirement: 群二维码加入群聊

用户可凭群二维码 token 加入群聊。**加入行为必须受群的 `join_type` 管辖**（本条为修复：`joinByQrCode` 原先直接 `addContact` 入群，完全绕过 `join_type`）。

#### Scenario: 群允许直接加入（join_type=0）

- **WHEN** 用户凭有效二维码 token 调用 `POST /api/group/qrCode/join`
- **THEN** 直接入群，返回 `joinType=0`

#### Scenario: 群需管理员同意（join_type=1）

- **WHEN** 用户凭有效二维码 token 调用 `POST /api/group/qrCode/join`，且该群 `join_type=1`
- **THEN** **不入群**，改为在 `user_contact_apply` 落一条 `contact_type=1` 的待处理申请
- **AND** 向群主推送 `CONTACT_APPLY(4)` WS 帧
- **AND** 返回 `joinType=1`

#### Scenario: token 过期或无效

- **WHEN** 二维码 token 不存在或已过期（Redis TTL 7 天）
- **THEN** 返回错误码 `1001`，提示「二维码已过期或无效」
- **AND** 不产生任何申请单

#### Scenario: 已是群成员

- **WHEN** 调用方已是该群成员
- **THEN** 返回错误码 `1001`，提示「您已经是该群成员」

#### Scenario: 重复申请幂等

- **WHEN** 同一用户对同一群在待处理状态下再次扫码
- **THEN** 复用既有申请行并重置为待处理，**不新增行**
- **AND** 不重复推送 `CONTACT_APPLY` 帧

---

### Requirement: 群邀请链接加入群聊

用户可凭邀请链接 token 加入群聊。**加入行为必须受群的 `join_type` 管辖**（本条为修复：`joinByInvite` 原先同样直接 `addContact` 入群）。

#### Scenario: 群允许直接加入（join_type=0）

- **WHEN** 用户凭有效邀请 token 调用 `POST /api/group/invite/join`
- **THEN** 直接入群，返回 `joinType=0`

#### Scenario: 群需管理员同意（join_type=1）

- **WHEN** 用户凭有效邀请 token 调用 `POST /api/group/invite/join`，且该群 `join_type=1`
- **THEN** **不入群**，落 `contact_type=1` 待处理申请并推 `CONTACT_APPLY(4)` 帧给群主
- **AND** 返回 `joinType=1`

#### Scenario: token 过期或无效

- **WHEN** 邀请 token 不存在或已过期
- **THEN** 返回错误码 `1001`，提示「邀请链接已过期或无效」

---

### Requirement: 处理联系人申请

`dealWithApply` 的**审批人判定按 `contactType` 分流**（本条为放宽：群入群申请的审批人由「仅群主」扩为「群主或任一群管理员」；好友申请审批人不变）。

#### Scenario: 群管理员审批入群申请

- **WHEN** 群入群申请（`contactType=GROUP`）待处理，且操作人在该群 `role` 为管理员或群主
- **THEN** 允许处理：同意（`status=1`）则申请人真正入群；拒绝（`status=2`）则申请关闭

#### Scenario: 普通群成员无权审批

- **WHEN** 群入群申请待处理，且操作人在该群 `role` 为成员
- **THEN** 返回错误码 `2305`（无权执行此操作）

#### Scenario: 非群成员无权审批

- **WHEN** 群入群申请待处理，且操作人不在该群
- **THEN** 返回错误码 `2304`（已不在该群组）

#### Scenario: 好友申请审批人仍为被申请人本人

- **WHEN** 好友申请（`contactType=USER`）待处理，且操作人不是 `receive_user_id`
- **THEN** 返回错误码 `1001`
- **AND** 该约束不因群审批放宽而改变

#### Scenario: 并发重复审批被拦截

- **WHEN** 同一条申请被并发处理两次
- **THEN** 第二次因 `updateByParam(applyId + status=INIT)` 影响行数为 0 而返回错误码 `1001`
- **AND** 不产生重复入群

---

### Requirement: 加载联系人申请列表

申请列表对**群管理员可见本群的入群申请**（本条为扩展：原先仅按 `receive_user_id=我` 过滤，群管理员看不到任何入群申请）。

#### Scenario: 群主查看申请列表

- **WHEN** 群主调用 `POST /api/contact/loadApply`
- **THEN** 返回其作为 `receive_user_id` 的好友申请，**以及**其作为群主/管理员的群的入群申请

#### Scenario: 群管理员查看申请列表

- **WHEN** 群管理员调用 `POST /api/contact/loadApply`
- **THEN** 返回其作为 `receive_user_id` 的好友申请，**以及**其作为管理员的群的入群申请
- **AND** 每条群入群申请的 `contactName` 为**群名**（非申请者昵称）

#### Scenario: 普通成员不越权可见

- **WHEN** 普通群成员调用 `POST /api/contact/loadApply`
- **THEN** 该群对其群的入群申请**不出现**在结果中

#### Scenario: 分页总数与列表同源

- **WHEN** 列表按 `pageNo` 分页
- **THEN** 返回的 `total` 与实际可见集合一致（`selectList` 与 `selectCount` 共用同一查询条件，不允许分页后内存二次过滤）

#### Scenario: 申请红点与列表一致

- **WHEN** 用户重连触发 WS INIT 帧
- **THEN** `extendData.applyCount` 的统计口径与 `loadApply` 的可见口径**完全一致**（群管理员的本群入群申请计入红点）

---

## ADDED Requirements

### Requirement: 客户端提供加入群聊入口

客户端提供「扫码入群 / 邀请链接入群」入口（本次新增：两个 join 端点此前**没有任何前端调用方**，被邀请者只能走「搜索群名→申请」的唯一路径）。

#### Scenario: 通过群二维码加入

- **WHEN** 用户在通讯录页点击「加入群聊」，选择「群二维码」并输入有效 token
- **THEN** 调用 `POST /api/group/qrCode/join`
- **AND** `joinType=0` 时提示「已加入该群聊」；`joinType=1` 时提示「已提交入群申请，等待群主或管理员同意」

#### Scenario: 通过邀请链接加入

- **WHEN** 用户选择「邀请链接」并输入有效 token
- **THEN** 调用 `POST /api/group/invite/join`
- **AND** 提示文案与二维码路径一致（按 `joinType` 分流）

#### Scenario: token 无效时提示错误

- **WHEN** 后端返回 `code=1001`
- **THEN** 复用统一错误提示（「二维码已过期或无效」/「邀请链接已过期或无效」），对话框不关闭
- **AND** **不**误报为「已加入」

#### Scenario: 申请列表区分类型

- **WHEN** 申请列表中同时存在好友申请与入群申请
- **THEN** 入群申请条目标注「入群申请」并展示群名
- **AND** 好友申请条目保持原有展示

---

## REMOVED Requirements

无。`group_info.join_type`（0 直接加入 / 1 管理员同意后加入）的既有语义完全保留，只是从「仅搜索路径生效」修正为「所有入群路径均生效」。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | MODIFIED: 群二维码加入群聊 / 群邀请链接加入群聊 | 1.1, 2.1, 2.2, 2.3 |
| C2 | MODIFIED: 群二维码加入群聊（joinType 出参）/ 群邀请链接加入群聊 | 2.1, 2.2, 2.3, 3.1 |
| C3 | MODIFIED: 处理联系人申请 / 加载联系人申请列表 | 1.2, 1.3, 1.4, 2.4, 3.2 |
| C4 | ADDED: 客户端提供加入群聊入口 | 3.3, 3.4, 3.5 |
