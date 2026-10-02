# Spec — 输入状态与在线状态实时感知

## ADDED Requirements

### Requirement: 输入状态实时感知

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

#### Scenario: 发送链路必须贯通主进程

- **WHEN** 渲染进程调用 `window.api.sendTypingStatus(...)`
- **THEN** 主进程必须已注册 `sendTypingStatus` IPC 监听并调用 `wsClient.sendTypingStatus`
- **AND** 渲染进程直接持有 WS 句柄、或主进程未注册该通道，均视为不合规（帧静默丢失、构建无报错）

---

### Requirement: 在线状态展示

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

### Requirement: 用户状态管理

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

#### Scenario: 状态变更链路必须贯通主进程

- **WHEN** 渲染进程调用 `window.api.sendUserStatusChange(status)`
- **THEN** 主进程必须已注册 `sendUserStatusChange` IPC 监听并调用 `wsClient.sendUserStatusChange`
- **AND** 未注册即视为不合规（状态变更静默失效）
