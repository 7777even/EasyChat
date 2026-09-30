# Spec Delta — 收藏功能

- 关联 Tasks: 2026-09-30-favorite/tasks.md
- 创建日期: 2026-09-30

> 格式对齐 `openspec/specs/favorite/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 收藏消息（C1）

用户收藏消息。

#### Scenario: 收藏消息

- **WHEN** 用户在消息右键菜单点击"收藏"
- **THEN** 服务端保存收藏记录
- **AND** 前端提示"收藏成功"

---

### Requirement: 取消收藏（C2）

用户取消收藏。

#### Scenario: 取消收藏

- **WHEN** 用户在收藏列表点击"取消收藏"
- **THEN** 服务端删除收藏记录
- **AND** 前端提示"取消收藏成功"

---

### Requirement: 查看收藏列表（C3）

用户查看收藏列表。

#### Scenario: 查看收藏列表

- **WHEN** 用户进入收藏页面
- **THEN** 显示收藏列表
- **AND** 包含收藏内容、收藏时间

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
| C1 | ADDED: 收藏消息 | 1.1, 1.2, 1.3, 3.1 |
| C2 | ADDED: 取消收藏 | 1.1, 1.2, 1.3, 3.2 |
| C3 | ADDED: 查看收藏列表 | 1.1, 1.2, 1.3, 3.2 |
