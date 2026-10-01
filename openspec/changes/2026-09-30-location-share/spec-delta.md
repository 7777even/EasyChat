# Spec Delta — 位置分享

- 关联 Tasks: 2026-09-30-location-share/tasks.md
- 创建日期: 2026-09-30

> 格式对齐 `openspec/specs/location-share/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 发送位置消息（C1）

用户选择位置并发送位置消息。

#### Scenario: 发送位置消息

- **WHEN** 用户在聊天窗口点击"位置"按钮
- **THEN** 显示地图选择位置
- **AND** 用户选择位置后发送位置消息

---

### Requirement: 查看位置详情（C2）

用户点击位置消息查看位置详情。

#### Scenario: 查看位置详情

- **WHEN** 用户点击位置消息
- **THEN** 显示位置详情（地址、经纬度）
- **AND** 可打开地图应用导航

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
| C1 | ADDED: 发送位置消息 | 1.1, 1.2, 3.1 |
| C2 | ADDED: 查看位置详情 | 1.1, 1.2, 3.2 |
