# Spec — 状态

## ADDED Requirements

### Requirement: 设置状态

用户可设置临时状态（文字+图片）。

#### Scenario: 设置状态

- **WHEN** 用户在好友详情页设置状态
- **THEN** 状态保存到 user_status 表，包含文字和图片
- **AND** 状态 24 小时后自动过期

#### Scenario: 状态文字超限

- **WHEN** 用户设置的状态文字超过 500 字符
- **THEN** 返回错误码 1001（参数非法）

---

### Requirement: 查看好友状态

好友可在好友详情页查看状态。

#### Scenario: 查看好友状态

- **WHEN** 用户打开好友详情页
- **THEN** 显示好友的状态（文字+图片）
- **AND** 状态过期后不显示

---

### Requirement: 清除状态

用户可清除自己的状态。

#### Scenario: 清除状态

- **WHEN** 用户清除自己的状态
- **THEN** user_status 表中该用户的状态被删除
