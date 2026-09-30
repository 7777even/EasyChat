# Spec Delta — 操作日志

- 关联 Tasks: 2026-09-30-operation-log/tasks.md
- 创建日期: 2026-09-30

> 格式对齐 `openspec/specs/operation-log/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 登录操作日志（C1）

用户登录成功/失败时记录操作日志。

#### Scenario: 登录成功

- **WHEN** 用户登录成功
- **THEN** 记录操作日志，包含用户 ID、操作类型、操作描述、IP 地址、操作时间

#### Scenario: 登录失败

- **WHEN** 用户登录失败
- **THEN** 记录操作日志，包含用户 ID、操作类型、操作描述、IP 地址、操作时间

---

### Requirement: 修改密码操作日志（C2）

用户修改密码时记录操作日志。

#### Scenario: 修改密码

- **WHEN** 用户修改密码
- **THEN** 记录操作日志，包含用户 ID、操作类型、操作描述、IP 地址、操作时间

---

### Requirement: 删除消息操作日志（C3）

用户删除消息时记录操作日志。

#### Scenario: 删除消息

- **WHEN** 用户删除消息
- **THEN** 记录操作日志，包含用户 ID、操作类型、操作描述、IP 地址、操作时间

---

### Requirement: 强制下线操作日志（C4）

管理员强制下线时记录操作日志。

#### Scenario: 强制下线

- **WHEN** 管理员强制下线用户
- **THEN** 记录操作日志，包含用户 ID、操作类型、操作描述、IP 地址、操作时间

---

## MODIFIED Requirements

无修改已有规格。

---

## REMOVED Requirements

无移除已有规格。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 登录操作日志 | 1.3 |
| C2 | ADDED: 修改密码操作日志 | 1.3 |
| C3 | ADDED: 删除消息操作日志 | 1.4 |
| C4 | ADDED: 强制下线操作日志 | 1.5 |
