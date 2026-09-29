# Spec Delta — 管理端消息删除位

- 关联 Tasks: 2026-09-29-admin-message-delete/tasks.md
- 创建日期: 2026-09-29

> 格式对齐 `openspec/specs/content-moderation/spec.md`。
> 与 proposal Capabilities C1–C3 一一对应。
> 本 delta 扩展既有 `content-moderation` capability（举报处置子能力）：把「聊天消息无删除状态位故仅记录」升级为真实逻辑删除 + WS 实时同步。

## ADDED Requirements

### Requirement: 聊天消息逻辑删除与证据保留（message-delete-flag）— C1

系统 SHALL 通过 `chat_message.delete_flag`（0=存活，非0=删除时间戳ms）为聊天消息提供逻辑删除位：用户侧所有查询路径无条件过滤已删消息，管理端证据读取保留原文。

#### Scenario: 举报处置真实删除

- **WHEN** 管理员对 `reportType=3` 的举报执行 `dealReport(handleAction=1 删除内容)`
- **THEN** 目标消息 `delete_flag` 置为处置时刻 ms，`handle_note` 记录删除动作，写入 `report_audit_log`
- **AND** 同一消息重复处置（已有删除位）幂等：不改写、不重复推送、处置照常成功

#### Scenario: 用户侧全路径不可见

- **WHEN** 消息被删后，任意用户调用 `loadHistoryMessage` / `searchMessage` / `globalSearch`，或经 WS INIT / SYNC 补推拉取消息
- **THEN** 查询条件无条件包含 `AND delete_flag = 0`，被删消息不返回、搜不到
- **AND** 该过滤为 SQL 字面条件，不存在可由 HTTP 绑定绕过的查询开关

#### Scenario: 管理端证据保留原文

- **WHEN** 管理员调用 `getReportDetail` 或 `loadReport` 查看已删消息的举报
- **THEN** 详情（`selectByMessageId` PK 读）与列表摘要（`ReportReadMapper` 独立 SQL）仍返回消息原文，不经过滤
- **AND** 举报列表可正常处置流程不受删除影响

#### Scenario: 会话预览不残留被删内容

- **WHEN** 被删消息是该会话的最新消息
- **THEN** 服务端 `chat_session.last_message` 与 `chat_session_user.last_message` 置「该消息已被管理员删除」，客户端本地会话预览经 20 帧同步为同一占位
- **AND** 被删消息非最新时，双端预览保持指向更新消息不变

---

### Requirement: 管理员删除帧实时同步（admin-delete-frame-20）— C2

管理员删除消息后，系统 SHALL 通过新增 WS 帧类型 `20 ADMIN_DELETE` 向单聊双方与群成员实时同步墓碑状态。

#### Scenario: 单聊接收方在线与离线

- **WHEN** 单聊消息被删，接收方在线 / 离线
- **THEN** 在线经 `sendMsg` 直推 20 帧；离线压入 Redis 离线缓冲，重连 `replayOfflineMessages` 补推
- **AND** 帧经 `CONTACT_CONVERT_TYPES`（含 20）转换后 `contactId=原发送者`，接收方定位到正确会话

#### Scenario: 发送方多端副本

- **WHEN** 单聊消息被删且原发送者有其他在线设备
- **THEN** 转换前的发送方副本（`contactId=会话对方`）直投其全部在线设备（发送方副本条件为 14|20）
- **AND** 原发送者全部设备离线时，副本帧入其 Redis 离线缓冲，重连 `replayOfflineMessages` 补推
- **AND** 补推时 `sendUserId`=收件人本人则跳过联系人转换（`contactId` 保持会话对方，不产生「联系人为自己」的脏会话）

#### Scenario: 群聊同步

- **WHEN** 群内消息被删
- **THEN** `sendMsg2Group` 向在线群成员推送 20 帧（群帧不做联系人转换，`contactId` 保持 groupId）
- **AND** 未收到在线帧的群成员由删除动作逐成员压入离线缓冲，重连补推
- **AND** 补推时 `contactType=1` 的群帧跳过联系人转换（`contactId` 保持 groupId，不产生脏会话）

#### Scenario: 客户端墓碑渲染

- **WHEN** 客户端主进程 `wsClient` 收到 20 帧
- **THEN** 本地 SQLite 该消息行更新为墓碑（已有则更新、离线缺行则补插），渲染层显示「该消息已被管理员删除」
- **AND** 帧带 `lastMessage` 时同步更新本地会话预览；不增加未读数、不触发任务栏闪烁/横幅/提示音（通知白名单 2/5/4 天然排除）
- **AND** 墓碑消息右键菜单隐藏（`messageType∉{2,5}` 不可撤回/转发）

#### Scenario: 与撤回帧语义隔离

- **WHEN** 对比 14（用户撤回）与 20（管理员删除）
- **THEN** 20 帧不改写 DB 中 `message_type` 与 `message_content`（证据保留），文案固定为管理员删除语义；撤回路径与统计不受 20 帧影响

---

### Requirement: 已删消息操作拒绝（deleted-message-guards）— C3

已删消息 SHALL 对普通用户的写操作与资源获取不可用。

#### Scenario: 撤回 / 下载 / 定位被拒

- **WHEN** 消息已被管理员删除，其发送者调用 `recallMessage`，或任意用户调用 `downloadFile` / `locateMessage`
- **THEN** 均返回 `CODE_2201 消息不存在`（守卫先于既有校验链）

#### Scenario: 重复处置幂等

- **WHEN** 已删消息再次被不同举报命中并执行删除内容
- **THEN** 不报错、不改写 `delete_flag`、不重复推送 20 帧，处置状态正常流转

---

## MODIFIED Requirements

### Requirement: 举报处理（C3）

管理员（`token` 中 `admin=true`）SHALL 能够查看、过滤、查看详情并处置用户举报。系统 SHALL 提供：

- `POST /admin/report/loadReport`：跨 `moment_report` / `message_report` 的统一分页列表，支持按 `reportType`(1动态/2评论/3消息)、`status`(0待处理/1已处理/2已驳回)、`reason`、`createTime` 范围过滤；
- `POST /admin/report/getReportDetail`：返回举报字段 + 被举报内容全文 + 举报人/发布者信息；
- `POST /admin/report/dealReport`：将举报 `status` 置为 1（已处理）或 2（已驳回），并记录 `handleUserId`/`handleTime`/`handleNote`/`handleAction`（0仅记录/1删内容/2封禁发布者）；**`handleAction=1` 时对被举报动态/评论软删（`status=0`），对聊天消息执行 `delete_flag` 逻辑删除并推送 20 帧同步（详见 `message-delete-flag` / `admin-delete-frame-20`）**；`handleAction=2` 时封禁发布者（`userInfoService.updateUserStatus(0, publisherId)`）；处置成功后 SHALL 写入一条 `report_audit_log` 审计记录。
- 重复处置/已处置记录（`status≠0`）SHALL 返回 `CODE_2702`。

#### Scenario: 管理员处置举报

- **WHEN** 调用 `dealReport`（合法 status/handleAction）
- **THEN** 更新举报状态/处理人/时间/备注，并按动作执行删内容（消息类为逻辑删除+同步）或封禁发布者，写入审计日志

**变更前（引用原 spec）**: 「`handleAction=1` 时对被举报动态/评论软删（`status=0`），聊天消息无删除状态位故仅记录」

**变更后**: 「`handleAction=1` 时对被举报动态/评论软删（`status=0`），对聊天消息执行 `delete_flag` 逻辑删除并推送 20 帧同步」

---

## REMOVED Requirements

无。

---

## 关键页面原型描述（UI 交互变更备案）

1. `ReportList.vue` 处置对话框（`reportType=3` 选「删除被举报内容」时）：

```
┌ 处置举报 ────────────────────────────┐
│ 处理状态: (●)已处理 ( )已驳回          │
│ 处理动作: [删除被举报内容 ▾]           │
│ 处理备注: [____________________]      │
│ ┌─────────────────────────────────┐  │
│ │ ⚠ 将删除该消息，会话双方实时收到  │  │
│ │  删除通知（原警示已移除）          │  │
│ └─────────────────────────────────┘  │
│            [取消]  [确认处置]          │
└──────────────────────────────────────┘
```

2. `ChatMessage.vue` 墓碑（仿撤回样式，文案不同）：

```
│            ┌──────────────────────┐ │
│            │ 该消息已被管理员删除    │ │   ← 灰色居中，无气泡，右键无菜单
│            └──────────────────────┘ │
```

3. 会话列表预览（被删消息为最新时）：`[头像] 张三   该消息已被管理员删除`

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 聊天消息逻辑删除与证据保留（message-delete-flag） | 阶段一 2/3、阶段二 3、阶段四 2 |
| C2 | ADDED: 管理员删除帧实时同步（admin-delete-frame-20） | 阶段二 1/2、阶段三 1/2、阶段四 4 |
| C3 | ADDED: 已删消息操作拒绝（deleted-message-guards） | 阶段二 2、阶段四 2 |
| 已有能力 举报处理（C3） | MODIFIED: 举报处理 | 阶段二 3、阶段三 3、阶段五 2（回写） |
