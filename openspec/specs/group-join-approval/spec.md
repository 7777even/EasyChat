# Spec — 群入群审批

## MODIFIED Requirements

### Requirement: 群二维码加入群聊

用户可凭群二维码 token 加入群聊。**加入行为必须受群的 `join_type` 管辖**（本条为修复：`joinByQrCode` 原先直接 `addContact` 入群，完全绕过 `join_type`）。

#### Scenario: 群允许直接加入（join_type=0）

- **WHEN** 用户凭有效二维码 token 调用 `POST /api/group/qrCode/join`
- **THEN** 直接入群，返回 `joinType=0`
- **AND** 不产生任何申请单

#### Scenario: 群需管理员同意（join_type=1）

- **WHEN** 用户凭有效二维码 token 调用 `POST /api/group/qrCode/join`，且该群 `join_type=1`
- **THEN** **不入群**，改为在 `user_contact_apply` 落一条 `contact_type=1` 的待处理申请
- **AND** 申请附言标注来源为「通过群二维码申请加入」
- **AND** 向群主推送 `CONTACT_APPLY(4)` WS 帧
- **AND** 返回 `joinType=1`

#### Scenario: token 过期或无效

- **WHEN** 二维码 token 不存在或已过期（Redis TTL 7 天）
- **THEN** 返回错误码 `1001`，提示「二维码已过期或无效」
- **AND** 不产生任何申请单、不入群

#### Scenario: 群不存在

- **WHEN** token 有效但对应群已解散或被删
- **THEN** 返回错误码 `1001`，提示「群组不存在」
- **AND** 不入群

#### Scenario: 已是群成员

- **WHEN** 调用方已是该群成员
- **THEN** 返回错误码 `1001`，提示「您已经是该群成员」
- **AND** 不产生申请单

#### Scenario: 重复申请幂等

- **WHEN** 同一用户对同一群在待处理状态下再次扫码
- **THEN** 复用既有申请行并重置为待处理，**不新增行**
- **AND** 不重复推送 `CONTACT_APPLY` 帧
- **AND** 仍返回 `joinType=1`

---

### Requirement: 群邀请链接加入群聊

用户可凭邀请链接 token 加入群聊。**加入行为必须受群的 `join_type` 管辖**（本条为修复：`joinByInvite` 原先同样直接 `addContact` 入群）。

#### Scenario: 群允许直接加入（join_type=0）

- **WHEN** 用户凭有效邀请 token 调用 `POST /api/group/invite/join`
- **THEN** 直接入群，返回 `joinType=0`

#### Scenario: 群需管理员同意（join_type=1）

- **WHEN** 用户凭有效邀请 token 调用 `POST /api/group/invite/join`，且该群 `join_type=1`
- **THEN** **不入群**，落 `contact_type=1` 待处理申请，申请附言标注来源为「通过群邀请链接申请加入」
- **AND** 向群主推送 `CONTACT_APPLY(4)` WS 帧
- **AND** 返回 `joinType=1`

#### Scenario: token 过期或无效

- **WHEN** 邀请 token 不存在或已过期
- **THEN** 返回错误码 `1001`，提示「邀请链接已过期或无效」
- **AND** 不入群

#### Scenario: 两条路径语义对称

- **WHEN** 同一群在 `join_type=1` 下分别被二维码与邀请链接命中
- **THEN** 行为完全一致：均不入群、均落申请单、均返回 `joinType=1`
- **AND** 唯一差异仅为申请附言中的来源标注

---

### Requirement: 处理联系人申请

`dealWithApply` 的**审批人判定按 `contactType` 分流**（本条为放宽：群入群申请的审批人由「仅群主」扩为「群主或任一群管理员」；好友申请审批人不变）。

#### Scenario: 群管理员审批入群申请

- **WHEN** 群入群申请（`contactType=GROUP`）待处理，且操作人在该群 `role` 为管理员或群主
- **THEN** 允许处理：同意（`status=1`）则申请人真正入群；拒绝（`status=2`）则申请关闭

#### Scenario: 普通群成员无权审批

- **WHEN** 群入群申请待处理，且操作人在该群 `role` 为成员
- **THEN** 返回错误码 `2305`（无权执行此操作）
- **AND** 申请单状态不变、申请人未入群

#### Scenario: 非群成员无权审批

- **WHEN** 群入群申请待处理，且操作人不在该群
- **THEN** 返回错误码 `2304`（已不在该群组）

#### Scenario: 好友申请审批人仍为被申请人本人

- **WHEN** 好友申请（`contactType=USER`）待处理，且操作人不是 `receive_user_id`
- **THEN** 返回错误码 `1001`
- **AND** 该约束不因群审批放宽而改变（群管理员**无权**处理他人好友申请）
- **AND** 好友申请分支**不**触发任何群角色校验

#### Scenario: 拉黑入群申请不入群

- **WHEN** 审批人处理群入群申请并选择拉黑（`status=3`）
- **THEN** 写入 `BLACKLIST_BE_FIRST` 关系
- **AND** 申请人**不**入群

#### Scenario: 并发重复审批被拦截

- **WHEN** 同一条申请被并发处理两次
- **THEN** 第二次因 `updateByParam(applyId + status=INIT)` 影响行数为 0 而返回错误码 `1001`
- **AND** 不产生重复入群

#### Scenario: 申请不存在或状态非法

- **WHEN** `applyId` 不存在，或传入的状态为 `INIT`（待处理）
- **THEN** 返回错误码 `1001`

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
- **THEN** 该群的入群申请**不出现**在结果中

#### Scenario: 分页总数与列表同源

- **WHEN** 列表按 `pageNo` 分页
- **THEN** 返回的 `total` 与实际可见集合一致（`selectList` 与 `selectCount` 共用同一查询条件，不允许分页后内存二次过滤）

#### Scenario: 申请红点与列表一致

- **WHEN** 用户重连触发 WS INIT 帧
- **THEN** `extendData.applyCount` 的统计口径与 `loadApply` 的可见口径**完全一致**（群管理员的本群入群申请计入红点）

#### Scenario: 列表含全部状态（既有行为）

- **WHEN** 申请已被处理（同意/拒绝/拉黑）
- **THEN** 该申请仍出现在列表中，由 `status` 字段与前端的 `status == 0` 判断区分待处理
- **AND** 本行为为既有设计，本变更未改动

---

## ADDED Requirements

### Requirement: 客户端提供加入群聊入口

客户端提供「扫码入群 / 邀请链接入群」入口（本次新增：两个 join 端点此前**没有任何前端调用方**，被邀请者只能走「搜索群名→申请」的唯一路径）。

#### Scenario: 入口位置

- **WHEN** 用户进入通讯录页
- **THEN** 搜索框旁存在「加入群聊」入口
- **AND** 点击后弹出对话框，提供「群二维码」与「邀请链接」两个页签，各含一个 token 输入框

#### Scenario: 通过群二维码加入

- **WHEN** 用户在「群二维码」页签输入有效 token 并确认
- **THEN** 调用 `POST /api/group/qrCode/join`
- **AND** `joinType=0` 时提示「已加入该群聊」并刷新群列表
- **AND** `joinType=1` 时提示「已提交入群申请，等待群主或管理员同意」，群列表不变

#### Scenario: 通过邀请链接加入

- **WHEN** 用户在「邀请链接」页签输入有效 token 并确认
- **THEN** 调用 `POST /api/group/invite/join`
- **AND** 提示文案与二维码路径一致（按 `joinType` 分流）

#### Scenario: token 为空

- **WHEN** 用户未输入 token 直接确认
- **THEN** 前端拦截并提示「请输入 token」
- **AND** 不发起请求

#### Scenario: token 无效时提示错误且不误报

- **WHEN** 后端返回 `code=1001`（token 过期/已是成员/群不存在）
- **THEN** 复用统一错误提示
- **AND** 对话框**不**关闭，便于用户就地改正后重试
- **AND** **不**误报为「已加入该群聊」

#### Scenario: 对话框内提示审批规则

- **WHEN** 用户打开对话框
- **THEN** 界面明示「若该群设置了需管理员同意后加入，提交后会进入待审批」
- **AND** 审批人表述为「群主或管理员」

#### Scenario: 申请列表区分类型

- **WHEN** 申请列表中同时存在好友申请与入群申请
- **THEN** 入群申请条目标注「入群申请」，好友申请标注「好友」
- **AND** 入群申请的 `contactName` 展示群名

---

## 门禁与验证资产

| 资产 | 覆盖 |
|------|------|
| `UserContactApplyServiceImplTest`（10 例） | 审批权限按 contactType 分流；群主/管理员可审、成员 2305、非成员 2304、好友申请未被放宽、并发守卫、拉黑不入群 |
| `GroupJoinApplyTest`（7 例） | 两条入群路径必须委托 `applyAdd`；token 失效/群不存在/已是成员均不入群；邀请与二维码对称 |
| `GroupJoinJoinTypeContractTest`（3 例） | `joinType` 出参契约：0/1 分别对应直接加入/需审批 |
| `scripts/smoke/smoke_group_join_approval.py`（48 断言） | 端到端闭环：join_type 双分支、幂等、申请单落库、群主/管理员可见可审、普通成员不可见不可审、好友申请负面护栏、自清理 |

## 遗留（不阻塞验收）

| 项 | 说明 |
|----|------|
| 前端 GUI 交互与截图证据 | 沙箱无 GUI，需本机 `npm run dev` 手动验证入口、两种提示、徽标显示 |
| WS 申请红点的管理员可见性 | 两处调用点共用同一 XML 条件，代码同源已对账；实际红点变化待 GUI 验证 |
| `loadApply` 不按状态过滤 | 既有行为，本变更未改动；已处理申请会持续累积在列表中 |
| 可复现测试夹具（多账号） | 本机仅 2 个账号可用统一口令，冒烟被迫拆两群 + SQL 造单；建议建立专用 smoke 账号集 |
