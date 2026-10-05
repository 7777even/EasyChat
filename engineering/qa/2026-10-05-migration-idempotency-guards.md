# QA — 迁移脚本幂等性守卫（遗留 #10）

- 日期: 2026-10-05
- 效率等级: **L4**
- Change: `openspec/archive/2026-10-05-migration-idempotency-guards`
- 关联台账: `docs/system-facts.md` §14 **#10**

## 范围

| 项 | 内容 |
|---|---|
| 改什么 | `001` / `002` / `006` / `007` / `009` 五份脚本的 **13 个结构性 DDL 子句**改为带存在性探针的动态 SQL；`verify_migration_flyway.mjs` 新增第 5 节正向强制 |
| 不改什么 | 最终表结构（逐项比对为空）、`pom.xml`、`application.properties`、应用代码、前端 |
| 生产改动文件 | **5 份 SQL + 1 个门禁脚本** |

## 关键前提：为什么改这些文件不会动 checksum

Flyway 的 `baseline-version=N` 把 **≤ N 的迁移整体标记为已应用、且不逐条记账**。实测本机库 `flyway_schema_history` **只有 1 行**：

```
installed_rank  version  description                checksum  success
1               12       << Flyway Baseline >>       NULL      1
```

即 001–012 全部被跳过、**从未写入 checksum**。按 `application.properties` 记载的两种部署（全新 `baseline-version=12`；存量由运维声明真实版本号，示例 9），001–009 均在 baseline 之下 → 改这些文件**不触发** `validate-on-migrate` 校验失败。

**已知残余风险**：声明了 `baseline-version ≤ 8` 的环境会执行并记账 006/007/009，改动将导致 checksum 不匹配而启动失败，修复手段是运维执行一次 `flyway repair`。该情形不在文档约定的部署矩阵内，已在 proposal 中列为已知残余风险并经人工确认。

## 验收口径与实际执行

### 修复前实证（缺陷是真的）

```
001-group-management        exit=1  ERROR 1060 (42S21) at line 9:  Duplicate column name 'role'
002-message-reliability     exit=1  ERROR 1060 (42S21) at line 9:  Duplicate column name 'seq'
006-report-admin            exit=1  ERROR 1060 (42S21) at line 11: Duplicate column name 'handle_user_id'
007-sensitive-word-admin    exit=1  ERROR 1060 (42S21) at line 21: Duplicate column name 'delete_flag'
009-message-delete          exit=1  ERROR 1060 (42S21) at line 23: Duplicate column name 'delete_flag'
```

### 修复后实证（逐份连跑两次）

| 脚本 | 第 1 次 | 第 2 次 | ERROR |
|---|---|---|---|
| 001-group-management | exit 0 | exit 0 | 无 |
| 002-message-reliability | exit 0 | exit 0 | 无 |
| 006-report-admin | exit 0 | exit 0 | 无 |
| 007-sensitive-word-admin | exit 0 | exit 0 | 无 |
| 009-message-delete | exit 0 | exit 0 | 无 |
| 011-privacy-settings（回归对照） | exit 0 | exit 0 | 无 |

**共 12 次执行全部 exit 0、零 ERROR。**

### 结构零变化

`information_schema` 全库结构快照（列名 + 类型 + 默认值 + 可空 + 键 + 索引唯一性，共 **291 行**）在补齐前后 `Compare-Object` 结果为**空**。

证据：`engineering/qa/evidence/2026-10-05-schema-before.txt`、`2026-10-05-schema-after.txt`。

### 门禁

| 门禁 | 结果 |
|---|---|
| `node scripts/verify/verify_migration_flyway.mjs` | exit 0，断言数由 **28 → 65** |
| `node scripts/verify/verify_schema_drift.mjs` | exit 0 |
| `node scripts/check-openspec-hygiene.mjs` | exit 0 |

### 变异检验（证明新断言有判别力）

```
[基线] ✓ 通过，后续「捕获」可归因于变异本身
[捕获] ★★★ 整段守卫被删 → 退回裸 ALTER（修复前的写法）
[捕获] ★★ 只把存在性探针注释掉（保留 SET @ddl/PREPARE/EXECUTE 机制）→ 恒执行 ALTER，重跑报 1060
[已登记·不可静态检测] 探针条件写错（TABLE_SCHEMA 指向错库）—— 真机跑两次兜底
[捕获] ★★ 另一份脚本（002）的索引守卫被整段删 → ADD INDEX 无守卫
[捕获] ★ 守卫块完好，但在文件末尾追加一条裸 DDL（守卫与 DDL 分离）
[已登记·不可静态检测] 探针比较方向写反（> 0 → < 0）—— 与 §2.1 第 3 条同类，真机跑两次兜底
[捕获] ★★★ 002 的无反引号 ADD INDEX 守卫被删（首版正则盲区所在）
[无害·等价] 探针换成 SHOW COLUMNS 形式（MySQL 5.7 合法替代写法，应判为已守卫）

=== 结论：5/5 个应捕获的变异被捕获，漏网 0，无效 0，豁免 3 ===
✓ 门禁有判别力
```

### 豁免项的兜底已实测，不是声明

「探针比较方向写反」属**不可静态检测**（静态断言只能验证字面量，无法验证条件方向，与 AGENTS §2.1 第 3 条的先例同类）。已**实测**其兜底有效性：

```
把 009 的 'delete_flag') > 0,  改写为  'delete_flag') < 0,
  第1次 exit=1  ERROR 1060 Duplicate column name 'delete_flag'
  第2次 exit=1  ERROR 1060 Duplicate column name 'delete_flag'
```

守卫恒假 → 每次都执行 ALTER → 立刻暴露。「跑两次」这一验证手段**确实能覆盖该盲区**。

## 过程中发现并修正的问题

| # | 问题 | 发现方式 | 处置 |
|---|---|---|---|
| 1 | **台账 #10 自身失准**：称 6 份（含 011），实测 011 的 `ADD COLUMN` **全部已有守卫** | 逐语句扫描 | 订正为 5 份 |
| 2 | **我自己的盘点少算一半**：初版只数到 6 个 `ADD COLUMN`，实为 **13 个子句** —— 001/002/006 的单条 `ALTER` 内含多列，而正则每条语句只取第一个匹配 | 按语句清点时发现 | 重做盘点，按子句补齐 |
| 3 | **新增门禁断言自身空转**：初版 `STRUCT_DDL_RE` 的尾反引号**必需**，于是 002 里 `ADD COLUMN seq` / `ADD INDEX idx_session_seq` 这类**无引号**标识符扫不到 → 断言因「没扫到任何 DDL」而通过 | 变异「002 索引守卫被删」**漏网** | 改为尾反引号可选 |
| 4 | **判定窗口过松**：初版取「向上 1200 字符内有 `information_schema`」，**相邻语句的守卫替当前语句背书** | 3 条变异同时漏网 | 改为**按语句边界**判定 |
| 5 | **迁移脚本守卫块形态不一致**：011/009 是分行式，001/002/006/007 是一行式 | 形态校验脚本 | 统一为 011 参考实现的分行式（13 处） |
| 6 | 变异脚本自身两处无效用例：① 硬编码锚点空格数与文件不符（`TABLE_NAME =` 实为三个空格）② 先替换整个守卫块、再去找块内的 `PREPARE` 行（已被自己删掉） | 报 `[无效]` | 改用正则定位 / 改为在文件末尾追加裸 DDL |

## 未运行项

| 未运行 | 原因 | 风险评估 |
|---|---|---|
| CI 全量流水线 | 本机 Docker 无法拉取镜像（`registry-1.docker.io` 返回 EOF），compose 的 `migrate` service 跑不起来 | **低**。`verify_migration_flyway.mjs` 与 `verify_schema_drift.mjs` 均已本地实跑 exit 0，且两者都在 CI 中有独立 job |
| 在**真·空库**上验证首次执行 | 需另建一个空库导入 `easychat.sql` 前的状态 | **低**。守卫为假时执行的是与原来**逐字相同**的 DDL（唯一差别是把多列 `ALTER` 拆成逐列）；且 `011` 早已在真实路径上跑过同一套写法 |
| 各部署环境的 baseline-version 实际取值 | 需访问运维环境 | **中**。已在 proposal 列为已知残余风险，并给出 `flyway repair` 的处置手段；建议运维确认是否存在 `baseline ≤ 8` 的环境 |

## 结论

**通过**。修复前后各有一份可复现的实证：修复前 5 份全部 `ERROR 1060`，修复后 12 次执行全部 exit 0，且 `information_schema` 结构快照逐项比对为空。新增门禁断言经变异检验证明有判别力（5/5 捕获），3 项豁免均已定性（1 项等价、2 项不可静态检测）且其中不可静态检测项的兜底手段已实测有效。
