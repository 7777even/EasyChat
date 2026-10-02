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

## ADDED Requirements — 朋友圈可见范围（用户级）

（2026-10-02 `privacy-moment-and-status` 追加。`user_info` 新增 4 列：
`moment_visibility` / `moment_visible_list` / `moment_invisible_list` / `online_status_visible`，
迁移 `easychat-migration-011-privacy-settings.sql`，**默认值与改动前一致 → 存量用户行为不变**。）

### Requirement: 配置朋友圈可见范围

用户可配置「默认谁能看到我的朋友圈」：公开 / 仅好友 / 仅自己 / 自定义白名单 / 黑名单。

#### Scenario: 设置为公开 / 仅好友 / 仅自己

- **WHEN** 用户在隐私页选择 `momentVisibility` 为 0 / 1 / 2 并保存
- **THEN** `user_info.moment_visibility` 更新为对应值
- **AND** **自定义名单列不被本次请求清空**（切回 3/4 时名单还在）

#### Scenario: 设置为自定义白名单 / 黑名单

- **WHEN** 选择 `momentVisibility=3` 并指定至少一名好友，或 `=4` 并指定至少一名好友
- **THEN** 对应名单列保存为 JSON 数组字符串（格式与既有 `moment.visible_list` 同构）
- **AND** 该设置成为**之后发布**朋友圈的默认值

#### Scenario: 白/黑名单为空被拒

- **WHEN** `=3` 但白名单为空，或 `=4` 但黑名单为空
- **THEN** 返回错误码 `1001`，数据库保持原值
- **AND** 理由：空名单会让「白名单=谁都看不到」或「黑名单=谁都能看」，属用户误操作

#### Scenario: 名单含非好友被拒

- **WHEN** 名单中出现一个不是当前用户好友的 userId
- **THEN** 返回错误码 `1001`，不落库
- **AND** 采用「一次查询取好友集合 + 子集断言」，非逐个查询

#### Scenario: 非法输入被拒

- **WHEN** `momentVisibility` 不在 `{0..4}`、名单为非法 JSON、或名单序列化后超 60000 字符
- **THEN** 均返回错误码 `1001`，不落库
- **AND** 缺省 `momentVisibility` 由 `@NotNull` 拦截返回 HTTP 400

#### Scenario: 不可修改他人

- **WHEN** 接口不接收任何 `userId` 入参
- **THEN** 只能修改当前登录用户自己的设置

---

### Requirement: 发布朋友圈采用用户级默认值

打开发布页时以用户级设置为初值；用户可单条临时调整，调整**不回写**用户级设置。

#### Scenario: 继承用户级默认

- **WHEN** 用户打开发布页
- **THEN** 可见范围选中 `getUserInfo().momentVisibility`
- **AND** 若为 3 / 4，带入用户级设置中已选成员

#### Scenario: 单条临时覆盖

- **WHEN** 用户把默认的「公开」改成「仅自己」并发布
- **THEN** 该条动态按 `visibility=2` 存储与判定
- **AND** 用户级 `moment_visibility` **不变**

#### Scenario: 白/黑名单选人

- **WHEN** 用户在发布页选择「自定义白名单」或「黑名单」
- **THEN** 可调出通用选人组件 `ContactPicker` 指定成员
- **AND** 未选任何人时**不允许发布**（前端拦截，后端亦 1001）

#### Scenario: 单条判定逻辑不受影响

- **WHEN** 任意用户浏览朋友圈
- **THEN** 可见性判定**完全**由该条动态自身的 `visibility` / `visible_list` / `invisible_list` 决定
- **AND** 用户级设置**不参与**判定（仅作发布默认值；改设置不追溯历史动态，与微信一致）

---

## ADDED Requirements — 在线状态可见性

### Requirement: 在线状态可见性开关

用户可控制是否对好友展示自己的在线状态。关闭后立即生效。

#### Scenario: 关闭后不再广播

- **WHEN** 用户关闭后发生上线 / 掉线 / 手动切换状态
- **THEN** 服务端**不向任何好友广播**该次状态变更
- **AND** 好友端不会因该用户的状态变化而更新

#### Scenario: 关闭时立即抹除好友端已有状态

- **WHEN** 用户关闭「展示在线状态」
- **THEN** 服务端立即向所有**在线**好友推送 `ONLINE_STATUS_HIDDEN(27)` 帧
- **AND** 好友端清除该联系人的在线状态展示，无需等待对方掉线

#### Scenario: 重新开启后立即广播当前状态

- **WHEN** 用户重新开启
- **THEN** 服务端立即广播一次当前状态（`ONLINE_STATUS(22)`）

#### Scenario: 查不到用户时按展示处理

- **WHEN** `user_info.online_status_visible` 为 NULL 或用户记录不存在
- **THEN** 按**展示**处理（不因查不到而误伤正常用户）

#### Scenario: 非法值与存量默认

- **WHEN** `visible` 不在 `{0,1}` → `1001`；缺省 → HTTP 400
- **AND** 存量用户默认 `1`（展示），与改动前「无条件广播」一致

---

### Requirement: 在线状态隐藏帧

新增 WS 帧 `ONLINE_STATUS_HIDDEN(27)`，用于通知好友端抹除某用户的在线状态展示。

#### Scenario: 帧结构

- **WHEN** 服务端推送该帧
- **THEN** `messageType=27`、`contactId` 为状态被隐藏的用户 id、`extendData = {"hidden": true}`
- **AND** 仅向在线好友推送

#### Scenario: 向后兼容

- **WHEN** 旧客户端收到 `messageType=27`
- **THEN** 走 `default` 分支忽略，**不崩溃**
- **AND** 既有帧 0–26 的结构与语义**完全不变**（帧号连续无空洞、无重复）

#### Scenario: 客户端处理

- **WHEN** 好友端 `wsClient.js` 收到 case 27 → 转发 `onlineStatusHidden`
- **THEN** `Contact.vue` 清除该联系人的在线状态映射并更新状态点
- **AND** 清除后不残留上一状态

---

## ADDED Requirements — 隐私设置统一页

### Requirement: 隐私设置统一页

四项隐私设置集中在同一个「隐私」页，单一入口、单一真源（ADR-003）。

#### Scenario: 页面构成

- **WHEN** 用户进入「设置 → 隐私」（`/setting/privacy`）
- **THEN** 同页内含四区块：加我的方式、朋友圈可见范围、在线状态可见性、黑名单
- **AND** 前三项由一次 `getUserInfo` 回填

#### Scenario: 旧入口重定向

- **WHEN** 访问旧路径 `/setting/userInfo` 或 `/setting/blacklist`
- **THEN** 重定向到 `/setting/privacy`，不出现 404 或空白页

#### Scenario: 通用选人组件

- **WHEN** 需要选人（白/黑名单）
- **THEN** 使用 `components/ContactPicker.vue`——**纯选择、无提交副作用**
- **AND** `views/chat/UserSelect.vue` 是**群成员专用**（硬编码标题 + 直调 `addOrRemoveGroupUser`），**不可复用**

#### Scenario: 账号设置与黑名单页清理

- **WHEN** 用户查看「账号设置」→ 不再出现「加我的方式」项，其余项行为不变
- **AND** 独立黑名单页文件已删除，逻辑并入隐私页，行为（列表/解除/空态/失败保留旧列表）不变

---

## 遗留

| 项 | 说明 |
|----|------|
| **WS 帧 27 端到端效果** | `wsClient.js` case 27 与 `Contact.vue` 订阅已实现、build 通过，但**关闭后好友端状态点是否立即消失**只有 GUI 验证覆盖（冒烟走 HTTP 未起 WS）。「漏加 case」已由 `verify_ws_frame_parity.mjs` 门禁阻断，但**渲染层效果仍需 GUI** |
| **`ChannelContextUtils` 2 个方法单测** | ✅ 已补 `ChannelContextUtilsOnlineStatusTest`（13 例：开关=0 不推帧 / 不查好友列表 / 只推在线好友 / 所有状态值均被拦截 / 查不到用户与 NULL 开关按展示 / 空 userId 提前返回 / 帧 27 结构与 `hidden:true` / 抹除帧不受开关影响 / 无好友不推帧 / 帧号 27≠22 / 开关不误伤消息投递），**已过 8 项变异检验** |
| **位置消息(25) / 语音消息(24) 端到端未接通** | 由 `verify_ws_frame_parity.mjs` 于 2026-10-03 首次运行时发现（**既有缺陷，非本次引入**）：DB 中 `message_type` 仅 1/2，0 条 24/25 记录 → 两功能从未被真实使用。三层断裂：① `ChatMessageServiceImpl` 落库白名单（仅 CHAT/GROUP_CREATE/ADD_FRIEND/MEDIA_CHAT）不含 24/25 → **不落库** ② `wsClient.js` 无 case 24/25 → **对端实时收不到** ③ `Chat.vue:136` 分发条件不含 → **即使有也不渲染**，且 `ChatMessageVoice.vue` 从未被 import（死组件）。修复需改服务端落库白名单（业务能力变更，L3），暂登记于门禁 `KNOWN_GAP` 保持技术债可见 |
| 前端 GUI 验证与截图 | 沙箱无 GUI，隐私页四区块 / 发布页 5 项选人 / 旧路由 redirect / `ContactPicker` 交互均待本机验证 |
| 黑名单分页 | 刻意不分页（与既有 `/contact/loadContact` 范式一致）；量级变大时再补 |
| 屏蔽某人（不看其朋友圈） | `moment_visibility` 体系已就位，缺的只是「用户级屏蔽名单」与 `canView` 判定联动（独立 L3） |
| 在线状态细分 | 微信有「离开/离开时长」，现有 `OnlineStatusEnum` 仅 在线/忙碌/离线 |
| 设置项信息架构 | 已合并为统一「隐私」页（ADR-003 落地），`/setting/userInfo` 现仅剩账号资料类项 |
