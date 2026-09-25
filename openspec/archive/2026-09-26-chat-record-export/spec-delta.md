# Spec Delta — 聊天记录导出

- 关联 Tasks: 2026-09-26-chat-record-export/tasks.md
- 创建日期: 2026-09-26

> 格式对齐 `openspec/specs/<capability>/spec.md`（Requirement / Scenario）。
> 与 proposal Capabilities C1/C2/C3 一一对应。
> 目标 capability `chat-record-export` 为新建（`openspec/specs/` 现无此项）。

## ADDED Requirements

### Requirement: 单会话导出

用户可对当前选中会话导出其本地已持久化的全部消息，按时间升序排列；导出为只读操作，不修改任何本地或服务端数据。

#### Scenario: 导出单聊会话为 TXT

- **WHEN** 用户在会话列表对某单聊会话点击右键菜单「导出聊天记录」并选择 TXT 格式
- **THEN** 弹出系统保存对话框，默认文件名形如 `EasyChat-<会话名>-<日期>.txt`
- **AND** 用户确认路径后，该会话本地全部消息按 `时间 昵称: 内容` 逐行写入文件
- **AND** 渲染层提示导出条数与保存路径

#### Scenario: 导出群聊会话

- **WHEN** 用户对某群聊会话执行导出
- **THEN** 导出内容包含该群本地全部消息，每条带发送人昵称，可区分不同发言者

#### Scenario: 用户取消保存

- **WHEN** 用户在保存对话框中点击取消
- **THEN** 不写任何文件，且不提示错误（静默返回）

### Requirement: 导出格式

导出支持 TXT 与 CSV 两种格式，分别面向人读与表格分析。

#### Scenario: TXT 人读格式

- **WHEN** 用户选择 TXT 格式
- **THEN** 每行格式为 `[yyyy-MM-dd HH:mm:ss] 昵称: 内容`
- **AND** 图片消息渲染为 `[图片]`，视频为 `[视频]`，文件为 `[文件] 文件名`

#### Scenario: CSV 表格格式且 Excel 不乱码

- **WHEN** 用户选择 CSV 格式
- **THEN** 文件以 UTF-8 BOM 开头，Excel 直接打开中文不乱码
- **AND** 首行为表头（消息ID / 时间 / 发送人ID / 昵称 / 消息类型 / 内容 / 文件名）
- **AND** 单元格内双引号翻倍转义，换行替换为空格
- **AND** 以 `=` `+` `-` `@` 开头的值前置单引号，避免被 Excel 当作公式执行

### Requirement: 导出结果反馈

导出操作的成功、取消、失败均向用户给出明确结果。

#### Scenario: 导出成功

- **WHEN** 文件写入成功
- **THEN** 渲染层提示「已导出 N 条消息」及保存路径

#### Scenario: 导出失败

- **WHEN** 目标路径不可写或其他 IO 异常发生
- **THEN** 渲染层提示失败原因，不抛出未捕获异常，应用状态不受影响
