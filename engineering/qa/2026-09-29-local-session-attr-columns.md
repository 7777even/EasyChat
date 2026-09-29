# QA — 本地 SQLite 会话表补 no_disturb / draft 两列

- 日期: 2026-09-29
- 效率等级: **L4**（前端 `Tables.js` 本地 db 迁移，人工确认关卡已过）
- 范围: `easychat-front/src/main/db/Tables.js`（`add_tables` DDL + `alter_tables` 迁移）；受影响链路：IPC `saveSessionDraft` / `setSessionNoDisturb`、WS `-7` 同步帧回写、会话列表加载（草稿恢复 / 通知抑制）。后端零改动。

## 验收口径

1. **C1 存量自动补列**：启动后 `chat_session_user` 存在 `no_disturb`、`draft` 两列；重复启动不报 duplicate column（ALTER 幂等跳过）。
2. **C2 草稿持久化**：`draft` 写入后可读回；`loadSessionData` 读回的会话含该值。
3. **C3 免打扰持久化**：`noDisturb=1` 写入后 `no_disturb` 为 `1`，供 `Chat.vue` 通知抑制与菜单状态读取。
4. **C4 空值安全**：同步帧 `draft=null` 被 `updateSessionAttr` 短路；`ADB#update` 空 set/where 返回 0 不执行非法 SQL。
5. 构建与三门禁全绿（§2 矩阵：结构变更 → 结构验证 + 构建验证）。

## 实际执行命令与结果

| 项 | 命令/方式 | 结果 |
|---|---|---|
| 失败取证 | dump 活体 `~/.easychatdev/local.db` `PRAGMA table_info` | 补列前确证仅 11 列，**无 `no_disturb`/`draft`** |
| 迁移验证 | esbuild 打包测试入口直连真实 `sqlite3`（`NODE_ENV=development`，走真实 `ADB.init()` 建表+迁移链路） | **5/5 PASS**（见证据） |
| 前端构建 | `electron-vite build --outDir out-verify` | ✓ built 23.33s，产物已移出仓库 |
| 契约门禁 | `check-api-contract.mjs` | 97 路由 / 95 调用 / **0 孤儿 0 漂移** |
| IPC 门禁 | `check-ipc-registration.mjs --strict` | 35 注册 / 35 export / 37 调用，**全注册** |
| 卫生门禁 | `check-openspec-hygiene.mjs` | 归档后复跑（见下） |

迁移验证逐条（证据文件 `2026-09-29-local-session-attr-columns-evidence.txt`）：

```
[PASS] 迁移后存在 no_disturb 列   | ...top_type,no_disturb,draft
[PASS] 迁移后存在 draft 列
[PASS] draft 写入并读回           | got={"draft":"测试草稿abc"}
[PASS] noDisturb 写入并读回       | got={"noDisturb":1}
[PASS] null 草稿仍被守卫短路      | ret=0 err=null
5/5 PASS
```

证据同时记录了真实执行的 SQL：`alter table chat_session_user add column no_disturb integer default 0` 与 `add column draft varchar` **各执行一次**（首跑），随后的复跑走 `PRAGMA table_info` 存在性判断跳过 —— 幂等性实证。

## 未运行项

- **Electron GUI 内的端到端体验**（输入草稿 → 重启 → 输入框回填；免打扰开关 → 重启 → 菜单状态）：沙箱无 GUI，以「真实 sqlite3 层读写往返」+ 代码路径审查覆盖，**未做实机 UI 走查**，需本机目验。
- 服务端链路冒烟：本变更后端零改动，不适用。
- 测试行已清理（`delete from chat_session_user where user_id='__schema_mig_test__'`），不污染真实数据。

## 结论

**达成**：C1–C4 四条验收口径均由真实 sqlite3 层执行验证，构建与门禁全绿。遗留：GUI 端到端目验需本机执行（非阻断，属体验确认）。

> 过程偏差如实记录：按 §7.1 本应「四件套齐 + 确认关卡后才写代码」，实际为 **确认 → 写码 → 验证 → 补四件套**（人工确认未被绕过，仅文档滞后）。已记入 Retro。
