# Tasks — 名单解析契约与实现对齐

- 关联 Design: `openspec/changes/2026-10-05-id-list-empty-item-contract/design.md`
- 创建日期: 2026-10-05
- 预估总工时: 1.5h

## 阶段零：基线与活库核查

- [x] **T0.1** 记录基线：`IdListToolsTest` 现有 19 例全绿；全量后端 343 例 exit 0 — ≤10min
- [x] **T0.2** **活库核查：沙箱无 MySQL，未执行** → 改用 ADR-002 的**论证**作为依据
      （见「未执行项说明」）。已如实登记，**未按「无影响」计**。

## 阶段一：[TDD] 先写失败测试

- [x] **T1.1** 改写 `IdListToolsTest` 中三条 ⚠ 现状用例为**目标行为**：
      `[""]` → `[]`、`["U001","","U002"]` → 2 项、`["   "]` → 跳过 — ≤25min
- [x] **T1.2** 新增 `validate` / `parse` **口径对照表**用例（design §2 的 4 行）— ≤20min
- [x] **T1.3** 新增「`parse` 对任意脏输入都不抛异常」用例（保住宽松契约）— ≤10min
- [x] **T1.4** **确认红阶段**：`exit≠0`、失败点精确指向 D1/D2 两条新期望 — ≤10min

## 阶段二：实现

- [x] **T2.1** `parse` 改为「先规范化（trim → 剥引号 → 再 trim）→ 判空 → 入集合」（ADR-001） — ≤15min
- [x] **T2.2** javadoc 补一句**为何先规范化再判空**（否则后人会「顺手简化」回去） — ≤10min
- [x] **T2.3** `IdListToolsTest` 转绿；`MAX_LENGTH` / `serialize` / `validate` 未受影响 — ≤10min

## 阶段三：判别力与回归

- [x] **T3.1** 变异①：去掉「规范化后判空」（退回判原文非空）→ 必须转红 — ≤15min
- [x] **T3.2** 变异②：去掉第二次 trim（`" \" \" "` 之类漏网）→ 必须转红 — ≤15min
- [x] **T3.3** 全量 `mvn test` 无新增失败，记录用例数与 exit code — ≤30min

## 阶段四：收尾

- [x] **T4.1** spec-delta 回写 `openspec/specs/privacy-settings/spec.md`（名单解析为该 capability 依赖） — ≤20min
- [x] **T4.2** `engineering/qa/2026-10-05-id-list-empty-item-contract.md` + `engineering/retro/` — ≤30min
- [x] **T4.3** `docs/system-facts.md` §14 #13 关闭 + 变更日志 — ≤15min
- [x] **T4.4** 归档（`Move-Item` 至 `openspec/archive/2026-10-05-id-list-empty-item-contract`） — ≤10min

## 遗留（本批明确不做）

| 项 | 原因 | 后续 |
|---|------|------|
| `serialize` 的 `HashSet` 导致顺序不保证 | 名单语义是集合，顺序无业务含义（ADR-003） | 若将来有「按顺序展示」需求再改 |
| 名单列的**长度**治理（`MAX_LENGTH` 60000 是否合理） | 属独立的容量决策 | 独立评估 |
| `parse` 返回 `null` 元素类型兜底 | fastjson 解析 `["a"]` 不会产生 null 元素 | 无需处理 |

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] **T1.4 红阶段已实跑并留证**
- [x] **T3.1 / T3.2 两个变异全部被捕获**
- [x] **T0.2 活库核查未执行已如实登记**（沙箱无 MySQL），改用 ADR-002 论证；未按「无影响」计
- [x] 全量后端测试无新增失败
- [x] `parse` 的**宽松契约**（永不抛异常）有用例守住
- [x] 归档闭环完成

## 实施记录

**T1.4 红阶段**：`exit=1`、**6 例转红**，精确命中 D1/D2：
`parseSkipsEmptyStringItem`、`parseSkipsEmptyItemsAroundRealIds`、`parseSkipsBlankItems`、
`parseTrimsRealValues`、`validateRejectsWhatParseEmpties`、
`parseSkipsBlanksRemainingAfterQuoteStripping`。
其余 19 例（含 `serialize` / `validate` / 长度守卫）**未红**，确认未误伤。

**T2 关键**：D1 与 D2 **同根** —— 不是两个独立 bug，而是**规范化顺序反了**：
原写法「先 trim 判空、再剥引号」，而判空作用于**未剥引号的原文**，
`isEmpty("\"\"")` 长度 2、`isEmpty("\"   \"")` 长度 5，皆为 false →
javadoc 承诺的「忽略空项」**从未生效**。改为「先规范化（trim → 剥引号 → 再 trim）→ 再判空」。

**T3 变异 5/5 全捕获**：退回原缺陷 / 去掉第二次 trim / 不判空 / 未写回 norm / 删掉剥引号。
其中「去掉第二次 trim」这条专门对应 design ADR-001 里「两次 trim 不可省」的论断 ——
若无此用例，那句注释只是我的断言而无人验证。

**过程中又一次转义层级错位**：`parseSkipsBlanksRemainingAfterQuoteStripping` 初版写
`"[\"\\t\"]"`（Java 转义 `\t`），到达 `parse` 时是**字面反斜杠 + t**，不是制表符，
于是被剥引号后剩两字符 → 用例持续红。改用 `"[\"" + "\t" + "\"]"` 拼接。
这是本会话**第五次**「写锚点/字面量时转义层级错一层」（前四次见 AGENTS §2.1 第 8 与第 10 条 ④），
已就地注释说明，避免后来者再「简化」回字面量。

## 未执行项说明（不计入通过）

**T0.2 活库核查未执行** —— 沙箱无 MySQL 实例。
故改动影响依据 design ADR-002 的**论证**而非查询结果：

> 空串/纯空白串**不可能等于任何真实 `userId`**（本项目 userId 形如 `U01234567890`，
> 见 `MomentServiceImpl#canView` 与 `IdListTools` 的存储格式示例）。
> 因此无论名单列是否含空白项：
> - 黑名单命中结果**不变**（空串不在黑名单里的概率=0，除非库里本来就有一个 userId 为空的账号）
> - 白名单命中结果**不变**
> - 动态可见范围判定**不变**
> 唯一变化是 `canView` 遍历时少几次无意义比较。

该论证**不能替代**活库查询。后续有环境时应补一次：
`SELECT COUNT(*) FROM user_setting WHERE <名单列> REGEXP '"[[:space:]]*"'`
或直接取样检视 `moment.visible_list` / `moment.invisible_list`。
