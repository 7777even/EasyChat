# Design — 跨会话云端全量备份导出

- 关联 Proposal: `2026-09-26-chat-backup-export/proposal.md`
- 创建日期: 2026-09-26

## 1. 背景与目标

单会话云端导出已落地（`exportChat.js` 支持传入 `list` 走云端分支）。本次把它扩展到**全部会话**：一次操作产出一个覆盖所有会话的备份文件。

非目标：不做增量备份、不做云端存储/还原、不做跨账号迁移、不改后端接口。

## 2. 架构与数据流

```
[渲染进程] 设置页「数据备份」
    │  点击「开始备份」
    ▼
utils/cloudBackup.js
    ├─ 1) 取会话列表：ipcRenderer 'loadSessionData' → 主进程 chat_session_user（本地）
    ├─ 2) 逐会话（串行）pullCloudHistory(sessionId)
    │       └─ POST /chat/loadHistoryMessage {sessionId, lastMessageId, pageSize}
    │          服务端按 messageId desc 返回，游标严格 < 递减，直到返回空
    ├─ 3) 聚合 groups = [{sessionId, sessionTitle, contactType, messages[]}]
    └─ 4) ipcRenderer 'exportChatBackup' → 主进程
[主进程] exportChat.js exportChatBackup()
    ├─ 归一化 camelCase → snake_case（复用 normalizeCloudRow）
    ├─ 格式化：TXT 按会话分段 / CSV 增加「会话」列
    ├─ dialog.showSaveDialog → fs.writeFileSync
    └─ 回传 exportChatBackupCallback {success, canceled, path, count, sessionCount, failedSessions}
[渲染进程] 展示结果（成功条数/会话数/失败会话）
```

## 3. 决策记录（ADR）

### ADR-001：前端逐会话聚合，不新增后端导出接口

- **选项 A（选定）**：复用现有 `loadHistoryMessage`，前端遍历会话聚合。
- **选项 B**：后端新增 `/chat/exportAll` 一次性返回全量。
- **理由**：B 属新增后端业务接口，需改 Controller/Service/契约，还要处理大响应流式与超时；A 完全复用已校验归属的既有接口，**零后端改动、零契约漂移**（对齐用户上一轮选定的 L2 复用路线）。代价是请求次数多，用串行 + 进度 + 上限缓解。

### ADR-002：会话枚举用本地 `chat_session_user`

- 会话是本地维护的概念，服务端没有「会话列表」接口（只有 `loadContact` / `loadMyGroup`，那是联系人/群维度，不等于会话）。
- 本地 `chat_session_user` 覆盖所有产生过消息的会话，正是备份需要的范围。
- 兜底：若本地会话列表为空，直接提示无可备份会话，不发无谓请求。

### ADR-003：新增独立 IPC 通道 `exportChatBackup`，而非复用 `exportChatRecord`

- 备份与单会话导出的**入参形状与结果字段都不同**（`groups[]` vs `list`；结果含 `sessionCount`/`failedSessions`）。
- 硬塞进 `exportChatRecord` 会让该函数承担两套语义，回调解析也要分支，可读性差。
- 代价是新增通道 → **必须遵守红线在 `index.js` 注册**，并用 `check-ipc-registration.mjs --strict` 验证。

### ADR-004：串行拉取 + 进度回显 + 软上限

- 并行拉取会瞬间打出 N 倍请求，可能压垮本机后端；串行更安全，且天然给出「第 x/N 个会话」进度。
- `pageSize` 固定 100（后端硬上限，>100 会被降为 20）。
- 单会话内沿用既有防死循环判定（无新增 / 游标未前进即停）。
- 总量软上限 `MAX_TOTAL_MESSAGES`（默认 200000）：超出即停止并提示，避免内存与文件过大。

## 4. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 会话多 / 历史长，备份耗时长 | 用户等待、感知卡死 | 串行 + 实时进度文案（第 x/N 个会话）+ 可预期的分页次数 |
| 单会话拉取失败（网络/后端断） | 整体备份中断 | 单会话 try/catch 跳过并计入 `failedSessions`，其余照常导出，结束汇总提示 |
| 本地会话列表不含全新设备的历史会话 | 备份不完整 | 本地会话表由消息驱动维护，有消息即有会话；且备份前提示以本地会话为范围 |
| 大文件落盘 | 内存/磁盘 | 软上限截断 + 保存路径由用户自选 |
| 新增 IPC 未注册 | 功能静默失效且构建无感 | 在 `index.js` 注册并跑 `--strict` 门禁（历史教训） |

## 5. 依赖

- 后端 5050 在线、MySQL/Redis 可用、登录 token 有效（与单会话云端导出同前提）。
- 现有 `loadHistoryMessage` 行为不变（游标严格小于、pageSize≤100）。

## 6. 数据影响

无。只读服务端消息与本地会话表，不写库、不改 `easychat.sql`、不新增表。

## 7. 验证方式

- `electron-vite build --outDir out-verify` 通过。
- `check-api-contract.mjs`（无契约变更，应仍 0 孤儿 0 漂移）。
- `check-openspec-hygiene.mjs` 通过。
- `check-ipc-registration.mjs --strict`（新增通道已注册）。
- 活体：后端在线时用真实账号在设置页发起备份，确认产出文件含多个会话分段、条数与预期一致。

## 8. 回退

纯前端能力。移除设置页入口与路由即停用；无后端/数据改动，回退零成本。
