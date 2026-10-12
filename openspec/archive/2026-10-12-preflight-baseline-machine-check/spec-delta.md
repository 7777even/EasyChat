# Spec Delta — 前置闸门补机控

- 关联 Tasks: 2026-10-12-preflight-baseline-machine-check/tasks.md
- 创建日期: 2026-10-12

## ADDED Requirements

### Requirement: 闸门判定可被行为测试验证（对齐 proposal Capability C1）

「放行 / 拒绝」的判定必须抽为纯函数，其**条件分支方向**由行为测试锁定——
把 `if (结构一致) 放行` 反写成 `if (结构不一致) 放行` 必须导致测试转红。

#### Scenario: 声称最新版但结构有缺失 → 拒绝（方向敏感）

- **WHEN** `baseline-version == 最大迁移号`，且差异计算得出 1 张缺表
- **THEN** `decidePreflight` 返回 `exitCode = 1`
- **AND** 返回的 `kind` 为「结构与基线不一致」
- **AND** **不**返回 `exitCode = 0`

#### Scenario: 声称最新版且结构完全一致 → 放行（方向敏感）

- **WHEN** `baseline-version == 最大迁移号`，且差异为空
- **THEN** 返回 `exitCode = 0`，`kind` 为「结构一致」

#### Scenario: 库为空 → 放行

- **WHEN** `tableCount = 0`
- **THEN** 返回 `exitCode = 0`，且 `kind` 为「库为空」
- **AND** **不**继续比对结构

#### Scenario: 已有 Flyway 历史表 → 放行

- **WHEN** `hasHistory > 0`
- **THEN** 返回 `exitCode = 0`，`kind` 为「由版本表记账」

#### Scenario: 版本号不等于最大迁移号 → 放行（合法的待升级库）

- **WHEN** `configuredVersion = 9`、`maxVersion = 12`
- **THEN** 返回 `exitCode = 0`，`kind` 为「合法的存量库待升级」

#### Scenario: 判定种类穷举（防止分支被删后悄悄落到兜底）

- **WHEN** 对覆盖全部输入组合的一组用例收集 `kind`
- **THEN** 每个用例的 `kind` 都属于**已声明的有限集合**
- **AND** 集合中**每个** kind 都至少被一条用例命中（防止某条分支写了却没被测）

---

### Requirement: 基线版本号来源优先级可被行为测试验证

`baseline-version` 必须**优先读环境变量**，其次才读 `application.properties` 默认值。
方向写反会把运维声明的真实版本号堵死。

#### Scenario: env 声明优先

- **WHEN** env 为 `9`，properties 默认值为 `12`
- **THEN** 返回 `version = 9`，`source` 指向环境变量

#### Scenario: env 非法时回退 properties

- **WHEN** env 为空串 / 非数字 / 含空白
- **THEN** 回退读 properties 的默认值，`source` 指向 properties

#### Scenario: properties 无该键时用兜底 12

- **WHEN** env 缺失且 properties 文本中无 `SPRING_FLYWAY_BASELINE_VERSION:(\d+)`
- **THEN** 返回 `version = 12`

---

### Requirement: 结构差异计算可被行为测试验证（对齐 proposal Capability C2）

#### Scenario: 缺表与缺列分别被识别

- **WHEN** 基线有 3 张表，活库缺 1 张表、另 1 张表缺 2 列
- **THEN** `missingTables` 恰含那张表
- **AND** `missingCols` 恰含那 2 个「表.列」

#### Scenario: 完全一致时差异为空

- **WHEN** 活库表列集合与基线完全相同
- **THEN** `missingTables` 与 `missingCols` 均为空数组

#### Scenario: 基线为空时差异为空（不得把所有表都报成缺失）

- **WHEN** 基线为空 Map
- **THEN** 差异为空

---

## MODIFIED Requirements

### Requirement: 前置闸门的静态断言

`verify_migration_flyway.mjs` 中针对 `preflight-baseline-check.mjs` 的四条静态断言
**保留**（能拦住「整个拒绝分支被删掉」），但**不再承担**分支方向的验证职责。

#### Scenario: 静态断言的职责边界被写明

- **WHEN** 阅读该门禁第 6 节
- **THEN** 注释中明确指出：静态断言只能证明「分支存在」，
      方向由 `verify_preflight_baseline_core.mjs` 的行为测试负责

**变更前**: 注释称「该情形由真机验证覆盖（T2.3 三路径实测），不在门禁能力范围内」。

**变更后**: 分支方向改由**行为测试**覆盖（真机验证仍保留为最终兜底）。

---

## REMOVED Requirements

（无）

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 闸门判定可被行为测试验证 | 2.1, 3.1, 4.1 |
| C1 | ADDED: 基线版本号来源优先级可被行为测试验证 | 2.2, 3.2 |
| C2 | ADDED: 结构差异计算可被行为测试验证 | 2.3, 3.3 |
| （静态断言职责） | MODIFIED: 前置闸门的静态断言 | 5.1 |