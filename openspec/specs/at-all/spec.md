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
- ⚠ **`atUserIds` 当前「只写不读」**（2026-10-12 取证订正）：全仓检索**无任何**按 `atUserIds`
  发通知或红点的代码；服务端仅将其落 `chat_message.at_user_ids` 并用于搜索过滤
  `like %id%`，**不解析消息正文**；前端也不读收到的消息。
  早前 spec 与 `MessageSend.vue` 注释中「服务端用于红点提醒」「跨端契约」的表述**均不成立**，
  已订正。若日后要实现「@ 我」红点，须同时复核 `atMentionCore` 的提取口径与下述正则取舍。

---

### Requirement: @ 提及 ID 形态精确化（2026-10-12，遗留 #19）

从消息正文提取被 @ 的用户 ID 时，匹配形态必须**精确等于真实 userId 的取值集合**，
即 `U` 后接数字，或系统账号 `Urobot`；普通英文单词不得被误判为提及。

#### Scenario: 真实 userId 与系统账号均可提取

- **WHEN** 正文含 `@U01234567890` 与 `@Urobot`
- **THEN** 提取结果为 `["U01234567890", "Urobot"]`（去重，保持首次出现顺序）

#### Scenario: 普通英文单词不再误命中

- **WHEN** 正文含 `@Ubuntu` / `@User` / `@Usernames`
- **THEN** 提取结果为空数组
- **AND** `extraData` 不含 `atUserIds`，请求字段 `atUserIds` 为 `null`

#### Scenario: ID 后紧跟单词字符不算完整 ID

- **WHEN** 正文含 `@U01234567890abc`、`@U01234567890_x`、`@UrobotFoo`
- **THEN** 均不提取（`(?![\w])` 边界生效，非前缀匹配）

#### Scenario: 已选中文本后接 @ 的合法提及不得漏掉

- **WHEN** 用户在输入框选中「你好」后点 @ 面板，产出 `你好@U01234567890 `
- **THEN** 提取结果为 `["U01234567890"]`

> **⚠ 有意接受的残留**：正则**不加左词边界**，故邮箱形态 `a@U123.com` 仍会提取出 `U123`。
> 取舍理由：加左边界会漏掉上一条「选中文本后接 @」的**正常**路径。该假阳性当前**无可观测后果**
> （`atUserIds` 只写不读）。**若日后基于 `atUserIds` 实现提醒，正确做法不是加左边界，
> 而是改为按真实群成员白名单过滤**——此点已由单元测试显式锁定，防止被后人当 bug「顺手修掉」。

> **根因不在正则而在靓号**：靓号是唯一能把任意字符写进 `user_info.user_id` 的入口，
> 此前**零格式校验**。字符集无上界时正则不敢收紧。现已约束靓号须为纯数字，
> 与 `StringTools.getUserId()`（`U` + 11 位数字）对齐，使 userId 集合收敛为
> **`U`+数字 ∪ `Urobot`** —— 正则即该集合的精确表达。
> 保留 `Urobot` 是必需的：`Constants.ROBOT_UID` 是真实系统账号（有头像文件、
> 有以它为 `contactId` 的会话），纯 `\d+` 会把它挡在 @ 之外。

---

### Requirement: 靓号字符集约束（2026-10-12，遗留 #19）

管理员创建的靓号，其 `userId` 片段必须为纯数字，与系统自动生成的 userId 形态一致。

#### Scenario: 合法纯数字靓号可保存

- **WHEN** 管理员保存 `userId = "888888"` 的靓号
- **THEN** 保存成功，落库 `user_id = "U888888"`

#### Scenario: 含非数字字符的靓号被拒

- **WHEN** 管理员保存 `userId` 为 `"ABC"` / `"88-88"` / `"88 88"` / `""` / `null`
- **THEN** 抛 `CODE_1001`
- **AND** **不落库**（校验置于所有唯一性查询与写库动作之前）

#### Scenario: 编辑路径同受约束

- **WHEN** 先建合法靓号、再编辑其 `userId` 为 `"ABC"`
- **THEN** 抛 `CODE_1001`，不执行 `updateById`

---

### Requirement: 靓号保存端点接收 DTO 而非持久化对象（2026-10-13，遗留 #31）

管理端保存靓号的写入口必须接收 `entity.dto` 下的入参对象，**不得**直接接收 `entity.po` 下的对象。

**为什么这不是安全缺口、却仍要约束**（立项前已穷举后果链）：
该端点有 `checkAdmin = true` 守着，`saveAccount` 已有四重校验
（含上方「靓号字符集约束」），PO 上 4 个字段也都是管理员域内可改的数据，
**无跨用户越权**。真正的风险是**结构性的**：

> PO 直接绑定意味着「将来给这个 PO 加一个内部字段，它会**自动**变成可由外部提交的入参」，
> 而构建、测试、门禁都不会给出任何提示。

#### Scenario: 入参形状

- **WHEN** 审视 `AdminUserInfoBeautyController#saveBeautAccount` 的入参
- **THEN** 是 `entity.dto.UserInfoBeautySaveDTO`，**不是** `entity.po.UserInfoBeauty`
- **AND** 入参带 `@Valid`，使 DTO 上的约束注解真正生效
- **AND** 全仓 Controller 中「直接收 PO」的端点数为 **0**

#### Scenario: 必填校验在进入 Service 前生效

- **WHEN** `email` 为空 / `email` 格式非法 / `userId` 为空
- **THEN** 返回 `1001` + 提示，**不**进入 Service

#### Scenario: DTO → PO 的转换逐字段手写

- **WHEN** Controller 把 DTO 转成 PO 传给 Service
- **THEN** **逐字段** `setXxx`，**不用** `BeanUtils.copyProperties`
- **AND** 转换后的 PO 四字段与入参逐一相等
- **AND** `UserInfoBeautyService#saveAccount` 的入参**仍是** `UserInfoBeauty`（Service 签名不变）

#### Scenario: 新建 / 编辑语义不被 DTO 干扰

- **WHEN** `id` 为空或非空
- **THEN** DTO 层**不**报错（两种都是合法路径）
- **AND** 分流仍由 Service 的 `getId() == null` 负责，不在两层各写一遍

#### Scenario: status 可选

- **WHEN** `status` 未提交（新建场景的正常情况）
- **THEN** **不**报错

#### Scenario: 不引入 swagger 注解

- **WHEN** 本仓尚未引入 swagger 注解依赖
- **THEN** DTO **不加** `@Schema` / `@ApiModel`
- **AND** 字段以中文注释说明（AGENTS §3.6 要求「中文注释**或** `@Schema`」）

#### Scenario: 方法参数级约束不在单测中断言

- **WHEN** 在 `standaloneSetup` 下验证 `@NotNull Integer id` 这类**方法参数**上的约束
- **THEN** **不**做行为断言（`standaloneSetup` 不注册 `MethodValidationPostProcessor`，恒返回 200）
- **AND** 改用结构断言「注解确实声明在参数上」，行为验证交由真机
- **AND** **不**写出「因环境缺件而恒红」的用例