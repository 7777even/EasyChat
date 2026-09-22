# Spec Delta — <变更名称>

- 关联 Tasks: <YYYY-MM-DD-<name>/tasks.md>
- 创建日期: <YYYY-MM-DD>

> 格式对齐 `openspec/specs/<capability>/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: <能力名，对齐 proposal Capability C1>

<一句话描述系统的能力行为。>

#### Scenario: <场景标题>

- **WHEN** <触发条件>
- **THEN** <系统行为预期>
- **AND** <补充行为 / 数据断言>

#### Scenario: <异常场景（可选）>

- **WHEN** <触发条件>
- **THEN** <错误码 / 回滚行为>

---

### Requirement: <能力名，对齐 proposal Capability C2>

<一句话描述系统的能力行为。>

#### Scenario: <场景标题>

- **WHEN** <触发条件>
- **THEN** <系统行为预期>

---

## MODIFIED Requirements

### Requirement: <已有规格中需要修改的能力名>

<一句话描述修改后的行为。>

#### Scenario: <场景标题>

- **WHEN** <触发条件>
- **THEN** <新的系统行为预期>

**变更前（引用原 spec）**: <原 spec.md 中的行为描述>

**变更后**: <本变更修改后的行为描述>

---

## REMOVED Requirements

### Requirement: <被移除的能力名>

<一句话描述被移除的能力及原因。>

**移除原因**: <为什么不再需要这个能力？>

**替代方案（如有）**: <替代的能力是什么？>

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: <能力名> | 1.1, 2.1 |
| C2 | ADDED: <能力名> | 1.2, 3.1 |
| 已有能力 X | MODIFIED: <能力名> | 2.1 |
