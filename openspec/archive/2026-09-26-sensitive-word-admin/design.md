# Design — 敏感词库管理端

- 关联 Proposal: 2026-09-26-sensitive-word-admin/proposal.md
- 创建日期: 2026-09-26

## 1. 架构设计

沿用管理端既定模式「一域一 Controller 一 Service 一页」（与 `AdminReportController`/`ReportList.vue` 同构），与既有 6 个 admin 页保持一致。

```
AdminSensitiveWordController ──> SensitiveWordAdminService ──> SensitiveWordMapper (XML)
                                  │  (查重/校验/计数/写后热更)
                                  └──调用──> SensitiveWordService.reload()  (既有引擎，不动 filter 逻辑)
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | 新增 `AdminSensitiveWordController` 五端点 | 路由 + `@Valid` + 调 Service，不写业务不碰 Mapper |
| Service | 新增 `SensitiveWordAdminService(+Impl)`；修改 `SensitiveWordServiceImpl` 加载 SQL 加 `delete_flag=0` | 查重转 2704、删除校验 2705、导入逐行容错与计数、写后调 `reload()`；事务边界 |
| Mapper / SQL | 新增/扩展 `SensitiveWordMapper` + XML：分页筛选、`selectByWord`、逻辑删除、批量插入 | 只写 SQL，`#{}` 绑定，`@Param("bean")` 时 XML 必须 `bean.` 前缀（本次 report-admin 冒烟踩过的坑，写入验收断言） |
| Entity | `SensitiveWord` 补 `deleteFlag` 字段，1:1 对应表 | 持久化对象 |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 主进程 / preload | 无改动（导出走 HTTP 文件流，不经 IPC） | — |
| 渲染进程 | 新增 `views/admin/SensitiveWord.vue`；`Admin.vue` 菜单、`router` 子路由 `sensitiveWord`、`Api.js` 5 端点 | 只经 `request.js` 访问接口，遵守 `Message.js`/`Confirm.js` 既有能力边界 |

## 2. 接口设计（均为 `@GlobalInterceptor(checkAdmin = true)`，非管理员 → CODE_404）

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/admin/sensitiveWord/loadWord` | POST | `pageNum/pageSize` + `keyword/level/status` | `Result<PageResult<SensitiveWordVO>>` | admin |
| `/api/admin/sensitiveWord/saveWord` | POST | `id?/word/level/status`（有 id 即编辑） | `Result<Void>` | admin |
| `/api/admin/sensitiveWord/deleteWord` | POST | `id` | `Result<Void>` | admin |
| `/api/admin/sensitiveWord/importWords` | POST | multipart `file` + `level/status`（txt 用） | `Result<ImportResultVO{success,skipped,failed}>` | admin |
| `/api/admin/sensitiveWord/exportWords` | GET | 无（文件流，非 `Result` 包络，对齐既有 `downloadFile` 先例） | `text/csv`（UTF-8 BOM） | admin |

导入约束：≤5000 行、≤2MB；`.txt` 一行一词统一套 `level/status`；`.csv` 三列 `word,level,status`；扩展名之外拒收（1001）。

### 错误码

| 码 | 含义 | 分段依据 |
|----|------|---------|
| `2704`（新增） | 词条已存在 | 27xx 敏感词段（紧邻 2701） |
| `2705`（新增） | 词条不存在 | 同上 |
| `2701`（既有） | 发送链路 level=3 拦截 | 不变 |
| `1001`（既有） | 参数非法（文件超限/扩展名/列格式错） | 通用段 |

## 3. 数据模型（L4）

| 表 | 变更 | 字段 | 类型 | 说明 |
|----|------|------|------|------|
| `sensitive_word` | 新增字段 | `delete_flag` | `BIGINT NOT NULL DEFAULT 0` | 0=存活；非0=删除时间戳 ms |
| `sensitive_word` | 新增索引 | `uk_word_flag(word, delete_flag)` | UNIQUE | 存活行同词唯一；删除行时间戳互异 → 删后可重导 |

> 同步 `easychat.sql`；迁移 `easychat-migration-007-sensitive-word-admin.sql`（手动执行）。

## 4. 安全设计

- 鉴权: 五端点全部 `checkAdmin=true`，非管理员 CODE_404（与举报管理端一致）
- 数据权限: 词库为全局运营数据，不区分用户归属；仅管理员可读写
- 输入校验: `@Valid` 校验 `word` 非空长度 ≤64、`level∈{1,2,3}`、`status∈{0,1}`；导入逐行同规则校验，越界行计失败
- SQL 注入防护: 全 `#{}` 绑定；导入批量插入用 `foreach` 参数绑定，无字符串拼接
- csv 安全: 导出 UTF-8 BOM + 对 `=+-@` 开头值前置单引号（对齐 chat-record-export 既有防护）

## 5. ADR

### ADR-001: `delete_flag` 用 BIGINT 删除时间戳而非 tinyint 0/1

- 状态: 已接受（待 L4 人工确认）
- 上下文: 需要逻辑删除（§6.4 优先 status 语义）+ `word` 唯一索引（导入查重兜底）。MySQL 5.7 无部分索引：若唯一索引仅落 `word`，被删词条占住唯一位，**删后无法重导同一词**；若 `delete_flag` 为 0/1 且唯一键 `(word, delete_flag)`，同一词最多只能删一次。
- 决策: `delete_flag BIGINT`，0=存活、非0=删除时间戳 ms；唯一键 `(word, delete_flag)`。存活行保证同词唯一；删除行时间戳互异，同词可反复删→导→删。
- 正面: 单字段同时满足逻辑删除标记 + 删除审计时间 + 唯一性共存。负面: `delete_flag` 名为 flag 实存时间戳，需在字段注释与代码注释写明语义；导入查重必须显式 `delete_flag=0`。

### ADR-002: 方案选型——独立 Controller/页面（已选定）

- 状态: 已接受
- 上下文: 三选一——①独立一域一 Controller 一页面（对齐 ReportList 等既有模式）；②挂进 SysSetting 页；③Controller 直连 Mapper。
- 决策: ①。②会让 AdminSettingController 职责膨胀且 tab 装不下导入/筛选；③直接违反 AGENTS.md §3。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 引擎加载 SQL 漏加 `delete_flag=0`，已删词仍生效 | 中 | 中 | 列入 tasks 验收断言：删除后 reload，用该词发送不得触发 2701 |
| `@Param("bean")` 与 XML 裸 `#{}` 不匹配（report-admin 同款坑） | 中 | 高 | Mapper XML 一律 `bean.` 前缀；冒烟覆盖分页/插入/删除全路径 |
| `reload()` 与发送链路并发读词库树 | 低 | 中 | 沿用既有 `reload()` 的替换实现（其为整体重建引用替换，本变更不改其机制） |
| 唯一索引与导入跳过逻辑不一致，导入报 500 | 中 | 中 | 导入先查后插 + 捕获 DuplicateKey 统一转「跳过计数」，不向上抛 |
| 迁移未执行导致接口全挂 | 中 | 高 | 前置检查任务：启动前确认 `delete_flag` 存在（与 005/006 同策略，写入 QA 前置项） |

## 7. 依赖与前提

- 无未归档 Change 依赖；`content-moderation`（引擎）已归档稳定
- 人工准备：实施前过 proposal 的 **L4 二次关卡**（delete_flag 语义 + 2704/2705 错误码两项）
- 环境：migration-007 需在目标库手动执行；后端重启生效
