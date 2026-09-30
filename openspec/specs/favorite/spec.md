# Spec — 收藏功能

## ADDED Requirements

### Requirement: 收藏消息

用户收藏消息。

#### Scenario: 收藏消息

- **WHEN** 用户在消息右键菜单点击"收藏"
- **THEN** 服务端保存收藏记录
- **AND** 前端提示"收藏成功"

---

### Requirement: 取消收藏

用户取消收藏。

#### Scenario: 取消收藏

- **WHEN** 用户在收藏列表点击"取消收藏"
- **THEN** 服务端删除收藏记录
- **AND** 前端提示"取消收藏成功"

---

### Requirement: 查看收藏列表

用户查看收藏列表。

#### Scenario: 查看收藏列表

- **WHEN** 用户进入收藏页面
- **THEN** 显示收藏列表
- **AND** 包含收藏内容、收藏时间
