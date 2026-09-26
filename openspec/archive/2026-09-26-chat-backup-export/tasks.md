# Tasks — 跨会话云端全量备份导出

- 关联 Design: `2026-09-26-chat-backup-export/design.md`
- 创建日期: 2026-09-26
- 预估总工时: 7h

> 任务按实施顺序排列；单条 ≤2h。

## 阶段一：主进程备份导出与 IPC 通道

- [x] `exportChat.js` 新增 `exportChatBackup()`：跨会话格式化（TXT 按会话分段标题 / CSV 增加「会话」列），复用既有 `normalizeCloudRow` / `buildContent` / `csvCell` / `buildDefaultPath` / 保存对话框 — ≤1h
- [x] `ipc.js` 新增 `onExportChatBackup`：`ipcMain.on('exportChatBackup')` → 调 `exportChatBackup(data)` → `e.sender.send('exportChatBackupCallback', result)` — ≤20min
- [x] `index.js` 启动初始化中调用 `onExportChatBackup()`（**红线：未注册则运行时静默失效且构建无感**）— ≤10min
- [x] 跑 `node scripts/check-ipc-registration.mjs --strict` 验证新通道已注册 — ≤10min（35 注册 / 37 调用，全部已注册 ✓）

## 阶段二：渲染进程取数与聚合

- [x] 新增 `utils/cloudBackup.js`：`pullCloudHistory(sessionId)` 用 `loadHistoryMessage` 游标翻页取全（pageSize=100 后端上限、无新增/游标未前进即停防死循环）— ≤1h
- [x] `backupAllSessions(onProgress)`：串行遍历会话列表聚合 `groups=[{sessionId, title, contactType, messages}]`，实时回调进度（第 x/N 个会话）— ≤1h
- [x] 单会话拉取失败隔离：`try/catch` 跳过该会话并计入 `failedSessions`，不中断整体备份 — ≤30min
- [x] 总量软上限 `MAX_TOTAL_MESSAGES`（默认 200000），超出即停止并提示，避免内存/文件过大 — ≤20min
- [x] `Chat.vue` 去掉本地重复翻页实现，改为引用 `utils/cloudBackup.js` 的 `pullCloudHistory`（单一真源）— ≤15min

## 阶段三：设置页入口与 UI

- [x] 新增 `views/setting/DataBackup.vue`：格式选择（TXT / CSV）+「开始备份」按钮 + 进度文案 + 结果提示（条数/会话数/失败会话）— ≤1.5h
- [x] 路由新增 `/setting/dataBackup`（挂到设置布局下）— ≤15min
- [x] `Setting.vue` 菜单新增「数据备份」项（图标 `icon-download` + 路径 + 配色 `#7c5cff`）— ≤15min
- [x] 会话列表为空时提示「暂无可备份的会话」，不发无谓请求 — ≤15min

## 阶段四：验证

- [x] `electron-vite build --outDir out-verify` 通过 — ≤30min（✓ built in 26.23s，0 error）
- [x] `check-api-contract` / `check-openspec-hygiene` / `check-ipc-registration --strict` 三门禁全绿 — ≤20min
- [x] 活体验证：`scripts/smoke/smoke_chat_backup.py` **12/12 PASS**（2 会话 / 31 条消息，翻页终止、失败隔离、越权 2202 拦截）；GUI 交互（设置页点击 / 保存对话框 / 落盘格式）沙箱无 GUI，留手动验证清单见 QA §5 — ≤1h

## 阶段五：收尾

- [x] QA 落 `engineering/qa/2026-09-26-chat-backup-export.md`（含构建/门禁存证 + 活体证据 + 手动验证清单）— ≤30min
- [x] Retro 落 `engineering/retro/2026-09-26-chat-backup-export.md` — ≤30min
- [x] spec-delta 回写 `openspec/specs/chat-backup/spec.md` + 归档到 `openspec/archive/2026-09-26-chat-backup-export` — ≤30min
- [x] 回写 `docs/system-facts.md` 变更日志一行（备份能力上线）— ≤15min
- [x] 按域拆分串行提交（main / renderer / docs），不推送 — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-26-chat-backup-export/tasks.md` 全部勾选
- [x] `electron-vite build` 0 error
- [x] 本次无后端契约 / 数据结构变更，无需同步 `easychat.sql`
- [x] 新增 IPC 通道已在 `index.js` 注册并通过 `--strict` 门禁
- [x] 归档闭环完成（spec-delta 回写 specs/ + 归档到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
