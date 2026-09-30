# Spec — 位置分享

## ADDED Requirements

### Requirement: 发送位置消息

用户选择位置并发送位置消息。

#### Scenario: 发送位置消息

- **WHEN** 用户在聊天窗口点击"位置"按钮
- **THEN** 显示地图选择位置
- **AND** 用户选择位置后发送位置消息

---

### Requirement: 查看位置详情

用户点击位置消息查看位置详情。

#### Scenario: 查看位置详情

- **WHEN** 用户点击位置消息
- **THEN** 显示位置详情（地址、经纬度）
- **AND** 可打开地图应用导航
