# Spec — 位置分享

## ADDED Requirements

### Requirement: 发送位置消息

用户在聊天中发送位置消息（消息类型 25）。

#### Scenario: 发送位置消息

- **WHEN** 用户在聊天窗口点击"位置"按钮
- **THEN** 弹出位置选择面板，可输入地点名称并可选获取当前位置
- **AND** 用户确认后发送一条 `messageType=25` 的消息
- **AND** 位置信息以 JSON 存入 `chat_message.extra_data`：`{"location":"...","latitude":39.9,"longitude":116.4}`

#### Scenario: 位置信息可缺省经纬度

- **WHEN** 用户仅输入地点名称、未获取定位
- **THEN** `latitude` / `longitude` 为 null
- **AND** 消息仍可正常发送与展示

#### Scenario: 不引入地图 SDK

- **WHEN** 实现位置选择交互
- **THEN** 不新增地图类前端依赖
- **AND** 定位走浏览器 `navigator.geolocation`，地图跳转走系统默认地图（外部 URI）

---

### Requirement: 查看位置详情

用户点击位置消息查看位置详情。

#### Scenario: 查看位置详情

- **WHEN** 用户点击位置消息气泡
- **THEN** 弹出详情，显示地点名称与经纬度（无经纬度时显示"未提供"）
- **AND** 有经纬度时提供「在地图中打开」

#### Scenario: 在地图中打开

- **WHEN** 用户在详情中点击「在地图中打开」
- **THEN** 通过 `openUrl` IPC 用系统默认地图打开该经纬度
- **AND** 无经纬度时按钮禁用

#### Scenario: 位置消息渲染

- **WHEN** 会话中出现 `messageType=25` 的消息
- **THEN** 渲染为位置气泡（图标 + 地点名 + 经纬度），不渲染纯文本正文
- **AND** 自己与对方消息均渲染

---

## MODIFIED Requirements

无。

---

## REMOVED Requirements

无。
