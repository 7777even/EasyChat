# Spec Delta — 消息多选

- 关联 Tasks: 2026-09-30-message-multi-select/tasks.md
- 创建日期: 2026-09-30

## ADDED Requirements

### Requirement: 进入多选模式

用户可通过右键菜单进入消息多选模式。

#### Scenario: 右键菜单进入多选

- **WHEN** 用户右键点击消息并选择「多选」
- **THEN** 进入多选模式，消息左侧显示勾选框
- **AND** 底部显示操作栏（已选 N 条 + 转发/删除/退出按钮）

---

### Requirement: 批量转发

多选后可批量转发到其他会话。

#### Scenario: 批量转发消息

- **WHEN** 用户在多选模式中选择多条消息并点击「转发」
- **THEN** 弹出 ForwardSelect 选择目标会话
- **AND** 确认后逐条转发到选中的会话

---

### Requirement: 批量删除

多选后可批量删除（仅本地删除）。

#### Scenario: 批量删除消息

- **WHEN** 用户在多选模式中选择多条消息并点击「删除」
- **THEN** 弹出确认弹窗
- **AND** 确认后从本地消息列表和数据库中删除

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
| C1 | ADDED: 进入多选模式 | 1.1, 1.2 |
| C2 | ADDED: 批量转发 | 1.4 |
| C3 | ADDED: 批量删除 | 1.4 |
