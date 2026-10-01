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

---

### Requirement: @所有人消息样式

@所有人的消息显示特殊样式。

#### Scenario: @所有人消息显示

- **WHEN** 用户收到@所有人消息
- **THEN** 消息显示特殊样式（红色高亮）
- **AND** 消息内容前显示「@所有人」

---

## 实现落点（归档时补记）

- @按钮与选择面板：`easychat-front/src/renderer/src/views/chat/MessageSend.vue`
  - 仅群聊渲染（`isGroupChat`）；成员与我的角色按 `getGroupInfo4Chat` 懒加载，同群缓存、切会话重置
  - 「@所有人」仅 `user_contact.role ∈ {0 群主, 1 管理员}` 可见可点（`canAtAll`），逻辑层二次校验
  - @文本插入光标位置而非文末
- 消息样式：`easychat-front/src/renderer/src/views/chat/ChatMessage.vue`
  - `isAtAll`：`extraData.atAll` 或正文含「@所有人」双判
  - `.at-all-message` 气泡描边 + `.at-all-tag` 标记红色加粗；撤回/管理员删除态不套用
- 主题变量：`easychat-front/src/renderer/src/assets/base.scss` 新增 `--ec-at-all`（浅色 + `html.dark` 成对定义）
- 契约：无新增接口。标记走既有 `POST /chat/sendMessage` 的 `extraData` JSON（`atAll` 字段），随既有 WS 帧透传
- 服务端权限校验：**本期未实现**，见下方「已知边界」

## 已知边界

- 权限仅在客户端生效：普通成员手工构造请求并塞入 `extraData.atAll` 仍可使消息显示 @所有人 样式。
  服务端侧鉴权（`saveMessage` 校验发送者 role）需单独 Change（L4，涉权限语义）。
- 本地 SQLite `chat_message` 无 `extra_data` 列，`extraData` 不落本地库；
  重启后读取历史消息时靠正文含「@所有人」兜底判定，样式仍正确。
- 未实现服务端 @所有人 触发的红点提醒（现有 `atUserIds` 机制面向单个成员）。