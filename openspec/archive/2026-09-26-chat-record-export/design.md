# Design — 聊天记录导出

- 关联 Proposal: 2026-09-26-chat-record-export/proposal.md
- 创建日期: 2026-09-26

## 架构与数据流

```
Chat.vue 右键菜单「导出聊天记录」
   → 格式选择（TXT / CSV）
   → ipcRenderer.send('exportChatRecord', {sessionId, contactName, format})
        ↓
ipc.js onExportChatRecord
        ↓
exportChat.js exportChatRecord()
        ├─ ChatMessageModel.selectAllMessageList({sessionId})   ← 本地 SQLite 只读
        ├─ buildTxt(list) / buildCsv(list)                      ← 纯函数组装
        ├─ dialog.showSaveDialog({defaultPath, filters})        ← 用户选路径
        ├─ fs.writeFileSync(path, content, encoding)            ← 落盘
        └─ return {success, canceled, path, count}
        ↓
ipcRenderer 'exportChatRecordCallback' → Chat.vue 提示结果
```

## 决策（ADR）

**ADR-1：数据源只用本地 SQLite，不拉云端**
- 备选：导出前循环 `loadHistoryMessage` 拉全量再导。
- 取舍：云端全量涉及翻页终止条件、漫游语义、大量回写本地，风险与工时都显著上升；本地已漫游/收过的消息足以覆盖「留证 / 备份」主场景。
- 结论：本期只导本地；云端全量归入后续「备份迁移」专项。导出内容即用户当前可见内容，语义直观无 surprise。

**ADR-2：导出在主进程完成，不经渲染进程**
- 理由：SQLite 句柄与 `dialog`/`fs` 均在主进程；渲染进程只传参和收结果，符合既有 `file.js` 的 saveAs 模式。
- 结论：新增 `src/main/exportChat.js`，与 `file.js` 同层。

**ADR-3：CSV 写入 UTF-8 BOM**
- 理由：Excel 默认按本地编码打开无 BOM 的 CSV，中文乱码。
- 结论：`\uFEFF` 前缀 + UTF-8。

**ADR-4：CSV 公式注入防护**
- 风险：以 `=` `+` `-` `@` 开头的单元格会被 Excel 当公式执行。
- 结论：此类值前置单引号（`'`），并对所有单元格做 `"` 转义（双引号翻倍），换行替换为空格。

**ADR-5：TXT 中媒体消息降级为占位符**
- 理由：图片/文件内容是二进制或远程文件，文本化无意义。
- 结论：`[图片]` / `[视频]` / `[文件] 文件名`，与聊天列表展示语义一致。

## 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 大会话导出卡顿主线程 | 低 | 单会话本地消息量级有限（千级）；同步写盘在保存对话框之后，用户已感知为一次性操作 |
| 用户选了不可写路径 | 低 | `try/catch` 包裹写盘，失败回传 `success:false` + 错误信息，渲染层提示 |
| CSV 打开乱码 | 中 | 已按 ADR-3 加 BOM |
| 撤回消息被导出 | 低 | 撤回消息本地已标记/删除，导出即当前视图，符合预期；不做特殊过滤 |

## 依赖

- Electron `dialog`、`fs`（已用于 `file.js`）
- `src/main/db/ChatMessageModel.js`（需新增全量查询）
- `store.getUserId()`（既有主进程 store）
- 无新增生产依赖

## 数据影响

- 无表结构变更、无迁移、无写入。只读 `chat_message`。
