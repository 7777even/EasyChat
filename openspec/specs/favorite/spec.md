# Spec — 收藏功能

## ADDED Requirements

### Requirement: 收藏消息

用户收藏消息以便日后查阅。

#### Scenario: 收藏消息

- **WHEN** 用户在消息右键菜单点击"收藏"
- **THEN** 服务端保存收藏记录（`favorite` 表，含 `user_id` / `message_id` / `content` / `file_path` / `create_time`）
- **AND** 前端提示"收藏成功"

#### Scenario: 可收藏的消息类型

- **WHEN** 用户右键一条文本(2) / 媒体(5) / 位置(25) 消息
- **THEN** 菜单出现"收藏"选项
- **AND** 撤回(14) / 管理员删除(20) 消息不出现该选项

#### Scenario: 重复收藏被拒

- **WHEN** 用户收藏一条已收藏过的消息
- **THEN** 服务端返回"该消息已收藏"
- **AND** 不产生重复记录（`uk_user_message(user_id, message_id)` 唯一索引兜底）

---

### Requirement: 取消收藏

用户取消已收藏的消息。

#### Scenario: 取消收藏

- **WHEN** 用户在收藏列表点击"取消收藏"并确认
- **THEN** 服务端删除该收藏记录
- **AND** 列表刷新

---

### Requirement: 查看收藏列表

用户查看自己收藏的消息。

#### Scenario: 查看收藏列表

- **WHEN** 用户进入设置页「我的收藏」
- **THEN** 显示收藏列表（内容 + 文件路径 + 收藏时间）
- **AND** 无收藏时显示空态提示

#### Scenario: 收藏内容取值

- **WHEN** 收藏的是媒体消息
- **THEN** `content` 存文件名、`file_path` 存文件路径
- **AND** 收藏的是文本/位置消息时 `content` 存正文/地址

---

## MODIFIED Requirements

无。

---

## REMOVED Requirements

无。
