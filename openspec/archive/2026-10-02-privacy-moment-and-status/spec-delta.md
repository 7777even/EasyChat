# Spec Delta — 朋友圈可见范围 + 在线状态可见性 + 隐私设置统一页

- 关联 Tasks: 2026-10-02-privacy-moment-and-status/tasks.md
- 创建日期: 2026-10-02

## ADDED Requirements

### Requirement: 配置朋友圈可见范围

用户可配置「默认谁能看到我的朋友圈」，含公开 / 仅好友 / 仅自己 / 自定义白名单 / 黑名单五种，并维护自定义名单成员。

#### Scenario: 设置为公开 / 仅好友 / 仅自己

- **WHEN** 用户在隐私页选择 `momentVisibility` 为 0 / 1 / 2 并保存
- **THEN** 返回 `code=0`，`user_info.moment_visibility` 更新为对应值
- **AND** 自定义名单列不被本次请求清空（保持原值，便于切回）

#### Scenario: 设置为自定义白名单

- **WHEN** 用户选择 `momentVisibility=3` 并指定了至少一名好友
- **THEN** `moment_visible_list` 保存为 JSON 数组字符串，格式与单条动态的 `visible_list` 一致
- **AND** 该设置成为**之后发布**朋友圈的默认值

#### Scenario: 设置为黑名单

- **WHEN** 用户选择 `momentVisibility=4` 并指定了至少一名好友
- **THEN** `moment_invisible_list` 保存为 JSON 数组字符串
- **AND** 该设置成为之后发布朋友圈的默认值

#### Scenario: 白名单/黑名单为空被拒

- **WHEN** `momentVisibility=3` 但 `visibleList` 为空，或 `=4` 但 `invisibleList` 为空
- **THEN** 返回错误码 `1001`（参数非法），数据库保持原值
- **AND** 理由：空名单会让「白名单=谁都看不到」或「黑名单=谁都能看」，属用户误操作

#### Scenario: 名单含非好友被拒

- **WHEN** 名单中出现一个不是当前用户好友的 userId
- **THEN** 返回错误码 `1001`，不落库
- **AND** 一次查询批量校验（非逐个查询）

#### Scenario: 可见范围非法值

- **WHEN** `momentVisibility` 不在 `{0,1,2,3,4}` 内，或缺省
- **THEN** 返回错误码 `1001`（缺省则 HTTP 400）
- **AND** 数据库保持原值

#### Scenario: 名单超出长度上限

- **WHEN** 名单 JSON 序列化后超过 60000 字符
- **THEN** 返回错误码 `1001`，不落库

#### Scenario: 不可修改他人

- **WHEN** 接口不接收任何 `userId` 入参
- **THEN** 只能修改当前登录用户自己的设置

#### Scenario: 存量用户升级后行为不变

- **WHEN** 存量用户升级到含本列的版本且从未设置过
- **THEN** `moment_visibility` 默认为 `0`（公开），与改动前发布页默认值一致
- **AND** 已发布的历史动态**不**被追溯改写

---

### Requirement: 发布朋友圈采用用户级默认值

打开发布页时，可见范围以用户级设置为初值；用户可单条临时调整，调整**不回写**用户级设置。

#### Scenario: 继承用户级默认

- **WHEN** 用户打开发布页
- **THEN** 可见范围选中 `getUserInfo().momentVisibility`
- **AND** 若为 3 / 4，白/黑名单带入用户级设置中已选成员

#### Scenario: 单条临时覆盖

- **WHEN** 用户在发布页把默认的「公开」改成「仅自己」并发布
- **THEN** 该条动态按 `visibility=2` 存储与判定
- **AND** 用户级 `moment_visibility` **仍为 0**，下次发布继续默认「公开」

#### Scenario: 白/黑名单选人

- **WHEN** 用户在发布页选择「自定义白名单」或「黑名单」
- **THEN** 可调出联系人选择器指定成员（复用既有 `UserSelect` 组件）
- **AND** 未选任何人时**不允许发布**，前端拦截并提示

#### Scenario: 单条判定逻辑不受影响

- **WHEN** 任意用户浏览朋友圈
- **THEN** 可见性判定完全由该条动态自身的 `visibility` / `visible_list` / `invisible_list` 决定
- **AND** 用户级设置**不参与**判定（仅作发布默认值）

---

### Requirement: 在线状态可见性

用户可控制是否对好友展示自己的在线状态。关闭后立即生效，好友端已有的状态展示被抹除。

#### Scenario: 关闭后不再广播

- **WHEN** 用户关闭「展示在线状态」后发生上线 / 掉线 / 手动切换状态
- **THEN** 服务端**不向任何好友广播**该次状态变更
- **AND** 关闭期间好友端不会因该用户的状态变化而更新

#### Scenario: 关闭时立即抹除好友端已有状态

- **WHEN** 用户关闭「展示在线状态」
- **THEN** 服务端立即向所有**在线**好友推送 `ONLINE_STATUS_HIDDEN(27)` 帧
- **AND** 好友端收到后清除该联系人的在线状态展示（状态点消失），无需等待对方掉线

#### Scenario: 重新开启后立即广播当前状态

- **WHEN** 用户重新开启「展示在线状态」
- **THEN** 服务端立即广播一次当前状态（`ONLINE_STATUS(22)`）
- **AND** 好友端恢复看到该用户的在线状态

#### Scenario: 非法值

- **WHEN** `visible` 不在 `{0,1}` 内，或缺省
- **THEN** 返回错误码 `1001`（缺省则 HTTP 400），数据库保持原值

#### Scenario: 不可修改他人

- **WHEN** 接口不接收任何 `userId` 入参
- **THEN** 只能修改当前登录用户自己的设置

#### Scenario: 存量用户升级后行为不变

- **WHEN** 存量用户升级且从未设置过
- **THEN** `online_status_visible` 默认为 `1`（展示），与改动前「无条件广播」一致

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
- **AND** 既有帧 0–26 的结构与语义**完全不变**

#### Scenario: 客户端处理

- **WHEN** 好友端 `wsClient.js` 收到 case 27
- **THEN** 清除该联系人的在线状态映射并通知渲染层更新状态点
- **AND** 清除后不残留上一状态

---

### Requirement: 隐私设置统一页

四项隐私设置集中在同一个「隐私」页，单一入口、单一真源。

#### Scenario: 页面构成

- **WHEN** 用户进入「设置 → 隐私」
- **THEN** 同一页面内含四个区块：加我的方式、朋友圈可见范围、在线状态可见性、黑名单
- **AND** 读取一次 `getUserInfo` 即可回填前三项

#### Scenario: 旧入口迁移

- **WHEN** 用户访问旧路径 `/setting/userInfo` 或 `/setting/blacklist`
- **THEN** 重定向到 `/setting/privacy`，不出现 404 或空白页
- **AND** 旧书签与浏览器/路由历史栈保持可用

#### Scenario: 朋友权限从账号设置移除

- **WHEN** 用户查看「设置 → 账号设置」
- **THEN** 不再出现「朋友权限」项（已迁至隐私页）
- **AND** 「账号设置」的其余项（头像、昵称、密码、通知、主题、状态等）**行为不变**

#### Scenario: 黑名单并入隐私页

- **WHEN** 用户在隐私页查看黑名单区块
- **THEN** 列表、解除、空态、失败保留旧列表的行为与迁移前一致

---

## MODIFIED Requirements

### Requirement: 加载黑名单

出参与判定**不变**（`status=BLACKLIST(4)`、含昵称、倒序、只返回本人）。
唯一变更是**入口位置**：从独立页 `/setting/blacklist` 迁移到 `/setting/privacy` 内的区块。

---

## REMOVED Requirements

无。既有的 `MomentServiceImpl#canView` 判定逻辑、`/moment/publish` 契约、
`/userInfo/updateJoinType`、`/contact/loadBlackList`、`/contact/removeBlackList` 全部保留不变。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 配置朋友圈可见范围 | 1.1–1.4, 2.1–2.4, 3.1, 3.4 |
| C2 | ADDED: 发布朋友圈采用用户级默认值 | 2.5, 3.5, 3.6 |
| C3 | ADDED: 在线状态可见性 / 在线状态隐藏帧 | 1.5–1.7, 2.6–2.8, 3.7, 3.8 |
| C4 | ADDED: 隐私设置统一页 | 3.2, 3.3, 3.9, 4.2 |
| — | MODIFIED: 加载黑名单（入口迁移） | 3.2, 4.2 |
