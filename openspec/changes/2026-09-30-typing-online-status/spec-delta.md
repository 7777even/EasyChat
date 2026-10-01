# Spec Delta — 输入状态与在线状态实时感知

- 关联 Tasks: 2026-09-30-typing-online-status/tasks.md
- 创建日期: 2026-09-30

> 格式对齐 `openspec/specs/typing-online-status/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 输入状态实时感知（C1 + C2）

单聊时，一方正在输入，另一方能看到"正在输入..."提示。

#### Scenario: A 正在输入，B 看到提示

- **WHEN** A 在聊天窗口输入文字
- **THEN** A 的客户端发送 `TYPING_STATUS(21)` 帧给服务端
- **AND** 服务端中继该帧给 B
- **AND** B 的聊天窗口显示"正在输入..."提示

#### Scenario: B 正在输入，A 看到提示

- **WHEN** B 在聊天窗口输入文字
- **THEN** B 的客户端发送 `TYPING_STATUS(21)` 帧给服务端
- **AND** 服务端中继该帧给 A
- **AND** A 的聊天窗口显示"正在输入..."提示

#### Scenario: 输入停止

- **WHEN** A 停止输入超过 3 秒
- **THEN** A 的客户端发送 `typing=false` 的 `TYPING_STATUS(21)` 帧
- **AND** B 的聊天窗口隐藏"正在输入..."提示

---

### Requirement: 在线状态展示（C3）

好友列表显示在线/离线状态。

#### Scenario: 用户上线

- **WHEN** 用户登录成功
- **THEN** 服务端更新 Redis 中该用户状态为 `ONLINE(1)`
- **AND** 服务端向所有好友广播 `ONLINE_STATUS(22)` 帧
- **AND** 好友列表显示该用户为在线

#### Scenario: 用户下线

- **WHEN** 用户退出或断线
- **THEN** 服务端更新 Redis 中该用户状态为 `OFFLINE(3)`
- **AND** 服务端向所有好友广播 `ONLINE_STATUS(22)` 帧
- **AND** 好友列表显示该用户为离线

---

### Requirement: 用户状态管理（C4 + C5）

用户可手动设置状态（在线/忙碌/离线），退出/断线时自动更新为离线。

#### Scenario: 用户设置状态为忙碌

- **WHEN** 用户在设置页选择"忙碌"
- **THEN** 客户端发送 `USER_STATUS_CHANGE(23)` 帧给服务端
- **AND** 服务端更新 Redis 中该用户状态为 `BUSY(2)`
- **AND** 服务端向所有好友广播 `ONLINE_STATUS(22)` 帧

#### Scenario: 用户退出

- **WHEN** 用户退出应用
- **THEN** 客户端发送 `USER_STATUS_CHANGE(23)` 帧，状态为 `OFFLINE(3)`
- **AND** 服务端更新 Redis 中该用户状态为 `OFFLINE(3)`
- **AND** 服务端向所有好友广播 `ONLINE_STATUS(22)` 帧

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
| C1 | ADDED: 输入状态实时感知 | 1.1, 1.5, 3.1, 4.1 |
| C2 | ADDED: 输入状态实时感知 | 1.1, 1.5, 3.1, 4.1 |
| C3 | ADDED: 在线状态展示 | 1.2, 1.3, 1.4, 3.3, 4.2 |
| C4 | ADDED: 用户状态管理 | 1.2, 1.3, 1.6, 3.2, 4.3 |
| C5 | ADDED: 用户状态管理 | 1.2, 1.3, 1.4, 3.4 |
