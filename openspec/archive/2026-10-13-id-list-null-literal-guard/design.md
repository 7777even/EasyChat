# Design — 名单解析器剔除「空值字面量」

## 架构

```
moment.visible_list / invisible_list   （读，per-moment 判定）
user_info.moment_visible_list          （读，仅用于发布页预填，不参与判定）
        │
        ▼
IdListTools.parse(json)                ← 本次唯一改动点
        │
        ├─ 包络宽松：容忍 [a,b] / a,b / 缺引号 / 首尾空白
        ├─ 空项忽略：""、纯空白（2026-10-05 已修）
        └─ ★ 新增：空值字面量忽略：null/ NULL /undefined/NaN/None
        │
        ▼
MomentServiceImpl#canView
   case 3 白名单：visible.contains(viewerId)
   case 4 黑名单：!invisible.contains(viewerId)
```

## 决策（ADR）

### ADR-001: 用**黑名单字面量**而非 id 形状白名单

最直觉的做法是「token 必须匹配 id 形状才保留」。**实测否决**：

- 既有测试 `IdListToolsTest` 用的 id 是 **`U001` / `U002`（3 位）**；
- 生产 id 是 `U` + **11 位数字**（`StringTools.getUserId`）。

形状白名单若按 `U\d{11}` 写 → **打破全部既有测试**；若放宽成 `U\d+` → 仍可能误伤
存量里的非标 id。**两者都不可接受**，因为这条路径决定「谁能看到朋友圈」，
误伤一条合法名单的后果是**该用户永久看不到**。

**改用黑名单**：`null` / `undefined` / `nan` / `none`（大小写不敏感）。
安全性可证：真实 id 一律以 `U`（用户）/ `G`（群）开头（`getUserId` / `getGroupId`
均拼接 `UserContactTypeEnum` 的 prefix），**不可能等于这几个字面量**。

### ADR-002: 大小写不敏感，而非只补 `"NULL"`

补全所有大小写变体（`Null`/`NULL`/`nUlL`）而非只修大写那一个。理由：
污染源是**各种工具的数据往返**（Java/JS/Python 的空值表示各不相同），
只挡一种变体等于给下一种变体留门。同理纳入 `undefined` / `NaN` / `None`。

### ADR-003: 不动 `StringTools.isEmpty`

它是**全仓通用**的空值判定（`encodeByMD5`、`checkPath` 等几十处在用），
把大小写放宽会波及所有调用点。**在 `IdListTools.parse` 内部单独判定**，
爆炸半径锁死在这一个方法里。

### ADR-004: 不动 `parse` 的宽松包络语义

`parse` 刻意宽松（容忍 `a,b,c` 无方括号、缺引号），这是 javadoc 明写的契约，
且是**读自己写入的数据**所依赖的。改成严格 JSON 会：
① 打破既有 5 条宽松性测试；② 改变 `canView` 对历史数据的行为。
**本次只补一个正交的判定：哪些 token 根本不是 id。**

### ADR-005: 不改数据（不清洗活库那 1 行）

用户已选择「治本」而非「洗数据」。且查证已证实：
- 该行当前**不影响任何判定**（前端 `parseIdList` catch 掉、后端不参与判定）；
- 洗数据**不解决隐患**（下次数据往返仍会写入），而解析器补上后脏数据在不在都无害。

## 行为恒等性证明（AGENTS §2.1 第 12 条：不证明不说「无影响」）

对任意输入，新旧 `parse` 的结果**仅在 token 属于上述字面量时**不同。逐分支核对 `canView`：

| 名单内容 | 旧 parse | 新 parse | 白名单 `contains(realId)` | 黑名单 `!contains(realId)` |
|---|---|---|---|---|
| `"NULL"` | `["NULL"]` | `[]` | false → false | true → true |
| `"null"` | `[]` | `[]` | false → false | true → true |
| `["U001","NULL"]` | `[U001, NULL]` | `[U001]` | true → true | false → false |
| `["U001","U002"]` | 不变 | 不变 | 不变 | 不变 |

`realId` 恒以 `U`/`G` 开头，永不等于字面量 ⇒ **两列结果恒等**。
**故本变更对既有正确数据零行为影响。**

## 风险

| 风险 | 缓解 |
|---|---|
| 误伤合法 id | 黑名单仅含 null 家族；真实 id 以 `U`/`G` 开头，可证不可能命中 |
| 打破既有测试 | 新增判定在 `StringTools.isEmpty` 之后、`set.add` 之前，只多剔一类 token；既有 5 条宽松性用例的 token 全是 `U001` 形态，不受影响（以实跑为准） |
| 将来放宽「允许空名单」后行为漂移 | 本变更**主动**先堵住解析器缺口，使那条路径将来放开时不再依赖脏数据恰好为 0 |

## 依赖

**零新依赖**（`HashSet`/`Arrays` 已在用）。

## 数据影响

**零**。不改表、不改字段、不迁移数据。