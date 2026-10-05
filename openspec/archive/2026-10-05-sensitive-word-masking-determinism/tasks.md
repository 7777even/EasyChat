# Tasks — 敏感词替换「长词优先」与顺序无关性

- 关联 Design: `design.md`
- 创建日期: 2026-10-05
- 效率等级: L3

> 状态只回填本文件。会话内进度不作第二套记录（AGENTS §7）。

## 0. 前置关卡

- [x] 0.1 **人工确认 proposal** —— 用户于 2026-10-05 勾选「同意方案，允许继续」

## 1. TDD 红阶段（先写会失败的测试）

- [x] 1.1 改写 `SensitiveWordFilterTest#maskingIsOrderDependent` → `maskingIsOrderIndependent`：同一组词条两种排列，断言输出**逐字节相同**（当前实现下红）
- [x] 1.2 [TDD] 新增 `longerWordMasksFirst`：短词为长词前缀时，输出等于「只按长词替换」的结果
- [x] 1.3 [TDD] 新增 `noRegisteredWordSurvivesInOutput`（性质断言，不硬编码）：任意词表 + 内容，输出**不得**含任何在册敏感词子串
- [x] 1.4 [TDD] 新增 `allPermutationsProduceSameOutput`：小词表（3 词）取**全排列**逐一断言输出相同
- [x] 1.5 [TDD] 新增 `nullAndBlankWordsSortWithoutNpe`：词表含 `word=null` / `word=""` 的词条时排序与替换均不抛异常（ADR-002）
- [x] 1.6 [TDD] 新增 `maskingListMirrorsWordListElements`：断言 `maskingList` 与 `wordList` **元素集合恒等**（仅顺序不同）（ADR-003 不变式）
- [x] 1.7 [TDD] 新增 `reloadDoesNotExposeTornState`：并发 `reload()` + `filter` 压测，断言无非 `CODE_2701` 的异常且输出始终等于「长词优先」的期望值（ADR-003）
- [x] 1.8 **跑红确认**：实测 26 例中 **4 例红** —— `maskingIsOrderIndependent` / `allPermutationsProduceSameOutput` / `longerWordMasksFirst`（顺序依赖未消除）+ `maskingListMirrorsWordListElements`（字段不存在）。**诚实标注**：1.5 `nullAndBlankWordsSortWithoutNpe` 与 1.7 `reloadDoesNotExposeTornState` 在红阶段**并未转红** —— 前者因空词恒被跳过（位置不可观测），后者因修复前只有单字段（无撕裂可能）；二者属「实现引入后才成立的守卫」，其判别力由变异检验补证（`maskingListSortOrderInvariant` 除外，该例为后补的白盒断言）

## 2. 实现（最小改动）

- [x] 2.1 `SensitiveWordServiceImpl` 新增 `volatile List<SensitiveWord> maskingList`（有序版本）
- [x] 2.2 `reload()` 内成对构建 `wordList` 与 `maskingList` 后一次发布（ADR-003）
- [x] 2.3 排序比较器：长度降序 + 字典序兜底 + **空词/null 排最后且不 NPE**（ADR-002）
- [x] 2.4 `filter` 第二遍改遍历 `maskingList`；**第一遍不动**
- [x] 2.5 `reload` 的 mapper 返回 `null` 分支同样构造空有序列表（不得留 `null`）
- [x] 2.6 跑绿：1.1~1.7 全绿

## 3. 判别力验证（变异检验）

- [x] 3.1 变异脚本含**基线自检**（未变异须先全绿，否则「捕获」可能只是环境坏了）
- [x] 3.2 三态计数 `[捕获]` / `[漏网]` / `[无效]`，「无效」用例不得计入分母冒充覆盖
- [x] 3.3 覆盖变异：删掉有序列表改回 `wordList` / 比较器反向 / 长度排序写成升序 / 空词排在最前导致 NPE / `reload` 只更新一个字段（撕裂）/ `filter` 第一遍也改用有序列表（应无影响，若有影响说明判断有误须查明）
- [x] 3.4 结果 **11/12 捕获、0 漏网、0 无效、1 无害·等价** → exit 0。查因过程：① 首轮「空词键改回 MAX_VALUE」**漏网** —— 真缺口，空词位置不可从输出观测，已补白盒断言 `maskingListSortOrderInvariant`；② 首轮「第一遍也改用 maskingList」**漏网** —— 实为**等价变异**，它恰好证明「拦截阶段与顺序无关」，故标 `[无害·等价]` 不计漏网
- [x] 3.5 还原确认：`git status` 中 `src/main` 干净

## 4. 回归与收尾

- [x] 4.1 `mvn -f easychat-java/pom.xml clean test` 全绿（记录总数）
- [x] 4.2 `node scripts/verify/verify_password_handoff.mjs` exit 0
- [x] 4.3 `node scripts/check-openspec-hygiene.mjs` exit 0
- [x] 4.4 spec 回填：将 `spec-delta.md` 的 ADDED / MODIFIED 合入 `openspec/specs/content-moderation/spec.md`
- [x] 4.5 台账：`docs/system-facts.md` §14 **#22** 改为已解决（保留「潜伏未发作」的更正说明）；变更日志补一行
- [x] 4.6 `engineering/qa/2026-10-05-sensitive-word-masking-determinism.md`：范围 / 验收口径 / 实际命令与用例数 / 未运行项 / 结论 + 变异检验输出为证据
- [x] 4.7 `engineering/retro/2026-10-05-sensitive-word-masking-determinism.md`：四段式
- [x] 4.8 归档：`git mv openspec/changes/2026-10-05-sensitive-word-masking-determinism openspec/archive/2026-10-05-sensitive-word-masking-determinism`
- [x] 4.9 按 scope 拆提交（`content`/`docs`）并推送

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 打码结果与词表顺序无关 | ADDED: 打码顺序无关性（masking-order-independence） | 1.1, 1.4, 3.3 |
| C2 长词优先命中 | ADDED: 长词优先替换（masking-longest-first） | 1.2, 2.3, 2.4 |
| C3 level=3 语义不变 | MODIFIED: 敏感词实时过滤（content-moderation C1） | 2.4, 3.3 |
| C4 性能不退化 | MODIFIED: 敏感词实时过滤（content-moderation C1） | 2.2, 2.5 |
