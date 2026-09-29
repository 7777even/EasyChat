# Spec Delta：本地 SQLite 会话表补 no_disturb / draft 两列

- 关联 Tasks: `2026-09-29-local-session-attr-columns/tasks.md`
- 创建日期: 2026-09-29

## ADDED Requirements

### Requirement: 本地库启动自动补列

本地 SQLite 表结构缺失字段时，应用启动阶段必须以幂等方式补齐，不得静默丢弃写入。

#### Scenario: 存量库缺列

- **WHEN** 应用启动且 `chat_session_user` 不存在 `no_disturb` 或 `draft` 列
- **THEN** `Tables.js#alter_tables` 对缺失列逐条执行 `alter table ... add column`
- **AND** 已存在该列时跳过（重复启动不报错）

#### Scenario: 新建库

- **WHEN** 用户首次运行、本地库新建
- **THEN** `add_tables` DDL 直接包含 `no_disturb integer default 0` 与 `draft varchar default null`

---

### Requirement: 草稿本地持久化与恢复

会话草稿必须落本地 SQLite 并在下次加载会话列表时可恢复。

#### Scenario: 写入并恢复

- **WHEN** 渲染层经 IPC `saveSessionDraft` 保存草稿，或服务端 `-7` 同步帧下发 `draft`
- **THEN** `chat_session_user.draft` 更新为该值
- **AND** 下次 `loadSessionData` 读回的会话对象含 `draft`，`MessageSend.vue` 据此回填输入框

---

### Requirement: 免打扰本地持久化

免打扰状态必须落本地 SQLite，供通知抑制判定与菜单状态展示读取。

#### Scenario: 写入并读回

- **WHEN** 渲染层经 IPC `setSessionNoDisturb` 或 `-7` 同步帧设置 `noDisturb=1`
- **THEN** `chat_session_user.no_disturb` 为 `1`
- **AND** `Chat.vue` 通知抑制分支（`session.noDisturb == 1`）与「消息免打扰」菜单读到该值

---

### Requirement: 空值写入安全

会话属性写入在列缺失或值为空时不得产生非法 SQL。

#### Scenario: 同步帧不带值

- **WHEN** `-7` 同步帧的 `value` 为 `null`/`undefined`
- **THEN** `updateSessionAttr` 直接跳过，不落库

#### Scenario: 空 set / 空 where

- **WHEN** `ADB#update` 拼不出任何可更新列或任何 where 条件
- **THEN** 短路返回 `0`（与 `run()` 受影响行数口径一致），不执行 SQL、不弹错误框

---

## MODIFIED Requirements

（无 —— 既有 `openspec/specs/` 中无覆盖本地 SQLite 缓存表结构的规格）

## REMOVED Requirements

（无）

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 存量库自动补列 | ADDED: 本地库启动自动补列 | 阶段一 1.1/1.2、阶段二 2.1 |
| C2 草稿持久化 | ADDED: 草稿本地持久化与恢复 | 阶段二 2.1 |
| C3 免打扰持久化 | ADDED: 免打扰本地持久化 | 阶段二 2.1 |
| C4 空值安全 | ADDED: 空值写入安全 | 阶段一 1.2、阶段二 2.1 |
