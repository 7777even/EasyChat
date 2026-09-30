# Spec — 聊天背景

## ADDED Requirements

### Requirement: 设置聊天背景

用户可在聊天窗口设置全局统一的聊天背景图。

#### Scenario: 设置聊天背景

- **WHEN** 用户在聊天窗口点击「聊天背景」按钮并选择图片
- **THEN** 图片以 Base64 格式保存到 user_setting.sysSetting.chatBackground
- **AND** 聊天面板立即应用背景图

#### Scenario: 图片大小超限

- **WHEN** 用户选择的图片超过 2MB
- **THEN** 提示「图片大小不能超过 2MB」

---

### Requirement: 清除聊天背景

用户可清除聊天背景恢复默认。

#### Scenario: 清除聊天背景

- **WHEN** 用户清除聊天背景
- **THEN** user_setting.sysSetting.chatBackground 置空
- **AND** 聊天面板恢复默认背景
