# Tasks — 名单解析器剔除「空值字面量」（遗留 #20 治本）

- 关联 Design: 2026-10-13-id-list-null-literal-guard/design.md
- 创建日期: 2026-10-13
- 预估总工时: 1.5h

## 阶段一：四件套

- [x] 1.1 proposal / design / spec-delta / tasks + `.openspec.yaml` — ≤30min

## 阶段二：TDD 红阶段

- [x] **[TDD]** 2.1 在 `IdListToolsTest` 补空值字面量用例，**先写红** — ≤20min
- [x] **[TDD]** 2.2 `parse("NULL")` 返回空列表 — ≤10min
- [x] **[TDD]** 2.3 `parse("undefined")` / `parse("NaN")` / `parse("None")` 返回空列表 — ≤15min
- [x] **[TDD]** 2.4 混合名单 `["U001","NULL","U002"]` → 只保留两个真实 id — ≤15min
- [x] **[TDD]** 2.5 大小写变体（`Null` / `nUlL` / `UNDEFINED`）一律剔除 — ≤10min
- [x] 2.6 合法 id 与既有宽松形态**不受影响**（回归护栏）— ≤15min

## 阶段三：实现

- [x] 3.1 `IdListTools` 加 `NULL_LITERAL_TOKENS` 常量（`null`/`undefined`/`nan`/`none`）
      + `isNullLiteral` 私有方法（大小写不敏感）— ≤15min
- [x] 3.2 在 `parse` 的 `set.add` 前插入该判定 —— **只加这两处，别的不动** — ≤10min

## 阶段四：变异验证

- [x] 4.1 变异：把判定改成**大小写敏感**（`equals` 而非 `equalsIgnoreCase`）
      → 大写变体用例必须转红 — ≤15min
- [x] 4.2 变异：把 `undefined` 从黑名单删掉 → 对应用例必须转红 — ≤15min
- [x] 4.3 变异：把判定**放在 `StringTools.isEmpty` 之前**并直接 `continue`
      （等价变异自查）— ≤10min

## 阶段五：验证

- [x] 5.1 `IdListToolsTest` 全绿（既有 20+ 条**一条都不能破**）— ≤15min
- [x] 5.2 `mvn test` 全绿（重点确认 `MomentServiceImplTest` 的 `canView` 用例未受影响）— ≤20min
- [x] 5.3 活库复核：`user_info` 那行 `"NULL"` 保持原样（**本变更不动数据**）— ≤10min

## 阶段六：收尾

- [x] 6.1 回写台账 #20（**订正「须先处理此脏值」这个已被证伪的判断**，并写明真实根因在解析器）— ≤15min
- [x] 6.2 spec 回写 `openspec/specs/privacy-settings/` — ≤15min
- [x] 6.3 归档 + QA / Retro — ≤30min

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] **既有 `IdListToolsTest` 全部用例零修改、零失败**（宽松语义不回退）
- [x] **不修改 `StringTools.isEmpty`**（爆炸半径锁在 `parse` 内）
- [x] **不修改 `IdListTools.validate`**（写入口严格语义不变）
- [x] **不修改活库数据**
- [x] 前端 / 数据库 / 依赖 零改动
- [x] `mvn test` 全绿
- [x] 三处变异均转红（或如实登记为等价变异），且记录转红的具体用例名
- [x] 归档闭环完成