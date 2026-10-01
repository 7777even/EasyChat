# Spec Delta — @所有人

- 关联 Tasks: 2026-10-01-at-all/tasks.md
- 创建日期: 2026-10-01

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

## MODIFIED Requirements

无

---

## REMOVED Requirements

无

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: @所有人 | 1.1, 1.3 |
| C2 | ADDED: @所有人消息样式 | 1.2 |
| C3 | ADDED: @所有人 | 1.1 |
