# QA — 前置闸门补机控（遗留 #11 闭环）

- Change: `openspec/archive/2026-10-12-preflight-baseline-machine-check`
- 执行日期: 2026-10-12
- 等级: L3

## 范围与验收口径

范围：把 `preflight-baseline-check.mjs` 的判定与解析抽出为纯核心，
新增**行为**门禁覆盖分支方向；`verify_migration_flyway.mjs` 第 6 节的职责边界收窄。

## 实际执行的命令与结果

| 命令 | 结果 |
|---|---|
| `node scripts/verify/verify_preflight_baseline_core.mjs` | **60 项通过 / 0 失败**，判定种类覆盖 **5/5** |
| `node scripts/migrate/preflight-baseline-check.mjs`（活库实跑） | exit 0，输出与改写前一致（检测到 `flyway_schema_history` → 由版本表记账 → 放行） |
| 全部 `scripts/verify/*.mjs`（**15 道**，本次新增 1 道） | 全部 exit 0 |
| `node scripts/setup-git-hooks.mjs` | pre-push 含新门禁（已 grep 确认生成物） |

## 变异验证（本 Change 的核心目的）

| 变异 | 结果 |
|---|---|
| 把 `if (!missing.length && !missingCols.length)` 反写成 `if (missing.length \|\| missingCols.length)` | **7 条转红**：缺表 exit1 / 缺表 kind / 「缺表绝不可放行」/ 缺列 exit1 / 缺列 kind / 一致 exit0 / 一致 kind |
| 把 `configuredBaselineVersion` 的 env 分支正则改为 `/^999$/` | **2 条转红**：「env=9 优先于 properties 默认 12」「env 生效时 source 指向环境变量」 |

> **对照证据**：第一处变异下，`verify_migration_flyway.mjs` 的**四条静态断言全部照样通过**
> ——这正是遗留 #11 描述的盲区，现由行为测试接住。

## 两道防空转措施

1. **kind 穷举自检**：`DECISION_KINDS` 是有限集合，门禁断言
   ① 每个用例产生的 `kind` 都已登记 ② **每个已登记的 kind 至少被一条用例命中**。
   后者防「某条分支写了却没被测」，也防「用例被删导致 kind 覆盖不全仍全绿」。
2. **纯度静态断言**：核心文件不得含 `process.exit` / `fs` / `child_process` / `execFileSync`。
   ⚠ 该断言**必须先剥注释再匹配**——核心顶部注释里明确写着「不读文件、不起进程、不 exit」，
   直接全文匹配会把这句**解释约束**的话判成违反约束（首次运行即踩中，已改为
   块注释 + 行注释 + 负向后顾排除 `://` 的三重剥离）。

## 未运行项 / 已知缺口

1. **未做真机三路径实测**：T2.3 的三条路径（空库 / 待升级库 / 声称最新但结构不符）
   未逐一实跑。本次行为测试在**纯函数层**覆盖了全部 5 个判定分支，
   但「IO 接线是否把数据正确喂给核心」由本次实跑覆盖了一条（活库 → 有历史表 → 放行）。
   另两条路径的真机复跑仍建议在下次涉及 Flyway 变更时补上。
2. **CI 首跑未知**（`gh` 未认证）。

## 结论

**通过**。两处变异均验证到判别力，且第一处直接证明了「行为测试能接住静态断言接不住的盲区」。