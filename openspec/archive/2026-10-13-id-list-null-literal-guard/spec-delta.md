# Spec Delta — 名单解析器剔除「空值字面量」

- 关联 Tasks: 2026-10-13-id-list-null-literal-guard/tasks.md
- 创建日期: 2026-10-13

## ADDED Requirements

### Requirement: 名单解析不得把「空值字面量」当作用户 id

`IdListTools.parse` 是朋友圈可见性判定（`canView` case 3/4）的名单来源。
它判空依赖 `StringTools.isEmpty`，而后者**只认小写 `"null"`** ——
于是大写 `"NULL"` 及 JS / Python 的空值表示会**被当成一个真实的用户 id 产出**。

后果：白名单模式下得到 `["NULL"]` → **除作者外所有人都看不到该动态**（含被误当作
白名单成员的那个 id 也不可能匹配任何真实用户）。这是「名单非空但全为垃圾」。

#### Scenario: 大写 NULL 被剔除

- **WHEN** 解析 `"NULL"`
- **THEN** 返回**空列表**
- **AND** **不**产出任何形如 `"NULL"` 的成员

#### Scenario: 各语言空值表示一律剔除（大小写不敏感）

- **WHEN** 解析含 `null` / `NULL` / `Null` / `undefined` / `UNDEFINED` / `NaN` / `none` 的名单
- **THEN** 这些 token **全部**被剔除
- **AND** 其余合法 id **原样保留**

#### Scenario: 剔除后与「空输入」行为一致

- **WHEN** 名单内容**全部**是空值字面量（如 `"NULL"`、`"None"`）
- **THEN** 解析结果与解析空串**完全相同**（空列表）
- **AND** 白名单判定 `contains` 两者同为 false、黑名单 `!contains` 两者同为 true
- **AND** **理由**：隐私判定对脏输入必须与对空输入表现一致，否则脏数据会造出
  「看似非空、实则谁都不匹配」的名单

#### Scenario: 合法 id 不受影响

- **WHEN** 解析 `["U01234567890","U09876543210"]` 或宽松形态 `U01234567890,U09876543210`
- **THEN** 两个 id **原样返回**
- **AND** **不**因本守卫被误伤

#### Scenario: 宽松包络语义不变

- **WHEN** 解析 `["U001","U002"]` / `[ "U001" , "U002" ]` / `"U001","U002"` / `  ["U001"]  `
- **THEN** 仍按既有宽松语义解析成功
- **AND** 空串元素与纯空白元素**仍**被忽略（2026-10-05 修复的行为不回退）

#### Scenario: 守卫不得波及其他空值判定

- **WHEN** 审视 `StringTools.isEmpty`
- **THEN** **不**修改它（它被全仓几十处调用）
- **AND** 空值字面量判定**只**加在 `IdListTools.parse` 内部

#### Scenario: 写入口的严格校验不受影响

- **WHEN** `IdListTools.validate` 校验用户提交的名单
- **THEN** 语义**完全不变**（本变更只动 `parse`）
- **AND** `parse` 与 `validate` 的「宽松 / 严格」口径差异仍然存在

---

## MODIFIED Requirements

（无。`parse` 的既有契约是「宽松且永不为 null」，本变更在该契约内收窄了
「什么算一个成员」，不与之冲突。）

---

## REMOVED Requirements

（无）

---

## 已知边界（本变更**刻意不做**的事）

### 边界 1：不清洗活库中已存在的 `"NULL"`

该行（`U69630787860` 的 `moment_visible_list` / `moment_invisible_list`）经查证
**当前不影响任何判定**：后端该两列不参与 `canView`，前端 `parseIdList` 对非 JSON
会 `catch` 返回 `[]`。本变更后它同样无害。**洗数据不治本**（下次数据往返仍会写入）。

### 边界 2：不改用 id 形状白名单

真实 id 为 `U` + 11 位数字，但**既有测试数据是 `U001`（3 位）**。
形状白名单会打破既有测试且可能误伤存量数据，故只用可证安全的黑名单。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 名单解析不得把「空值字面量」当作用户 id | 2.1~2.6, 3.1, 3.2, 4.1~4.3 |