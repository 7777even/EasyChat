# Spec Delta — 敏感词替换「长词优先」与顺序无关性

- 关联 Tasks: `2026-10-05-sensitive-word-masking-determinism/tasks.md`
- 创建日期: 2026-10-05
- 目标 capability: `content-moderation`

> 格式对齐 `openspec/specs/content-moderation/spec.md`。

## ADDED Requirements

### Requirement: 打码结果与词表顺序无关（masking-order-independence，C1）

内容过滤的**替换（打码）阶段**输出 SHALL 是**在册词条集合的纯函数**，不得依赖词条在内存列表 / 数据库结果集中的行顺序。当两个词条互为子串（其一为另一的前缀或内含子串）时，顺序不得影响打码范围。

#### Scenario: 互含词条的两种排列产出相同输出

- **WHEN** 词表含短词 `ab` 与长词 `abcd`，内容为 `abcd`
- **THEN** 无论 `ab` / `abcd` 在内存列表中的先后，`filter` 的输出 SHALL 逐字节相同
- **AND** 该输出 SHALL 等于「仅按长词 `abcd` 替换」的结果（`***`），而非残留片段的 `***cd`

#### Scenario: 任意排列一致性

- **WHEN** 同一组 N 个词条以任意排列存在于内存列表
- **THEN** 对同一输入内容，所有排列下的 `filter` 输出 SHALL 相同

#### Scenario: 替换阶段不得泄露完整在册词

- **WHEN** 对任意在册 `level=1/2`（或 `level IS NULL`）词条与任意输入内容执行 `filter`
- **THEN** 输出 SHALL NOT 包含任何在册词条作为子串
- **AND** 系统 SHALL NOT 承诺「输出不含在册词的任何**片段**」——长词优先替换后残留的片段不属于在册词，不构成整词泄露

---

### Requirement: 长词优先替换（masking-longest-first，C2）

替换阶段 SHALL 以**词条长度降序**遍历，使得当长词与短词存在包含关系时，长词先获得匹配机会，短词在长词已被替换消失后不再重复命中。词条 SHALL 按「空词 / `null` 词排最后、长度降序、同长度按字面量升序」排序，以消除对具体排序算法稳定性实现的依赖。

#### Scenario: 长词先于其前缀被替换

- **WHEN** 词表含 `abcd` 与其前缀 `ab`，内容为 `abcd`
- **THEN** 输出 SHALL 为 `***`（长词整体被替换），SHALL NOT 为 `***cd`

#### Scenario: 空词与 null 词不参与排序比较的 NPE

- **WHEN** 内存词表中含 `word` 为 `null` 或空串的词条
- **THEN** 排序 SHALL 将其置于末尾且 SHALL NOT 抛出 `NullPointerException`
- **AND** 过滤时 SHALL 跳过该词条（不拦截、不替换），不影响其它词条正常生效

---

## MODIFIED Requirements

### Requirement: 敏感词实时过滤（content-moderation C1）

过滤 SHALL 分为两个语义独立、均与词表行顺序无关的阶段：**拦截阶段**以**原始未替换内容**判定 `level=3`；**替换阶段**以**长度降序**的词条序列判定 `level=1/2`。

#### Scenario: 拦截阶段不受顺序与替换影响

- **WHEN** 内容命中任一 `level=3` 词条
- **THEN** 系统 SHALL 返回 `CODE_2701`，内容不入库、不推送
- **AND** 该判定 SHALL 基于**原始内容**进行（不受替换阶段的词长顺序影响），且 SHALL NOT 因词表顺序而变化

#### Scenario: 替换阶段在热路径不引入排序开销

- **WHEN** 词库发生变更并触发 `reload()`
- **THEN** 长度降序序列 SHALL 在 `reload()` 内一次性构建并随词库成对发布
- **AND** 每条消息的 `filter` 调用 SHALL NOT 执行排序（热路径仅线性扫描）

**变更前（引用原 spec）**:
> 命中 `level=1/2`（提醒/替换）的敏感词时，系统 SHALL 将内容中的该词替换为 `***` 后继续；

**变更后**:
> 命中 `level=1/2`（提醒/替换）的敏感词时，系统 SHALL 将内容中的该词替换为 `***` 后继续；替换 SHALL 按**词条长度降序**执行，使互为子串的词条中长词优先获得匹配机会，最终输出 SHALL 与词表行顺序无关。排序 SHALL 在词库加载时预计算，不在消息过滤热路径内执行。

---

## REMOVED Requirements

无。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 打码结果与词表顺序无关 | ADDED: 打码顺序无关性（masking-order-independence） | 1.1, 1.4, 3.3 |
| C2 长词优先命中 | ADDED: 长词优先替换（masking-longest-first） | 1.2, 2.3, 2.4 |
| C3 level=3 语义不变 | MODIFIED: 敏感词实时过滤（content-moderation C1） | 2.4, 3.3 |
| C4 性能不退化 | MODIFIED: 敏感词实时过滤（content-moderation C1） | 2.2, 2.5 |
