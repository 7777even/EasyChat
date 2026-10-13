# QA — 邮箱验证码失败次数限制（遗留 #1 根治）

- 日期: 2026-10-13
- 关联 Change: `openspec/archive/2026-10-13-email-code-failure-limit/`
- 效率等级: **L3**（原判 L4，经查证修正）

## 一、效率等级修正（重要）

立项时本项被标为 **L4（含迁移脚本）**，前提是「失败次数需持久化 ⇒ 须给
`email_verify_code` 加 `fail_count` 列」。**该前提经查证不成立**：

- `RedisUtils` 已有 `incr` / `expire` / `delete` / `get`（与 `GlobalOperationAspect`
  的 `applyRateLimit` 同一套机制），失败计数**完全不必落库**；
- `RedisComponet` 是业务语义封装（无 `incr`），但注入既有 `RedisUtils` Bean 即可，
  **零新依赖**（`pom.xml` diff 为空已验证）。

故实际为 **L3**：无表结构变更、无迁移脚本、`easychat.sql` 与
`baseline-version` 均未触碰。

## 二、两处事实订正

| 此前结论 | 查证结果 |
|---|---|
| 「攻击者可**换邮箱**绕过」 | **不成立**。`sendEmailCode` 对 `type=1` 要求用户已注册（`UserInfoServiceImpl:446-449`），`resetPasswordByEmail` 也 `selectByEmail` 否则抛 2101（`:508-510`）——未注册邮箱根本拿不到有效码 |
| 「根治必 L4 + 迁移脚本」 | **不成立**。见上节 |

## 三、风险量化（自算）

| 事实 | 值 | 出处 |
|---|---|---|
| 验证码位数 / 空间 | 6 位 / 10⁶ | `StringTools.getRandomNumber(Constants.LENGTH_6)` |
| 有效期 | 10 分钟 | `expireTime = now + 10*60*1000` |
| 端点限流 | 60 次/分 | `RATE_LIMIT_PER_MINUTE` |
| **单邮箱 10 分钟可尝试** | **600 次** | 上两行相乘 |
| **修复前命中概率** | **0.06%** | 600 / 10⁶ |
| **修复后** | **0.0005%** | 5 / 10⁶，**降 120 倍** |

## 四、验收口径与实际执行

### 4.1 TDD 红阶段（tasks 2.1）

第一次跑：**编译失败**于三个尚不存在的常量（`找不到符号` ×6）。
按 AGENTS §2.1 第 5 条先只加常量让测试编译通过，再跑：

```
Tests run: 67, Failures: 4
  resetPasswordByEmail_firstFailure_setsWindowTtl
  resetPasswordByEmail_overLimit_rejectedBeforeDbQuery
    expected:<验证码错误[次数过多，请 10 分钟后再试]> but was:<验证码错误[]>
  resetPasswordByEmail_success_clearsFailureCounter
  resetPasswordByEmail_wrongCode_incrementsFailureCounter
```

> ⚠️ 其中 `codeExpired_doesNotCountAsFailure` 与 `userNotFound_doesNotCountAsFailure`
> 两条「负向断言」此时**空洞为真**（生产代码根本没有任何计数）。它们的有效性
> 由 tasks 4.2 的变异检验证明，不是靠「当前绿」。

### 4.2 转绿

`mvn test -Dtest=UserInfoServiceImplTest` → **Tests run: 67, Failures: 0, Errors: 0**。
全量 `mvn test` → **883 例**（本轮前 873，**+10**）。

### 4.3 变异验证（**4/4 捕获，其中 1 处初判为等价变异后被封住**）

| # | 变异 | 结果 |
|---|---|---|
| 1 | `EMAIL_CODE_FAIL_MAX` 5 → 50 | **0 条转红（等价变异）** → 补值锚定断言后 → **1 条转红** |
| 2 | 把「码已过期」也计入失败 | **1 条转红**：`resetPasswordByEmail_codeExpired_doesNotCountAsFailure` |
| 3 | 锁定检查移到 `selectList` 之后 | **1 条转红**：`resetPasswordByEmail_overLimit_rejectedBeforeDbQuery`（`NeverWantedButInvoked`） |
| 4 | 去掉成功后的 `clearEmailCodeFailure` | **1 条转红**：`resetPasswordByEmail_success_clearsFailureCounter` |

**关于变异 1 的诚实登记**：本组用例均引用 `Constants.EMAIL_CODE_FAIL_MAX`，
与生产代码**同源**，故改常量对它们是**等价变异**（两边一起动，照常通过）——
实测确实 0 条转红。按 AGENTS §2.1 第 3 条不得假装覆盖，处理方式是
**补一条字面量值锚定断言**（`emailCodeFailureLimit_thresholdIsPinnedToAgreedValue`
把 5 与 600 写死）。理由：阈值调大是**静默的安全降级**
（0.06% 悄悄变成 5%），不该无声发生；值锚定会迫使改动者**有意识地**重新评估。
该断言同时校验窗口 = 600 = 10 分钟，与验证码寿命一致。

### 4.4 DoD 零改动核验

| 项 | 结果 |
|---|---|
| `easychat.sql` / `easychat-migration-*.sql` | **无改动 ✓** |
| 前端（`easychat-front/`） | **无改动 ✓** |
| `pom.xml`（新依赖） | **无改动 ✓** |
| `node scripts/check-api-contract.mjs --strict` | **exit 0 ✓**（接口契约未变） |
| 全部 `scripts/verify/*.mjs` | **exit 0 ✓** |

本次仅涉及 3 个源文件：`Constants.java` / `UserInfoServiceImpl.java` /
`UserInfoServiceImplTest.java`。

### 4.5 Scenario → 断言对应

| spec-delta Scenario | 用例 |
|---|---|
| 猜错后计数累加 | `resetPasswordByEmail_wrongCode_incrementsFailureCounter` |
| 窗口只在首次设置 | `resetPasswordByEmail_firstFailure_setsWindowTtl` |
| 超过阈值拒绝且先于查库 | `resetPasswordByEmail_overLimit_rejectedBeforeDbQuery` |
| 未达阈值仍正常查库 | `resetPasswordByEmail_underLimit_stillQueriesDb` |
| 成功验证后清零 | `resetPasswordByEmail_success_clearsFailureCounter` |
| 重发新码不清零 | `resetPasswordByEmail_sendEmailCode_doesNotClearCounter` |
| 过期不计数 | `resetPasswordByEmail_codeExpired_doesNotCountAsFailure` |
| 用户不存在不计数 | `resetPasswordByEmail_userNotFound_doesNotCountAsFailure` |
| Redis 异常 fail-open | `resetPasswordByEmail_redisUnavailable_failsOpen` |
| 阈值/窗口值锚定 | `emailCodeFailureLimit_thresholdIsPinnedToAgreedValue` |

## 五、本轮自身失误与修正

1. **测试里写了多余 stub，被 `MockitoJUnitRunner` 严格模式当场抓住**
   （`redisUnavailable_failsOpen` 里 stub 了 `incr`，但成功路径不调它）。
   已删除并在代码注释中写明「本用例走校验通过路径，不会调 incr」。
2. **`ResponseCodeEnum.CODE_2101.getMessage()` 不存在**（实际是 `getMsg()`），编译失败。
3. **第一次补测试时吃掉了段落分隔注释**（`// ==== 密码变更后会话失效 ====`），
   靠 `git diff` 发现后补回 —— 这是本会话第二次因 `edit` 短片段替换误删相邻行。

## 六、未运行 / 不可运行项

| 项 | 原因 |
|---|---|
| 真实 Redis 上的计数与 TTL 行为 | 单测用 mock；实际 TTL / INCR 语义由 Redis 保证。**真机验证缺口已登记** |
| 真实邮件 + 浏览器端到端 | 需 SMTP 配置 + GUI 环境 |
| CI 首跑 | `gh` 未认证，**不做推测** |

## 七、结论

**通过**。4 处变异全部捕获（含 1 处等价变异被值锚定封住）；
DB / 前端 / 依赖三处零改动；接口契约零变化，三条既有错误文案一个未改；
spec 的两条「已知边界」已写明。