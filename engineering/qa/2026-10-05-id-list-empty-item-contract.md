# QA — 名单解析契约与实现对齐（`IdListTools.parse`）

- 日期: 2026-10-05
- 效率等级: **L3**
- Change: `openspec/archive/2026-10-05-id-list-empty-item-contract`
- 范围:
  - `utils/IdListTools.java` —— 仅 `parse` 内循环的规范化顺序
  - `utils/IdListToolsTest.java` —— 3 条 ⚠ 现状用例改写为目标行为 + 6 条新增
  - `MAX_LENGTH` / `serialize` / `validate` **未改动**
  - 数据库: **无表结构变更、无迁移脚本**（ADR-002）

## 验收口径

| # | 口径 | 依据 |
|---|------|------|
| 1 | 空串元素被忽略 | spec-delta「空串元素被忽略」 |
| 2 | 纯空白元素被忽略，不再成为「用户 id」 | spec-delta「纯空白元素被忽略」 |
| 3 | 剥引号后仍带空白也被忽略 | spec-delta「剥引号后仍带空白也被忽略」 |
| 4 | 合法元素的值被去首尾空白 | spec-delta「合法元素仍被正确解析」 |
| 5 | `parse` **永不抛异常**（宽松契约不被收紧） | spec-delta「解析永不抛异常」 |
| 6 | `validate` 拒的输入 `parse` 一律产出空 | spec-delta「名单读写两侧的空项口径一致」 |
| 7 | 测试有判别力 | AGENTS §2.1 第 1 条 |

## 实际执行命令与结果

| 命令 | 结果 |
|------|------|
| `mvn -B -f easychat-java/pom.xml clean test` | **`Tests run: 349, Failures: 0, Errors: 0, Skipped: 0`** / `BUILD SUCCESS` / `exit=0`（基线 343 + 6） |
| `IdListToolsTest` 单类 | `Tests run: 25, Failures: 0`（原 19 + 新增 6） |
| 红阶段（改实现前） | `exit=1`、**6 例转红**，精确命中 D1/D2；其余 19 例未红 |
| 变异脚本（5 用例） | **`5/5 捕获`，漏网 0，无效 0** / `exit=0`，工作区已还原 |

### 变异明细

```
[基线] ✓ 未变异时门禁/测试全绿
[捕获] ★ 退回原缺陷：判空作用于未剥引号的原文
[捕获] ★ 去掉第二次 trim（剥引号后残留的空白漏进结果）
[捕获] ★ 规范化后不判空（空项照样入集合）
[捕获] 规范化结果未写入集合（写了 norm 却 add 原文）
[捕获] 剥引号步骤被删（值保留引号）
=== 结论：5/5 个变异被测试捕获，漏网 0，无效 0 ===
```

## 未运行项

| 项 | 原因 |
|---|---|
| **活库核查名单列是否含空串/空白项** | ✅ **2026-10-05 已实际执行**（当时沙箱无 MySQL）。5 个名单列中「含空串元素 / 空元素 / 引号未闭合 / 元素带首尾空白」**四项均 0 行** → 存量零脏数据，ADR-002 由论证升级为**实证**。证据见 `engineering/qa/2026-10-05-live-verification.md` §③ |
| 端到端隐私设置 / 动态可见范围冒烟 | 需后端 + MySQL + Redis |
| ~~GitHub Actions 实跑~~ | ✅ 2026-10-05 已实跑（run #1，4 job 全绿）；原缺口：当时沙箱无网络 |

### 活库核查替代依据（论证，非查询结果）

空串/纯空白串**不可能等于任何真实 `userId`**（本项目 userId 形如 `U01234567890`）。
故无论名单列是否含空白项，黑名单 / 白名单 / 动态可见范围的**命中结果均不变**，
唯一变化是 `canView` 遍历时少几次无意义比较。

后续有环境时应补：
`SELECT COUNT(*) FROM user_setting WHERE <名单列> REGEXP '"[[:space:]]*"'`
并取样检视 `moment.visible_list` / `moment.invisible_list`。


> 📌 **2026-10-05 已补执行**：本条所列的「GitHub Actions 未实跑」「端到端未验」缺口
> 已在具备网络与活库的环境中**实际执行并通过**，证据见
> `engineering/qa/2026-10-05-live-verification.md`（CI run #1 4 job 全绿；
> 冒烟 96/96：`smoke_privacy` 51、`smoke_audit_and_at_all` 16、`smoke_password_session` 29）。

## 结论

**达成**。7 条验收口径全部满足；变异 5/5 证明用例非恒绿。
**活库核查已于 2026-10-05 补执行**，存量零脏数据 —— 原「唯一未验证点」已消除。

⚠ 同轮另发现字面量 `"NULL"` 脏值（`test@qq.com.moment_visible_list`），经双重证据确认**无功能影响**，已登记为遗留 #20，本变更不处理。

## 遗留

无新增遗留。#13 已闭环。
