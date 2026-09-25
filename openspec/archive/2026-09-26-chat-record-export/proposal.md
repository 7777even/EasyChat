# Proposal — 聊天记录导出（单会话导出为 TXT / CSV）

- 创建日期: 2026-09-26
- 效率等级: L3（新增业务能力：聊天记录导出）
- 需求来源: 用户「新增能力方向 · 四」清单第 3 项，按「从简单的开始做」排期首选（自闭环、无协议改动）

## Why

微信 / Discord 均支持聊天记录留存。现状 EasyChat 消息只在本地 SQLite 与服务端留存，**没有任何导出出口**：用户想留证、备份或做简单统计只能手工截图。本变更提供单会话粒度的文本导出，是「备份迁移」能力的最小可用切片。

范围刻意收敛：**只做本地已持久化消息的导出，不做云端全量拉取**：
- 云端全量需循环翻页 `loadHistoryMessage` 并回写本地，涉及漫游语义与数据量风险，留待「备份迁移」专项；
- 本地导出零后端改动、零协议改动，可独立交付且可回退。

## What Changes

- 后端: **零改动**——不新增/修改端点、不动 WS 协议与包络、无错误码变动、无表结构变更。
- 前端主进程:
  - 新增 `src/main/exportChat.js`：`querySessionMessages()`（按 `sessionId + userId` 全量升序读取 `chat_message`）、`buildTxt()` / `buildCsv()` 文本组装、`exportChatRecord()`（`dialog.showSaveDialog` 选路径 → `fs.writeFileSync` 落盘）。
  - `src/main/db/ChatMessageModel.js`：新增 `selectAllMessageList({sessionId})` 全量查询（现有 `selectMessageList` 为分页接口，不适用于导出）。
  - `src/main/ipc.js`：注册并导出 `exportChatRecord` 通道（同步返回 `{success, canceled, path, count}`）。
- 前端渲染进程:
  - `src/renderer/src/views/chat/Chat.vue`：会话右键菜单新增「导出聊天记录」项；触发后弹格式选择（TXT / CSV），经 `window.ipcRenderer.send('exportChatRecord')` 下发，回调 `exportChatRecordCallback` 提示结果。
- preload: **零改动**——复用既有 `window.ipcRenderer.send/on`，不新增 `contextBridge` 暴露面（规避 L4 门禁）。
- 数据库: 无表结构 / 字段变更，无迁移。

## Capabilities

- C1: 单会话导出——对当前选中会话，导出其本地已持久化的全部消息，按时间升序。
- C2: 两种格式——TXT（人读友好：`[时间] 昵称: 内容`，媒体消息渲染为 `[图片]/[文件] 文件名`）与 CSV（表格友好：UTF-8 BOM，Excel 直接打开不乱码，含 消息ID/时间/发送人ID/昵称/类型/内容/文件名 列）。
- C3: 路径与结果反馈——经系统保存对话框选路径，默认文件名 `EasyChat-<会话名>-<日期>.txt|.csv`；成功提示条数与路径；用户取消不报错；失败提示原因。

## Impact

- 对外接口: 无。后端契约调用方无需同步。
- 存量数据: 只读，不写不改，无影响。
- 性能 / 安全: 导出数据量级为本地单会话消息（千级），同步读取与写盘耗时可忽略；CSV 单元格做引号转义与换行处理，避免公式注入（`=` `+` `-` `@` 开头加前置单引号）；仅写用户主动选择的路径，不写应用目录。
- 行为变更（对内）: 无——纯新增入口，既有消息链路零改动。
- 回退方案: revert 单个 commit 即回到无导出入口状态，无残留数据。

---

## ☑ 人工确认关卡

> - [x] 同意方案（用户 2026-09-26「从简单的开始做」指令，本项为清单中最简项），允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
