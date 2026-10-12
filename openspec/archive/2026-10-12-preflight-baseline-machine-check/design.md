# Design — 前置闸门补机控

## 架构

```
preflight-baseline-check.mjs （IO 层：fs / child_process / process.exit）
    │  读 application.properties、列迁移文件、找 mysql、跑 SQL
    ▼
preflightBaselineCore.mjs （纯函数：fs / child_process 零依赖）
    ├─ configuredBaselineVersion(env, propsText) → { version, source }
    ├─ maxMigrationVersion(fileNames)              → number
    ├─ parseBaseline(sqlText)                      → Map<表, Set<列>>
    ├─ parseLiveColumns(queryOutput)               → Map<表, Set<列>>
    ├─ computeDiff(baseline, live)                 → { missingTables, missingCols }
    └─ decidePreflight(input)                      → { exitCode, reason, kind }
    ▼
verify_preflight_baseline_core.mjs （行为测试，直接 import 核心）
```

**关键**：`decidePreflight` 返回 `exitCode` 与 `reason`，IO 层只负责
打印 `reason` 并 `process.exit(exitCode)`。判定本身不再有任何分支方向可被文本掩盖。

## 决策（ADR）

### ADR-001: 抽「判定」而不是抽「读文件」

只把 `decidePreflight` + `computeDiff` 抽出，**不**把 `mysqlAvailable` / `query` 抽出。
理由：后两者是纯 IO 接线，抽出后仍不可测（要起进程），却增加了 indirection。
判定与差异计算才是「分支方向不可静态检测」的那部分。

### ADR-002: `decidePreflight` 返回结构化 reason 而非直接 exit

若让核心函数直接 `process.exit`，它在测试里会**杀死测试进程**，
无法逐个判定点断言。返回 `{ exitCode, reason, kind }` 后：
- IO 层：`console[errorCode===0?'log':'error'](reason); process.exit(exitCode)`
- 测试层：`expect(decidePreflight(input)).toMatchObject({ exitCode: 1 })`

`kind` 用于**穷举性自检** —— 测试可断言所有输入组合都命中某个已知 kind，
防止「某条分支被删掉导致悄悄落到 else」而无人察觉（§2.1 第 14 条：断言通过 ≠ 断言在做事）。

### ADR-003: 保持 fail-closed 语义不变

原脚本有两处 fail-closed（找不到 mysql 客户端、连库失败）。这两处在**核心之外**，
IO 层各自 `exit(1)`。核心只处理「已拿到数据」的判定，
但 `decidePreflight` 仍保留 `tableCount` 与 `hasHistory` 两个输入以覆盖放行分支。

### ADR-004: `configuredBaselineVersion` 也抽出来

它含一条**极关键的方向性判断**：env 优先于 properties 默认值。
2026-10-04 曾在自查中发现「只读 properties 默认值」会把运维声明的真实版本号堵死
（`docs/system-facts.md` §14 #9 记载）。这条判断同样无法从静态断言看出方向，
故一并抽出并配行为测试。

## 风险

| 风险 | 缓解 |
|---|---|
| 抽离时改变闸门行为 | 行为测试逐条锁定改写前的**实际**判定结果（含 6 个放行/拒绝分支） |
| 新旧两处判定并存，日后分叉 | 核心为唯一真源，IO 层**不得**再写判定；门禁断言 IO 层不含结构判定关键字 |
| `process.exit` 在核心中被误引入 | 门禁静态断言核心文件**不含** `process.exit` / `require('fs')` 等 |

## 依赖

无新增依赖。纯 ESM，node 直接可跑（与既有 `verify_*.mjs` 同款）。

## 数据影响

**零**。无表结构变更、无字段变更、无数据迁移。