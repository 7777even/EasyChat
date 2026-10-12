# Proposal — 前置闸门补机控：抽纯核心 + 行为测试

- 创建日期: 2026-10-12
- 效率等级: L3

## Why

`verify_migration_flyway.mjs` 对 `preflight-baseline-check.mjs` 的断言**全是读源码找字面量**：
检查「拒绝」二字是否出现、`process.exit(1)` 是否存在、是否提到 `information_schema` 与
`SPRING_FLYWAY_BASELINE_VERSION`。

**问题**：静态断言看不出**条件分支的方向**。把放行条件

```js
if (!missingTables.length && !missingCols.length) { 放行 }
```

反写成

```js
if (missingTables.length || missingCols.length) { 放行 }
```

四条断言**全部照样通过** —— 而语义已完全颠倒：结构不符反而放行，
正是本项目吃过两次的「静默跳过迁移」事故。

这是 AGENTS §2.1 第 3 条登记的**不可静态检测项**（`docs/system-facts.md` §14 遗留 #11），
此前按「静态断言固有上限」处理并撤除了该变异用例。**本 Change 换一种办法解决它**：不改断言形式，
而把被测逻辑抽为**可注入依赖的纯函数**并配**行为测试** —— 行为测试**不受静态上限约束**。

## What Changes

- 后端: 不涉及。
- 前端: 不涉及。
- 脚本:
  - 新增 `scripts/migrate/preflightBaselineCore.mjs`：把「判不拒绝」的判定与
    「比对出缺哪些表/列」抽为纯函数（无 fs / child_process 依赖）；
  - `preflight-baseline-check.mjs` 改为调用该核心，**IO 接线不变**；
  - 新增门禁 `scripts/verify/verify_preflight_baseline_core.mjs`，含**方向敏感用例**
    （缺失结构必须拒绝 / 结构一致必须放行），并接 pre-push + CI。
- 数据库: 无变更。

## Capabilities

- C1: 前置闸门的「放行 / 拒绝」判定可被**行为测试**验证，条件分支方向写反会被捕获
- C2: 「活库与基线的差异计算」可被行为测试验证（缺表 / 缺列 / 完全一致 / 基线为空）

## Impact

- 对外接口: 无（内部脚本）。
- 存量数据: 无影响。
- 性能 / 安全: 无。**闸门行为不变**（判定结果与改写前逐一等价，由行为测试锁定）。
- 回退方案: `preflight-baseline-check.mjs` 回到内联判定即可，核心文件可整体删除。

---

## ☑ 人工确认关卡

> 本提案经 **用户（需求方）** 于 **2026-10-12** 确认，允许进入 design 阶段。
> 原始选项：「B. 投 L3 抽纯核心 + 行为测试（成本比上轮估的低）」。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> 立项前已复核改动面：`preflight-baseline-check.mjs` 共 200 行，且**已有清晰的顶层函数分解**
> （`configuredBaselineVersion` / `maxMigrationVersion` / `mysqlAvailable` / `query` /
> `parseBaseline` / `main`），仅 3 处 `execFileSync`、3 处 `readFileSync`。
> 六个判定点的输入输出均为纯数据，抽出成本确实低于上轮估计。