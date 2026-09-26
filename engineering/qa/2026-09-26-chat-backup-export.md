# QA — 跨会话云端全量备份导出

- 变更: `openspec/changes/2026-09-26-chat-backup-export`（L3）
- 日期: 2026-09-26
- 执行人: WorkBuddy

## 1. 范围

跨会话全量备份：把云端导出从「单会话」扩展到「全部会话」。

- 主进程 `exportChat.js`：新增 `exportChatBackup()`（跨会话 TXT 分段 / CSV 增「会话」列），复用既有 `normalizeCloudRow` / `buildContent` / `csvCell` / 保存对话框。
- 主进程 `ipc.js` + `index.js`：新增 `exportChatBackup` 通道并在启动初始化注册（红线）。
- 渲染进程 `utils/cloudBackup.js`（新）：`pullCloudHistory()` 游标翻页取全 + `backupAllSessions()` 串行聚合 + 失败隔离 + 总量软上限。
- 渲染进程 `views/setting/DataBackup.vue`（新）、路由 `/setting/dataBackup`、`Setting.vue` 菜单项。
- `Chat.vue`：删除本地重复的翻页实现，改为引用 `cloudBackup.js`（单一真源）。
- 后端：**无改动**；数据库：`easychat.sql` **无变更**。

## 2. 验收口径（对照 spec-delta）

| # | 场景 | 验收预期 | 验证方式 | 结论 |
|---|------|----------|----------|------|
| C1-1 | 会话枚举 | 能取到当前账号会话列表 | **活体冒烟**（服务端枚举到 2 个） | ✅ |
| C1-2 | 逐会话翻页取全 | `loadHistoryMessage` 游标翻页直到无数据，能正常终止 | **活体冒烟**（2 会话均正常终止） | ✅ |
| C1-3 | 一键产出单文件 | 多会话聚合后落盘为一个文件 | 代码路径 + 构建（落盘需 GUI） | ✅ |
| C2-1 | TXT 按会话分段 | 每段「会话：xxx（单聊/群聊）」+ 消息行 | 代码路径 + 构建 | ✅ |
| C2-2 | CSV 增「会话」列 | 首列标明来源会话，保留 BOM 与转义 | 代码路径 + 构建 | ✅ |
| C3-1 | 进度反馈 | 显示「正在备份第 x/N 个会话」 | 代码路径（`onProgress` 回调） | ✅ |
| C3-2 | 单会话失败隔离 | 失败会话跳过并计数，不中断整体 | **活体冒烟**（不存在会话被 2202 拒绝，其余 2 组不受影响） | ✅ |
| C3-3 | 总量软上限 | 达 200000 条停止并提示截断 | 代码路径 | ✅ |
| C4 | 权限边界 | 依赖服务端 `checkSessionOwner`，非本人会话被拒 | **活体冒烟**（越权会话返回 2202） | ✅ |

## 3. 实际执行命令与用例数

| 命令 | 结果 |
|------|------|
| `electron-vite build --outDir out-verify` | ✓ built in 26.23s，0 error |
| `node scripts/check-api-contract.mjs` | 97 路由 / 95 调用 / 3 主进程调用，0 孤儿 0 漂移 ✓ |
| `node scripts/check-openspec-hygiene.mjs` | 0 错误 / 0 警告 / 0 信息 ✓ |
| `node scripts/check-ipc-registration.mjs --strict` | ipc.js 35 注册 / index.js 37 调用，**全部已注册** ✓ |
| `python scripts/smoke/smoke_chat_backup.py`（活体） | **12/12 PASS**（2 会话 / 31 条消息） |

自动用例：**4 项构建门禁全过 + 活体冒烟 12 项全过**。

## 4. 关键结论：`lastMessageId` 空串安全（实测）

前端 `Request.js:128` 会把 `null` 转成空字符串 `""` 发送。实测后端对 `Long lastMessageId` 的三种形态：

| 传参形态 | 后端响应 |
|----------|----------|
| 省略该参数 | `code=0`，返回 30 条 |
| **`""`（前端实际形态）** | **`code=0`，返回 30 条** |
| `"None"`（错误形态） | `1001 参数类型不匹配` |

结论：Spring 将空串按 `null` 绑定，**前端传 `null` 不会触发 1001**，跨会话备份与既有单会话导出/漫游翻页均不受影响。

## 5. 未运行项（如实标注）

- **GUI 交互**：设置页点击「开始备份」、保存对话框选择路径、TXT/CSV 实际落盘内容校验，需 Electron 界面，沙箱无 GUI，留本机手动确认。
- 手动清单：设置 → 数据备份 → 选 TXT → 开始备份 → 选路径保存 → 打开文件确认含多个「会话：xxx」分段；再以 CSV 重复一次，确认首列为「会话」且 Excel 打开不乱码。

## 6. 结论

跨会话全量备份能力已实现：构建与全部门禁通过；核心取数链路（会话枚举 → 逐会话游标翻页 → 失败隔离 → 越权拦截）已由**活体冒烟 12/12 在真实后端验证**；落盘格式与 GUI 交互经代码路径审查符合预期、留本机手动确认。后端与数据库零改动，回退成本为零。

## 证据

- 活体冒烟输出（12/12 PASS）见同目录 `2026-09-26-chat-backup-live-smoke.txt`
- 冒烟脚本见 `scripts/smoke/smoke_chat_backup.py`
