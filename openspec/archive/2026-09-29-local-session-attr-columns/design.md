# Design：本地 SQLite 会话表补 no_disturb / draft 两列

- 关联 Proposal: `2026-09-29-local-session-attr-columns/proposal.md`
- 创建日期: 2026-09-29

## 现状与根因

```
渲染层 MessageSend.vue saveDraft ──ipc saveSessionDraft──┐
渲染层 Chat.vue setSessionNoDisturb ──ipc setSessionNoDisturb──┤
服务端 -7 SYNC_SESSION_USER ──wsClient.updateSessionAttr──┤
                                                          ▼
                                    ChatSessionUserModel#updateSessionAttr
                                                          ▼
                                    ADB#update → globalColumnsMap[table] 查列名
                                                          ▼
                              列不存在 → dbColumns=[] → 过滤丢弃（守卫前：拼出非法 SQL 崩溃）
```

`ADB.js` 的 `globalColumnsMap` 由 `initTableColumnsMap()` 遍历 `PRAGMA table_info` 构建（camelCase ←→ snake_case）。**列不在表里 = 写入被静默吞掉**，这是本地缓存表字段缺失时的统一失效模式。

## 决策

### ADR-1：列定义落 `Tables.js` 的 `add_tables` + `alter_tables` 双落点

- `add_tables` 补列 → 覆盖**新建库**（DDL 即真源）；
- `alter_tables` 补两条 → 覆盖**存量库**，复用 ADB 既有幂等逻辑（`fieldList.some(row.name === item.field)` 才执行），**不新写迁移框架**。
- 被否方案：启动时裸跑 `alter table` 不做存在性判断（重复启动会抛 duplicate column）。

### ADR-2：列类型与默认值对齐服务端语义

| 列 | 类型 | 默认 | 依据 |
|---|---|---|---|
| `no_disturb` | `integer` | `0` | 服务端 PO `Integer noDisturb`，0 正常 / 1 免打扰；默认 0 = 不抑制通知 |
| `draft` | `varchar` | `null` | 服务端 `String draft`，空串由 `saveSessionDraft` 归一化为 `""` |

### ADR-3：空值守卫保持双重，不因补列而移除

- `ChatSessionUserModel#updateSessionAttr`：`attrValue == null` 直接返回（同步帧可能不带值）；
- `ADB#update`：空 set / 空 where 短路返回 `0`。

补列只解决「列存在」，不解决「值可能为 null」，两层职责不同，**均保留**。

### ADR-4：三层交互时序（前端 AGENTS §4 要求）

```
[渲染] saveDraft(draft) ──send──► [主进程 ipc] saveSessionDraft
                                        │ updateSessionAttr(contactId,'draft',draft)
                                        ▼
                                   [db] ADB#update → 列存在 → set draft = ? → 落库 ✓
服务端 -7 帧 ──► [主进程 wsClient] ──► updateSessionAttr ──► 同上 ✓
                                        │ sender.send('syncSessionUser')
                                        ▼
[渲染] Chat.vue 更新内存 session.draft / noDisturb（UI 即时反映）
下次启动 loadSessionData ──► selectUserSessionList ──► 列存在 → 恢复草稿/免打扰 ✓
```

## 风险与依赖

- 风险：ALTER 在启动 `db.serialize` 中执行，若用户本地库损坏会启动失败 —— 该风险为既有行为（建表本就在此执行），本变更未扩大。
- 依赖：无新增依赖；`easychat.sql`（服务端基线）不受影响。
- 存量数据影响：两条 ALTER 均带默认值，存量行无需回填。
