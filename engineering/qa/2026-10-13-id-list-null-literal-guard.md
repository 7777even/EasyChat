# QA — 名单解析器剔除「空值字面量」（遗留 #20 治本）

- 日期: 2026-10-13
- 关联 Change: `openspec/archive/2026-10-13-id-list-null-literal-guard/`
- 效率等级: L3（触及朋友圈可见性判定路径，属权限语义）

## 一、范围

| 对象 | 性质 |
|---|---|
| `IdListTools.parse` 增加空值字面量黑名单 | 业务规则收窄 |
| `IdListToolsTest` 新增 6 例 | 行为测试 |

**未改**：`StringTools.isEmpty`、`IdListTools.validate`、活库数据、前端、依赖。

## 二、根因（与台账原记载不同）

台账把 #20 当作「数据脏了」。查证后确认 **根因在解析器**：

```java
// StringTools.isEmpty
if (null == str || "".equals(str) || "null".equals(str) || "\u0000".equals(str)) {
```

**只认小写 `"null"`**。于是 `parse` 里 `isEmpty(norm)` 对 `"NULL"` / `"undefined"` /
`"NaN"` / `"None"` 一律放行 → `set.add(...)` → **产出一个「用户 id」**。

**同时订正台账此前的处置建议**（「放宽空名单前必须先处理此脏值，否则白名单模式下无人可见」）——
**该判断不成立**：per-moment 名单来自前端 `whiteSelected`（`PublishMoment.vue:352`），
不来自用户级那一列。放宽空名单后，脏列也只影响发布页**预填**（`parseIdList` 遇非 JSON
会 `catch` 返回 `[]`）。**洗数据不治本。**

## 三、爆炸半径核实（AGENTS §2.1 第 12 条）

活库实测（ASCII SQL 文件 + hex 比对，规避 GBK）：

| 对象 | 实测 | 影响 |
|---|---|---|
| `user_info.moment_visible_list` / `moment_invisible_list` | `U69630787860` 两列 = `4E554C4C`（ASCII `"NULL"`，`LENGTH()`=4） | `canView` **不读**这两列；前端 `parseIdList` `catch` → `[]` |
| `moment.visible_list` / `invisible_list` | 2 行，长度 **0**（空字符串，非 `"NULL"`） | `canView` **主路径不受影响** |

另经全仓 grep 确认：`getMomentVisibleList()` / `getMomentInvisibleList()` 在主源码**零调用**，
`momentVisibility` 只被写入与放进 VO —— 且**前端确实接线**了
（`PublishMoment.vue:220-230` 用它预填发布页），故特性本身有效，非死代码。

## 四、为何用黑名单而非 id 形状白名单（ADR-001，实测否决）

既有测试 `IdListToolsTest` 用的是 **`U001` / `U002`（3 位）**；生产是 `U` + **11 位**数字。
形状白名单无论按 `U\d{11}` 还是 `U\d+` 写，都会打穿其中一边。

黑名单**可证安全**：真实 id 一律以 `U`（用户）/ `G`（群）开头
（`StringTools#getUserId` / `#getGroupId` 均拼接 `UserContactTypeEnum` 的 prefix），
**不可能等于** `null`/`undefined`/`nan`/`none`。且判定为**全等**而非包含，
故 `U0null1` / `Unone1` 这类 id 不受影响（有用例锁定）。

## 五、行为恒等性证明

`canView`：case 3 白名单 `visible.contains(viewerId)`；case 4 黑名单 `!invisible.contains(viewerId)`。

| 名单内容 | 旧 parse | 新 parse | 白名单 contains(realId) | 黑名单 !contains(realId) |
|---|---|---|---|---|
| `"NULL"` | `["NULL"]` | `[]` | false → false | true → true |
| `"null"` | `[]` | `[]` | false → false | true → true |
| `["U001","NULL"]` | `[U001, NULL]` | `[U001]` | true → true | false → false |
| `["U001","U002"]` | 不变 | 不变 | 不变 | 不变 |

`realId` 恒以 `U`/`G` 开头 → **两列恒等**，对正确数据零行为影响。

## 六、TDD 与变异

### 6.1 红阶段

`mvn test -Dtest=IdListToolsTest` → **Tests run: 31, Failures: 5**
（`parseSkipsUppercaseNullLiteral` / `parseSkipsNullFamilyLiterals` /
`parseSkipsNullLiteralCaseInsensitively` / `parseMixedListKeepsOnlyRealIds` /
`parseAllJunkEqualsEmptyInput`）。
`parseRealIdsUnaffected`（回归护栏）红阶段即绿 → 证明合法 id 现状本就正常。

### 6.2 转绿

`Tests run: 31, Failures: 0`（既有 25 条**零修改零失败**）。

### 6.3 变异验证

| # | 变异 | 结果 |
|---|---|---|
| 1 | `isNullLiteral` 改成**大小写敏感**（`contains(token)`） | **5 条转红** |
| 2 | 黑名单漏掉 `undefined` | **4 条转红** |
| 3 | 判定提前到 `isEmpty` 之前并 `continue` | **0 条转红 = 等价变异** |

**等价变异如实登记**（AGENTS §2.1 第 3 条，不得假装覆盖）：小写 `"null"` 被
`StringTools.isEmpty` 与 `isNullLiteral` **各覆盖一次**，两个判定的先后顺序不影响结果，
故任何针对「顺序」的变异都抓不到。

## 七、验收口径与实际执行

| 项 | 命令 | 结果 |
|---|---|---|
| 目标测试 | `mvn test -Dtest=IdListToolsTest` | **31 例全绿** |
| 后端全量 | `mvn test` | **889 例**，Failures 0 / Errors 0（本轮前 883，+6） |
| `canView` 回归 | `MomentServiceImplTest` | 随全量通过，未受影响 |
| 活库数据 | 未执行任何 UPDATE | 保持原样（**本变更不动数据**） |
| `StringTools.isEmpty` | `git diff` | **无改动** |
| `IdListTools.validate` | `git diff` | **无改动** |
| 前端 / 依赖 | `git status` | **零改动** |

## 八、未运行 / 不可运行项

| 项 | 原因 |
|---|---|
| 真实朋友圈端到端（真机验证白/黑名单） | 需 GUI + 多账号 + 造动态；行为恒等性已由解析层断言 + 逐分支推导证明 |
| CI 首跑 | `gh` 未认证，**不做推测** |

## 九、结论

**通过**。2 处非等价变异全部精确捕获，1 处等价变异如实登记；
行为恒等性经逐分支证明；`StringTools.isEmpty` / `validate` / 活库数据 / 前端 / 依赖 五处零改动。