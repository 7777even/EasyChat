# QA — 遗留 #19 @ 提及 ID 形态收紧

- Change: `openspec/archive/2026-10-12-at-mention-id-shape`
- 执行日期: 2026-10-12
- 等级: L3

## 范围与验收口径

范围限于**靓号字符集约束**（后端）与**@ 提及提取正则**（前端纯核心）+ 两处不实注释订正。
验收口径来自 spec-delta 的 Scenario，逐条对应下表的用例。

## 实际执行的命令与结果

| 命令 | 结果 |
|---|---|
| `mvn test`（后端全量） | **849 通过 / 0 失败 / 0 错误**（基线 844，+5 靓号用例） |
| `npm run test`（前端全量 vitest） | **746 通过 / 0 失败**（基线 729，+17 @ 提及用例） |
| `npm run build` | exit 0 |
| `node scripts/verify/verify_at_mention_core.mjs` | **25 项通过 / 0 失败**（基线 19，+C1~C6） |
| 全部 `scripts/verify/*.mjs`（14 道 pre-push 门禁） | 全部 exit 0 |

## TDD 红→绿证据

**前端正则**（红阶段先建用例）：
- 红：`npx vitest run at-mention-core.spec.js` → **10 failed / 46 passed**，
  转红的全是新增反例（`@Ubuntu`/`@User`/`@Usernames`/`@US`/`@UI`/邮箱 6 条 + 3 条边界 + 1 条端到端）。
- 绿：实现 `@(U\d+|Urobot)(?![\w])` 后 → 1 failed（见下方「自查到的一处测试错误」），修正后 56/56。

**后端靓号**：
- 红：`mvn test -Dtest=UserInfoBeautyServiceImplTest` → **3 failures + 1 error**（字母/特殊字符/空值/编辑路径）。
- 绿：加入 `checkBeautyUserId` 后 → 13/13。

## 变异验证（每条均记录转红的具体用例）

| 变异 | 转红结果 |
|---|---|
| 前端正则改回 `@(U[A-Za-z0-9]+)` | vitest **9 条转红**：不提取 @Ubuntu / @User / @Usernames / @US / @UI / @U01234567890abc / @U01234567890_x / @UrobotFoo / 端到端 @Ubuntu 不产出 atUserIds |
| 同一变异，门禁侧 | `verify_at_mention_core` **4 条转红**：C3、C4、C6，及 **B5「正则唯一性」守卫**（正则出现 0 次） |
| 后端校验改为「仅判空」 | `UserInfoBeautyServiceImplTest` **3 条转红**：lettersRejected / specialCharsRejected / editWithIllegalUserId |

## 活库取证（只读 SELECT）

| 探针 | 结果 |
|---|---|
| `user_info.user_id` 形态 | 4/4 为 `^U[0-9]+$`，长度恒 12 |
| 非 `U+数字` 的 user_id | **0 条** |
| `user_info_beauty`（靓号池） | **0 行** |
| `at_user_ids` 幻影值 / 覆盖 | 该列无数据 |

结论：收紧对存量**零影响**，无需数据迁移。

## 未运行项 / 已知缺口

1. **未做真机联调**：管理端「保存靓号」页面输入字母后的错误提示走的是既有 Toast 分支
   （`CODE_1001` 已在管理端多处使用），未启动应用点击验证。
2. **UI 证据形式**：按 `easychat-front/AGENTS.md` §4.1 判为 **B 形式（可执行行为证据）** ——
   同一输入下界面像素与交互路径**不变**（本次只改匹配口径，不改任何渲染）；
   证据为上述 17 条 vitest + 6 条门禁断言，逐条对应 spec-delta Scenario。
3. **后端未启服务冒烟**：改动为 Service 内入参校验，接口签名与响应结构不变；
   已有 Service 层 5 例覆盖新建/编辑两条路径。

## 自查到的一处测试错误（如实记录）

首轮实现后仍有 1 条用例红：把 `a@U123.com` 放进了「不得误命中」清单，
但它恰好是 **ADR-002 明确接受的残留**（不加左词边界的代价）。
处置：**改测试而非改设计** —— 把该用例移入「已容忍残留」分组并显式断言当前行为为
`['U123']`，同时在注释中写明「若将来实现 @我 红点，正确做法是按成员白名单过滤而非加左边界」。
避免后人把它当 bug「顺手修掉」而重新引入漏掉合法提及的假阴性。

## 结论

**通过**。验收标准逐条满足；两处变异均验证到判别力；存量零影响已由活库实证。