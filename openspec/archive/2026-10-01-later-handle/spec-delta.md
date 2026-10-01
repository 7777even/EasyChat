# Spec Delta — 稍后处理

- 关联 Tasks: 2026-10-01-later-handle/tasks.md
- 创建日期: 2026-10-01

## ADDED Requirements

### Requirement: 标记稍后处理

用户可通过消息右键菜单将消息标记为稍后处理。

#### Scenario: 标记稍后处理

- **WHEN** 用户右键点击消息并选择「稍后处理」
- **THEN** 消息保存到本地 SQLite 的 later_handle 表
- **AND** 系统会在 1 小时后通过系统通知提醒用户

---

### Requirement: 稍后处理提醒

系统会在 1 小时后通过系统通知提醒用户。

#### Scenario: 系统通知提醒

- **WHEN** 消息标记为稍后处理且超过 1 小时
- **THEN** 系统发送通知提醒用户
- **AND** 点击通知可跳转到对应会话

---

### Requirement: 稍后处理列表

用户可查看和管理稍后处理消息列表。

#### Scenario: 查看稍后处理列表

- **WHEN** 用户查看稍后处理列表
- **THEN** 显示所有稍后处理消息
- **AND** 用户可删除稍后处理消息

---

## MODIFIED Requirements

无

---

## REMOVED Requirements

无

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 标记稍后处理 | 1.1, 1.2, 1.3 |
| C2 | ADDED: 稍后处理提醒 | 1.5 |
| C3 | ADDED: 稍后处理提醒 | 1.5 |
