# Design — 敏感词替换「长词优先」与顺序无关性

- 关联 Proposal: `proposal.md`
- 创建日期: 2026-10-05
- 效率等级: L3

## 现状与问题定位

`SensitiveWordServiceImpl#filter` 两遍扫描：

```java
// 第一遍：level=3 拦截（遍历原始 content，与顺序无关）
for (SensitiveWord sw : wordList) { ... throw CODE_2701; }

// 第二遍：level=1/2 替换（遍历 wordList，顺序即 DB 返回顺序）
String result = content;
for (SensitiveWord sw : wordList) { result = result.replace(word, "***"); }
```

`SensitiveWordMapper.xml#selectByStatus` **无 `ORDER BY`**，故 `wordList` 顺序由 DB 决定，`reload()` 后可能变化。短词先被替换 → 长词失去匹配机会 → 输出随词表行顺序而变。

**已验证的事实（非推断）**：

| 事实 | 证据 |
|---|---|
| 遍历顺序无 SQL 保证 | `SensitiveWordMapper.xml:42-48` 无 `ORDER BY` |
| 缺陷当前**未在活库发作** | 活库 `sensitive_word` 9 条（level2×4、level3×5），互相包含词对 `= 0` |
| 缺陷对用户可见 | 3 个调用方全是用户可见正文：`ChatMessageServiceImpl:250`、`MomentServiceImpl:83`、`MomentServiceImpl:200` |
| 不会泄露**完整**敏感词 | 论证：任一词 W 若在自身迭代命中则被替换；若未命中则说明 W 已被更早的替换**打断**，而 `String.replace` 只做字面量增删、无法重新拼回 W。故输出中不可能存在完整的在册敏感词 —— **残留是片段**（如 `cd`），非整词。 |
| level=3 拦截不受影响 | 第一遍作用于**未替换的原始 content**，与词表顺序天然无关（`SensitiveWordFilterTest#level3BlockIsOrderIndependent` 已锁定） |

## 决策

### ADR-001：排序放在 `reload()` 预计算，而非每次 `filter` 调用

- **决策**：`reload()` 时对词列表做一次**按 `word` 长度降序**的复制，存为独立字段；`filter` 第二遍遍历该有序列表。
- **理由**：
  1. `filter` 是**每条消息**都调用的热路径（3 个调用方 × 全量消息流量）。排序放这里等于每条消息付一次 O(n log n)。
  2. `reload()` 只在启动 `@PostConstruct` 与管理端词库写变更后触发（量级：人工操作），排序成本可忽略。
  3. 排序结果与 `wordList` **解耦存储** → 不污染第一遍的遍历对象，避免「为了让第二遍有序而误改第一遍语义」的隐患。
- **被否方案**：
  - *每次 `filter` 内 `stream().sorted(comparingInt(...))`* —— 热路径付排序成本，否。
  - *在 Mapper XML 加 `ORDER BY CHAR_LENGTH(word) DESC`* —— 语义分散在 SQL 与 Java 两层；且 `wordList` 同时服务第一遍，让 SQL 为第二遍的排序需求买单；每次 `reload()` 多一次 filesort。**否**。

### ADR-002：稳定性排序（同长度词按字面量兜底）

- **决策**：比较器为 `comparingInt(长度降序).thenComparing(word)`（末位仅用于**确定性**，不影响替换结果 —— 同长度词互不含，顺序无碍）。
- **理由**：`List#sort`（TimSort）本身是**稳定**排序，同长度词保持 DB 顺序，本就确定。但把字典序兜底写进去可让「顺序无关」这一性质**不依赖 TimSort 的实现细节** —— 若将来换成不稳定排序（如并行 sort），输出仍可复现。
- **须处理的边界**：`word` 可能为 `null` 或空串（`filter` 现有代码对空词 `continue` 跳过）。排序比较器**不得**在此 NPE —— 故先按「空词排最后」处理再比长度。

### ADR-003：`reload()` 的原子性

- **决策**：`wordList` 与有序列表**作为同一个不可变对象成对赋值**，且都在 `reload()` 内一次性构建后发布；两个字段保持 `volatile`。
- **理由**：若两个字段分两次赋值，并发读线程可能看到「新 wordList + 旧有序列表」的撕裂组合，导致短时间内输出又变回旧行为。成对构建后一次发布可消除该窗口。
- **不变式**：`maskingList` 与 `wordList` **元素集合恒等**（仅顺序不同）。

## 数据影响

- **无 schema 变更**，无迁移脚本，不触碰 `easychat.sql`。
- **存量数据零影响**：已实测活库互相包含词对 = 0，故存量词表下输出**逐条不变**。

## 测试策略（TDD）

先改测试（红）→ 再改实现（绿）。

核心断言是**派生式**的（对齐 `PasswordEncoderTest` 手法）：不硬编码「哪些词该被替换」，而是断言**性质** ——

1. **顺序无关性**：同一组词条的所有排列（`n!`，小集合取全排列）下 `filter` 输出**逐字节相同**。
2. **长词优先**：短词为长词的前缀时，输出等于「只按长词替换」的结果。
3. **不可泄露完整在册词**（性质断言，不硬编码）：对任意词表与内容，`filter` 输出中**不得**包含任何在册敏感词作为子串。
4. **回归**：既有 20 例全部保留（`maskingIsOrderDependent` 依契约改写为顺序无关断言）。

## 风险

| 风险 | 缓解 |
|---|---|
| 比较器对 `null`/空词 NPE | ADR-002 显式要求「空词排最后」；且测试注入含 `null` word 的词条 |
| 两字段撕裂 | ADR-003 成对构建发布；测试并发读 `reload()` 与 `filter` |
| 输出变化引起客户端困惑 | 输出格式不变（仍是 `***`），仅**打码范围**变宽，无契约变更 |
| 误以为修复了「泄露」 | 已论证不可能泄露**完整**词；本次是**片段残留 + 不可复现**，spec 与 QA 均按此措辞 |

## 依赖

无新增依赖。
