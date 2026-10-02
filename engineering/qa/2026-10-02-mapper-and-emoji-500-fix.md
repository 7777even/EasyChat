# QA 报告 — 用户状态与表情包两处 500 修复（L2 缺陷修复）

- 日期: 2026-10-02
- 范围: `UserStatusMapper.xml` 占位符、`EmojiController` 鉴权取用户方式（无独立 Change，属已归档能力的缺陷修复）

## 背景

执行 `2026-10-01-password-handoff-unify` 时，活体验证发现 `/userStatus/set` 与 `/emoji/list` 仍返回 500。二者此前都被「表不存在」的 500 掩盖——`migration-010` 补建四张表后才浮出下一层缺陷。

## 两处根因（不同类）

| 端点 | 根因 | 修复 |
|------|------|------|
| `/userStatus/set` | `UserStatusMapper.xml` 的 `insert` 用裸属性 `#{userId}`，而 `BaseMapper.insert` 带 `@Param("bean")` → 运行期 `BindingException: Parameter 'userId' not found. Available parameters are [bean, param1]` | 占位符改为 `#{bean.userId}` 等 5 个限定名 |
| `/emoji/list` | `EmojiController` 读 `request.getAttribute("userInfo")`，而 `GlobalOperationAspect` **只校验不写入**该 attribute → 恒为 null → `NullPointerException` | 改为 `extends ABaseController`，用 `getTokenUserInfo(request)` |

> 第二处的教训值得记：全仓仅此一处用了 `getAttribute("userInfo")`，与「Controller 统一用 `ABaseController.getTokenUserInfo`」的既有约定相悖，属于孤例。

## 配套门禁：`scripts/verify/verify_mapper_params.mjs`

解析 Mapper 接口的 `@Param`（含 `BaseMapper` 继承方法的 `bean`/`query`/`list`）与 XML 占位符，按 MyBatis 三种运行期语义判定：

- 有 `@Param` → `parameterObject` 是 `ParamMap`，必须写限定名
- 无 `@Param` 且单参数 → `parameterObject` 就是 POJO 本身，裸属性**合法**
- 无 `@Param` 且多参数 → 只能用 `param1..paramN` / `arg0..`

全仓 **27 个 Mapper 审计通过**。

> 该脚本第一版规则过严（误判「单参数无 @Param 时裸属性」为违规，产生 4 个假阳性：EmojiMapper / FavoriteMapper / OperationLogMapper / ReportAuditLogMapper）。修正后才定位到唯一真实违规 `UserStatusMapper`。**静态审计脚本必须先用已知真/假阳性校准，否则会把噪声当结论。**

## 实际执行命令与用例数

| 命令 | 结果 |
|------|------|
| `node scripts/verify/verify_mapper_params.mjs`（修复前） | **1 FAIL**：`UserStatusMapper.xml` insert 6 个占位符非法 |
| 同上（修复后） | **27/27 通过**，0 error |
| `mvn -B -o compile` | BUILD SUCCESS |
| `mvn -B -o test` | Tests run: **120**, Failures: 0 |

### 活体复测（本机 MySQL 3306 / Redis 6379 / 后端 5050）

```
login http=200 code=0
/favorite/list     http=200 code=0
/emoji/list        http=200 code=0    ← 修复前 500（NPE）
/userStatus/get    http=200 code=0
/userStatus/set    http=200 code=0    ← 修复前 500（BindingException）
/userStatus/clear  http=200 code=0
```

## 未运行项

- 单测层面无法覆盖：单测 mock 掉 Mapper，不解析 XML，`BindingException` 只有真连库才会暴露。这也是新增静态审计门禁的原因。
- GUI 端到端（表情包上传/列表、状态设置页交互）待本机手动验证。

## 结论

**通过**。两处 500 均已修复并活体验证为 200，全仓同类问题经静态审计确认仅此一处。静态审计脚本已入 `scripts/verify/`，可作为回归门禁。
