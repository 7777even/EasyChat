# at-all — @所有人

## ADDED Requirements

### Requirement: @所有人

群主/管理员可在群聊中@所有人。

#### Scenario: @所有人

- **WHEN** 群主/管理员在群聊中点击@按钮并选择「@所有人」
- **THEN** 发送@所有人消息
- **AND** 所有群成员收到消息

#### Scenario: 普通群成员@所有人

- **WHEN** 普通群成员在群聊中点击@按钮
- **THEN** 不显示「@所有人」选项

#### Scenario: 普通群成员手工构造请求

- **WHEN** 普通群成员（role=2）绕过前端，直接调用发送接口并携带 `extraData={"atAll":true}`
- **THEN** 服务端返回 `CODE_2305`（无权执行此操作）
- **AND** 消息不落库、不推送给任何群成员

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

> 权限是**发送侧**的正确性前提；「正文含 @所有人 也认」是**有意的兜底**
> （兼容草稿恢复）。本条只做**叠加**权限判定，**不删除**该兜底。

---

### Requirement: 群成员搜索与显示口径一致

@ 面板中能在列表里看到的成员，必须能被搜到。搜索同时匹配成员的**昵称**与 **userId**。

#### Scenario: 按昵称搜索

- **WHEN** 在 @ 面板的搜索框输入某个成员昵称的一部分（大小写不限）
- **THEN** 该成员出现在结果列表中

#### Scenario: 按 userId 搜索未设昵称的成员

- **WHEN** 某成员 `contactName` 为空（列表中显示为其 userId）
- **THEN** 用该 userId 搜索时**能命中该成员**
- **AND** 与该成员已设昵称时的搜索体验**一致**

> 2026-10-05 前：过滤只用 `contactName`，而显示用 `contactName || userId`，
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

> 2026-10-05 前：`buildExtraData` 不去重、`buildAtUserIds` 去重，且**各自复制了同一个正则**，
> 两处口径已分叉。本条消除分叉隐患。
> **正则本身不变**（`/@(U[A-Za-z0-9]+)/g`），其误命中属跨端契约变更，见「已知边界」。

---

### Requirement: @所有人消息样式

@所有人的消息显示特殊样式。

#### Scenario: @所有人消息显示

- **WHEN** 用户收到@所有人消息
- **THEN** 消息显示特殊样式（红色高亮）
- **AND** 消息内容前显示「@所有人」

---

## 实现落点（归档时补记）

- **判定纯核心（2026-10-05 起）**：`easychat-front/src/renderer/src/utils/atMentionCore.mjs`
  - 导出 `canAtAll` / `roleText` / `filterMembers` / `spliceAtText` / `extractAtUserIds` /
    `buildAtUserIdsField` / `buildExtraData` / `GROUP_CONTACT_TYPE` 及三个常量
  - **不 import electron / window / axios / store** —— 故 node 门禁与 vitest 均可直接加载
  - 门禁 `scripts/verify/verify_at_mention_core.mjs`（19 项）+
    变异 `mutation_at_mention_core.mjs`（9/9），含**三条结构用例**防止组件把判定内联回去
- @按钮与选择面板：`easychat-front/src/renderer/src/views/chat/MessageSend.vue`
  - **只保留副作用**（`nextTick` 光标复位、`proxy` 提示、成员拉取、面板开关），
    判定一律委托纯核心；**内联判定即视为缺陷复发**
  - 仅群聊渲染（`isGroupChat`）；成员与我的角色按 `getGroupInfo4Chat` 懒加载，同群缓存、切会话重置
  - 「@所有人」仅 `user_contact.role ∈ {0 群主, 1 管理员}` 可见可点（`canAtAll`），逻辑层二次校验
  - @文本插入光标位置而非文末（`spliceAtText` 算字符串，组件做 DOM 光标复位）
  - 必须把 `myGroupRole` 传入 `buildExtraData` —— 否则 `atAll` 会因 `role=undefined` 恒判无权限
- 消息样式：`easychat-front/src/renderer/src/views/chat/ChatMessage.vue`
  - `isAtAll`：`extraData.atAll` 或正文含「@所有人」双判
  - `.at-all-message` 气泡描边 + `.at-all-tag` 标记红色加粗；撤回/管理员删除态不套用
- 主题变量：`easychat-front/src/renderer/src/assets/base.scss` 新增 `--ec-at-all`（浅色 + `html.dark` 成对定义）
- 契约：无新增接口。标记走既有 `POST /chat/sendMessage` 的 `extraData` JSON（`atAll` 字段），随既有 WS 帧透传
- **服务端权限校验（2026-10-03 起已实现）**：`ChatMessageServiceImpl#saveMessage` 在群聊分支内，
  当 `ExtraDataTools.isAtAll(extraData)` 为真时调用
  `groupInfoService.checkGroupRole(userId, groupId, GroupMemberRoleEnum.ADMIN)`；
  权限不足抛 `CODE_2305`，非成员抛 `CODE_2304`。校验置于 `ROBOT_UID` 判断之内（机器人自回复不触发）
  且仅在群聊分支触发（单聊的 `atAll` 不受影响）。

## 已知边界

- ~~权限仅在客户端生效~~ → **2026-10-03 已下沉服务端**（见上）。普通成员手工构造
  `extraData.atAll=true` 会得到 `CODE_2305`，消息不落库、不推送。
- `ExtraDataTools.isAtAll` **只认顶层布尔** `{"atAll":true}`：`"1"` / `1` / 嵌套字段一律视为非 @所有人，
  避免 fastjson 宽松转换导致普通成员被误拦。
- 本地 SQLite `chat_message` 无 `extra_data` 列，`extraData` 不落本地库；
  重启后读取历史消息时靠正文含「@所有人」兜底判定，样式仍正确。
- 未实现服务端 @所有人 触发的红点提醒（现有 `atUserIds` 机制面向单个成员）。
- **@ 提及正则在客户端存在误命中**（2026-10-05 登记，未修）：客户端用
  `/@(U[A-Za-z0-9]+)/g` 从正文提取被 @ 的用户 ID，故 `@Ubuntu` 会被提取为 `buntu`。
  该正则格式是**跨端契约**（服务端 `ExtraDataTools.isAtAll` 与 `MessageSendDto.atUserIds`
  消费同一形态），收紧属跨端契约变更，须前后端协同 + 存量数据评估。
  当前影响面有限：误提取出的 id 只会让**恰好存在该 id 的用户**收到红点提醒。
  落点：`docs/system-facts.md` §14 遗留 #19。