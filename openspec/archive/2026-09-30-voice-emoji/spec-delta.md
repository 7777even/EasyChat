# Spec Delta — 语音消息与表情包

- 关联 Tasks: 2026-09-30-voice-emoji/tasks.md
- 创建日期: 2026-09-30

> 格式对齐 `openspec/specs/voice-emoji/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 语音消息（C1 + C2）

用户按住说话录制语音，松开后发送语音消息，点击语音消息可播放/暂停。

#### Scenario: 按住说话录制语音

- **WHEN** 用户在聊天窗口按住"按住说话"按钮
- **THEN** 客户端开始录音
- **AND** 显示录音动画

#### Scenario: 松开发送语音

- **WHEN** 用户松开"按住说话"按钮
- **THEN** 客户端停止录音并发送语音消息
- **AND** 语音消息显示在聊天窗口

#### Scenario: 播放语音消息

- **WHEN** 用户点击语音消息
- **THEN** 播放语音
- **AND** 显示播放动画

#### Scenario: 暂停语音消息

- **WHEN** 用户再次点击正在播放的语音消息
- **THEN** 暂停播放

---

### Requirement: 表情包（C3 + C4）

用户选择表情包发送，可收藏/管理表情包。

#### Scenario: 发送表情包

- **WHEN** 用户在表情包选择器中点击表情包
- **THEN** 发送表情包消息
- **AND** 表情包显示在聊天窗口

#### Scenario: 收藏表情包

- **WHEN** 用户在表情包选择器中点击"收藏"
- **THEN** 表情包添加到收藏列表

#### Scenario: 管理表情包

- **WHEN** 用户进入表情包管理界面
- **THEN** 显示已收藏的表情包列表
- **AND** 可删除表情包

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
| C1 | ADDED: 语音消息 | 1.3, 1.4, 3.1, 3.3 |
| C2 | ADDED: 语音消息 | 1.3, 1.4, 3.2 |
| C3 | ADDED: 表情包 | 1.1, 1.2, 4.1, 4.2 |
| C4 | ADDED: 表情包 | 1.1, 1.2, 4.3 |
