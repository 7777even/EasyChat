# QA — 敏感词打码确定性（遗留 #22）

- 日期: 2026-10-05
- 效率等级: L3
- Change: `openspec/changes/2026-10-05-sensitive-word-masking-determinism`
- 关联台账: `docs/system-facts.md` §14 **#22**

## 范围

| 项 | 内容 |
|---|---|
| 改什么 | `SensitiveWordServiceImpl`：`reload()` 预计算「长度降序」的 `maskingList`；`filter` 第二遍改遍历它 |
| 不改什么 | `filter` 第一遍（level=3 拦截）逻辑、Mapper XML、表结构、前端、接口签名 |
| 测试 | `SensitiveWordFilterTest` 由 20 例扩到 **27 例** |
| 生产改动文件 | **1 个**（`SensitiveWordServiceImpl.java`） |

## 验收口径与实际执行

| 验收口径 | 命令 | 实际结果 |
|---|---|---|
| TDD 红阶段先失败 | `mvn -B -f easychat-java/pom.xml test -Dtest=SensitiveWordFilterTest` | ✅ 26 例中 **4 例红**（顺序依赖未消除 + 字段不存在），BUILD FAILURE |
| 实现后转绿 | 同上 | ✅ **exit 0**，27 例全绿 |
| 后端全量回归 | `mvn -B -f easychat-java/pom.xml clean test` | ✅ **exit 0**，`Tests run: 408, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |
| 变异检验有判别力 | `node mutate_sensitive_word.cjs`（一次性脚本，见下） | ✅ **exit 0**，`11/12 捕获、0 漏网、0 无效、1 无害·等价` |
| 密码交接门禁 | `node scripts/verify/verify_password_handoff.mjs` | ✅ exit 0 |
| OpenSpec 卫生 | `node scripts/check-openspec-hygiene.mjs` | ✅ exit 0 |
| 接口契约不漂移 | `node scripts/check-api-contract.mjs` | ✅ exit 0（本次未动接口） |
| WS 帧协议不漂移 | `node scripts/verify/verify_ws_frame_parity.mjs` | ✅ exit 0（本次未动帧） |
| 变更后 diff 纯净 | `git diff -- <生产文件>` | ✅ 仅含预期改动，无变异残留 |

**用例数变化**：后端 401 → **408**（`SensitiveWordFilterTest` 20 → 27）。

## 变异检验证据

变异脚本为一次性脚本，存于临时目录（不入库），含**基线自检**（未变异须先全绿）与**三态计数**。

```
[基线] 未变异时运行 SensitiveWordFilterTest …
[基线] ✓ 全绿，后续「捕获」可归因于变异本身
[捕获] ★★★ 排序被整段删除 → 顺序依赖回归（ab 先替换 → ***cd 残留）
[捕获] ★★★ 第二遍改回遍历 wordList（有序列表建了但不用）
[捕获] ★★ 忘记 .reversed() → 变成「短词优先」，与目标完全相反
[捕获] ★★ reversed 放在 thenComparing 之后 → 同长度词的兜底顺序也被反转
[捕获] ★★ 空词键改回 MAX_VALUE → 空词排到最前（reversed 之后键大在前）
[捕获] ★★ 空词判断被删 → word=null 时比较器取 length() 抛 NPE（reload 整体失败）
[捕获] ★ maskingList 与 wordList 分两次赋值（撕裂窗口）→ 不变式与并发用例应转红
[捕获] ★ maskingList 直接引用 raw（未复制、未排序）
[捕获] ★ mapper 返回 null 时 maskingList 留 null → filter 第二遍 NPE
[无害·等价] [等价] 第一遍也改用 maskingList —— level=3 判定本就与顺序无关，应保持全绿
[捕获] 第二遍的空词跳过被删 → word=null 时 result.contains(null) 抛 NPE
[捕获] maskingList 字段改名（反射读取失败 → 应报测试失败而非静默通过）

=== 结论：11/12 个变异被测试捕获，漏网 0，无效 0，无害但转红 1 ===
✓ 测试有判别力
```

## 关键实证（结论所依赖的事实，均为实测非推断）

| 事实 | 取证方式 | 结果 |
|---|---|---|
| 遍历顺序由 DB 决定 | 读 `SensitiveWordMapper.xml:42-48` | **无 `ORDER BY`** |
| 缺陷对用户可见 | 全仓 grep `sensitiveWordService.filter` | 3 处：`ChatMessageServiceImpl:250`、`MomentServiceImpl:83`、`MomentServiceImpl:200`，全为用户可见正文 |
| **缺陷当前未在活库发作** | `mysql easychat` 查互相包含词对 | `sensitive_word` 共 **9** 条（level 2×4、level 3×5），**互相包含词对 = 0** |
| 不会泄露**完整**在册词 | 论证 + 性质断言 `noRegisteredWordSurvivesInOutput` | `String.replace` 只做字面量增删，无法把已被打断的词重新拼回；输出中不可能存在完整的在册词 |
| level=3 拦截不受影响 | 既有 `level3BlockIsOrderIndependent` + 变异「第一遍改用 maskingList」仍全绿 | 拦截阶段以**原始未替换内容**判定，与顺序天然无关 |

## 过程中发现并修正的问题

| # | 问题 | 发现方式 | 处置 |
|---|---|---|---|
| 1 | **`Comparator.comparingInt(len)` 是升序** → 得到「短词优先」，与目标**完全相反**，且恰好与修复前的旧行为一致 | 红阶段未转绿的用例 + 变异「忘记 `.reversed()`」被捕获 | 加 `.reversed()`；在代码注释中写明该陷阱 |
| 2 | 空词键取 `MAX_VALUE` 时，`reversed()` 后空词排到**最前** | 同上 | 键改取 `-1`，使空词落在末尾 |
| 3 | 变异「空词键改回 `MAX_VALUE`」**漏网** | 变异检验 | **真测试缺口**：空词位置在任何输入下都不可从输出观测（恒被 `isEmpty→continue` 跳过）。补**白盒断言** `maskingListSortOrderInvariant` 钉住 ADR-002 声明的排序形态 |
| 4 | 变异「第一遍也改用 `maskingList`」**漏网** | 变异检验 | **不是缺口，是等价变异** —— 它恰好**证明**了设计声明。标 `[无害·等价]`，不冒充覆盖 |
| 5 | 测试脚手架反射直写 `wordList`，**绕过**了生产构建有序列表的逻辑 | 通读测试时发现 | 改为注入 mapper 走**真实 `reload()`** —— 否则排序改坏测试仍全绿（假覆盖） |
| 6 | 两处测试自身笔误：期望值写成 `***和***`（内容用的是「与」）、`abc` 的期望写成 `***c` | 跑测失败 | 修正为 `***与***` / `***` |

## 未运行项

| 未运行 | 原因 | 风险评估 |
|---|---|---|
| 活体接口冒烟（真发一条含互相包含词的消息） | 需要先向活库插入互相包含的词条（如 `ab` + `abcd`），属**改动生产数据**，未获授权 | **低**。① 存量词对 = 0，现状下输出与修复前逐条相同；② 修复方向是收紧（`***cd`→`***`）；③ 已由 `allPermutationsProduceSameOutput` 在 6 种排列下证明输出与顺序无关 |
| 真实 Spring 容器下的启动验证 | 单元测试已覆盖 `reload()` 全路径（走真实方法 + 代理 mapper） | **低**。`maskingList` 在字段声明处即初始化为 `new ArrayList<>()`，`@PostConstruct init()` 调 `reload()`，无未初始化窗口 |

## 结论

**通过**。验收口径逐条满足，后端全量 408 全绿，变异检验证明断言有判别力（11/12 捕获 + 1 条经查证为等价变异），变更后 diff 纯净无残留。

唯一未覆盖的是「活体发消息」这一端到端环节，已说明原因与风险评估，且该环节的**核心性质已由排列测试在内存级证明**。
