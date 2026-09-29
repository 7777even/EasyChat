# Proposal：本地 SQLite 会话表补 no_disturb / draft 两列

- 创建日期: 2026-09-29
- 效率等级: **L4**（前端 `Tables.js` 本地 db 迁移 → `easychat-front/AGENTS.md` §5；根 §8 数据库结构变更）

## Why

本地 SQLite `chat_session_user` 表只有 `top_type`，**缺失 `no_disturb` 与 `draft` 两列**（`Tables.js` 未声明、`alter_tables` 为空）。而 ADB 列映射按 `PRAGMA table_info` 构建，查不到的键会被静默过滤，导致：

1. `saveSessionDraft` / `setSessionNoDisturb` IPC 落本地时 `dbColumns` 为空；
2. 服务端 `-7`（SYNC_SESSION_USER）同步帧回写同样被过滤；
3. 后果：**重启后草稿不恢复、免打扰状态丢失**，通知抑制（`Chat.vue:751` 要求 `session.noDisturb == 1`）与免打扰菜单状态读到 `undefined`；
4. 在 2026-09-29 补空列守卫之前，空 `dbColumns` 还会拼出 `update <t> set  where ...` → SQLITE_ERROR 弹原生模态框**阻塞主进程**（走查发现 #3 的真实根因之一）。

服务端 `chat_session_user` 早已有 `no_disturb`/`draft` 两列（migration-004），**服务端是真源**，本变更只补齐本地缓存表，使本地读写路径与服务端字段对齐。

## What Changes

- 后端：**零改动**。
- 前端主进程：`src/main/db/Tables.js`
  - `add_tables` 的 `chat_session_user` DDL 增 `no_disturb integer default 0`、`draft varchar default null`（覆盖新建库）；
  - `alter_tables` 新增两条存量迁移（ADB `createTable()` 已具备「查 `PRAGMA table_info` 无该列才执行」的幂等逻辑）。
- 渲染进程：零改动（读写路径本就按 `noDisturb`/`draft` camelCase 走）。
- 数据库：**本地 SQLite 表结构变更（L4）**；服务端 MySQL 结构不变，无需 `easychat.sql` 同步。

## Capabilities

- C1: 存量本地库启动时自动补列（幂等，只在缺列时 ALTER）
- C2: 草稿本地持久化与恢复（写入读回 + 重启后可恢复）
- C3: 免打扰本地持久化（写入读回，通知抑制与菜单状态可读到真实值）
- C4: 空值安全（同步帧 `draft=null` 被 Model 层与 ADB 双重短路，不产生非法 SQL）

## Impact

- 对外接口: **无变**（零新增/修改端点，契约门禁 0 漂移）
- 存量数据: 影响所有已有本地库用户，启动时自动 ALTER 补列；列带默认值（`no_disturb default 0` / `draft null`），存量行取值安全，无数据回填需求
- 性能 / 安全: 两条 ALTER 在启动建表阶段一次性执行，幂等跳过；无性能影响。无新增攻击面
- 回退方案: 代码回退即可（新列对旧代码透明——旧代码本就查不到这两列而过滤它们）；本地缓存可删除重建，不影响服务端真源

---

## ⛔ 人工确认关卡

> 本提案经 **用户（会话内人工指令）** 于 **2026-09-29** 确认，允许进入 design / 实施阶段。
> 确认形式：question 工具决策「本地 SQLite 缺 draft/no_disturb 两列…如何处置？」→ 用户选择 **「现在补列并修复（推荐）」**。
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估

> **流程偏差如实记录**：本变更命中 L4，按 §7.1 应「四件套 + 确认关卡完成后才写代码」；实际执行顺序为 **先人工确认 → 写码 → 验证 → 补齐四件套**（确认关卡本身未被绕过，仅文档滞后于实现）。该偏差已记入 `engineering/retro/`。
