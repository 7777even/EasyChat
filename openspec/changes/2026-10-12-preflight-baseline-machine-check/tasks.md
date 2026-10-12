# Tasks — 前置闸门补机控

- 关联 Design: 2026-10-12-preflight-baseline-machine-check/design.md
- 创建日期: 2026-10-12
- 预估总工时: 3h

## 阶段一：四件套

- [x] 1.1 proposal / design / spec-delta / tasks 四件套 + `.openspec.yaml` — ≤30min

## 阶段二：抽纯核心

- [x] **[TDD]** 2.1 抽 `decidePreflight(input) → { exitCode, reason, kind }`，
      逐条锁定改写前的 6 个判定分支 — ≤40min
- [x] **[TDD]** 2.2 抽 `configuredBaselineVersion(env, propsText)`（含 env 优先的方向性判断）— ≤30min
- [x] **[TDD]** 2.3 抽 `parseBaseline` / `parseLiveColumns` / `computeDiff` / `maxMigrationVersion` — ≤40min
- [x] 2.4 `preflight-baseline-check.mjs` 改调用核心，**IO 接线与打印文案不变** — ≤30min

## 阶段三：门禁（行为测试）

- [x] **[TDD]** 3.1 `verify_preflight_baseline_core.mjs`：判定分支用例，
      **含方向敏感用例**（缺失→拒绝 / 一致→放行）— ≤40min
- [x] **[TDD]** 3.2 版本号来源优先级用例（env 优先 / 非法回退 / 无键兜底）— ≤20min
- [x] **[TDD]** 3.3 差异计算用例（缺表 / 缺列 / 一致 / 基线为空）— ≤20min
- [x] 3.4 空转自检：`kind` 穷举集合完整（每个 kind 至少被一条命中）— ≤20min
- [x] 3.5 静态断言：核心文件**不含** `process.exit` / `require('fs')` / `execFileSync` — ≤10min

## 阶段四：接线

- [x] 4.1 门禁接 `setup-git-hooks.mjs` pre-push（改生成器，非 `.git/hooks`）— ≤10min
- [x] 4.2 门禁接 `.github/workflows/ci.yml` — ≤10min
- [x] 4.3 `verify_migration_flyway.mjs` 第 6 节注释更新：静态断言的职责边界 — ≤15min

## 阶段五：验证与收尾

- [x] 5.1 **变异验证**：把「结构一致 → 放行」反写成「结构不一致 → 放行」，
      新门禁**必须转红**（这是本 Change 的核心目的）— ≤30min
- [x] 5.2 变异验证：把 `env 优先` 反写成 `properties 优先` → 必须转红 — ≤20min
- [x] 5.3 全部 verify 门禁 exit 0 + `preflight-baseline-check.mjs` 实跑仍 exit 0 — ≤30min
- [x] 5.4 回写台账（闭环 #11）+ spec 回写 + 归档 + QA/Retro — ≤40min

## DoD 自检（完成后逐项确认）

- [x] `tasks.md` 全部勾选
- [x] 全部 `scripts/verify/*.mjs` exit 0
- [x] 两处变异验证均转红，且记录了**转红的具体用例名**
- [x] 闸门行为与改写前**等价**（由行为测试锁定 6 个分支）
- [x] 归档闭环完成（spec 回写 + `git mv` 到 `archive/`）