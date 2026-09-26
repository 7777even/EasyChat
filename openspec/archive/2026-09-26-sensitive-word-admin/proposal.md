# Proposal — 敏感词库管理端（CRUD + 批量导入导出 + 热更新）

- 创建日期: 2026-09-26
- 效率等级: L4（`sensitive_word` 表字段 + 唯一索引变更，命中 AGENTS.md §8 数据库结构；新增错误码分段项命中 §8 契约语义）

## Why

内容治理 Change 已交付敏感词过滤引擎（`SensitiveWordService`，命中 level=3 抛 2701、level1/2 替换 `***`），但**词库没有任何维护入口**：全仓无敏感词 CRUD 接口与管理页面，`reload()` 零调用方，活体词库 0 行——引擎形同虚设。运营无法把词库建起来，过滤能力实际为零。

## What Changes

- 后端:
  - 新增 `AdminSensitiveWordController`（`/admin/sensitiveWord/*` 五接口，均 `@GlobalInterceptor(checkAdmin = true)`）
  - 新增 `SensitiveWordAdminService`（分页筛选 / 保存含查重 / 逻辑删除 / 导入解析与计数 / 导出）；写成功后调既有 `SensitiveWordService.reload()` 热更
  - 修改 `SensitiveWordServiceImpl` 加载 SQL：加 `AND delete_flag = 0`（否则已删词条仍进过滤树）
  - 新增/修改 Entity `SensitiveWord`（补 `deleteFlag` 字段）、`SensitiveWordMapper` + XML（分页、按 word 查重、逻辑删除、导入批量插入）
  - `ResponseCodeEnum` 新增 `2704 词条已存在`、`2705 词条不存在`（27xx 敏感词段）
- 前端: 新增 `views/admin/SensitiveWord.vue`（筛选列表 + 新增/编辑/删除 + 导入 dialog + 导出按钮）；`Admin.vue` 菜单与 `router` 加子路由；`Api.js` 加 5 端点
- 数据库: **L4** —— `sensitive_word` 新增 `delete_flag BIGINT NOT NULL DEFAULT 0`（0=存活，非0=删除时间戳ms）+ `UNIQUE INDEX uk_word_flag(word, delete_flag)`；同步 `easychat.sql`；新增 `easychat-migration-007-sensitive-word-admin.sql`（手动执行，与 005/006 同策略）

## Capabilities

- C1: 管理员可分页筛选（关键词/级别/状态）敏感词，并新增/编辑/逻辑删除词条；重复新增被拒（2704）、删除不存在词条被拒（2705）
- C2: 管理员可批量导入 txt（统一级别/状态）或 csv（`word,level,status`）词条，系统逐行容错并返回新增/跳过/失败计数；同一词条不会重复入库（唯一索引 + 导入跳过）
- C3: 管理员可导出当前词库为 csv（UTF-8 BOM + 公式注入防护），导出文件可原样导回（格式与导入对称，往返等价）
- C4: 词库任意变更（保存/删除/导入）后服务端自动 `reload()`，新词条即刻参与发送链路过滤（level=3 拦截 2701 / level1-2 替换），已删词条即刻失效
- C5: 逻辑删除与唯一性共存：删掉的词条不占用 `word` 唯一约束，可重新导入/新增同一词条

## Impact

- 对外接口: 新增 5 个 `/admin/sensitiveWord/*` 端点；`check-api-contract` 需同步前端调用；`ResponseCodeEnum` 新增 2 个码（2704/2705），既有码语义不变
- 存量数据: 活体 `sensitive_word` 为 0 行，迁移零风险；新库由 `easychat.sql` 基线直接建出字段与索引；无数据回填
- 性能 / 安全: 词库规模上限由导入限制（5000 行/2MB）约束，`reload()` 全量重建内存树在该量级无压力；全部接口 `checkAdmin`，`#{}` 参数绑定，csv 导出防 Excel 公式注入
- 回退方案: 回滚代码 + `ALTER TABLE sensitive_word DROP COLUMN delete_flag, DROP INDEX uk_word_flag`；过滤引擎回落到「仅 status=1」的原加载逻辑（迁移前行为）

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 2026-09-26 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续（用户 2026-09-26）
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估

> **L4 实施前二次关卡（§7.1，写代码前必过）**，两项需人工点头：
> 1. `delete_flag` 采用 **BIGINT 删除时间戳（0=存活）** 而非 tinyint 0/1 标志位（为与 `word` 唯一索引共存、支持删后重导）
> 2. `ResponseCodeEnum` 新增 **2704 / 2705**（27xx 敏感词段，紧邻既有 2701）
>
> - [x] 两项均同意，允许实施（用户 2026-09-26「两关都过，开工」）
> - [ ] 需修改（说明：__________________）
