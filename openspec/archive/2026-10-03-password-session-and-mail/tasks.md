# Tasks — 密码变更后会话失效 + 邮箱验证码真实投递

- 关联 Design: `openspec/changes/2026-10-03-password-session-and-mail/design.md`
- 创建日期: 2026-10-03
- 预估总工时: 8h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。
> **TDD 红阶段纪律（根 AGENTS.md §2.1-3）**：新增方法先只落方法签名 + `throw new UnsupportedOperationException()` 桩，让测试**编译通过并跑红**，再填实现。若已跳过，须用变异检验补偿并在 QA 说明。

## 阶段零：门禁先红后绿（本批新增的机控）

- [x] **T0.1** 新增 `scripts/verify/verify_password_session.mjs`：静态审计「改密 / 找回密码成功后是否调用会话失效」+「`sendEmailCode` 是否仍把验证码写日志」+「`checkRateLimit` 是否存在 `token == null` 直接 return」。在**未改造**的代码上实跑，必须 exit=1 且报出全部三项 — ≤1h
- [x] **T0.2** 补 `scripts/verify/mutation_password_session.mjs` 变异脚本（破坏守卫确认 T0.1 会转红） — ≤30min
- [x] **T0.3** T0.1 / T0.2 接入 `ci.yml` gates job 与 `pre-push` hook — ≤30min

> **T0 实施记录**：
> - T0.1 首次实跑 **9/29 pass / exit=1**，24 项红全部对应真实缺陷。
> - **T0.1 自身出过两次假红并已修正**（否则「先红后绿」不成立）：
>   ① `stripCommentsAndStrings` 把字面量内容也清空，导致 `value = "/login"`、`"rate_limit:"`、`"X-Forwarded-For"` 一律查不到 → 改为「字面量判定用 methodBody 原文，实参判定才剥字符串」；
>   ② 端点注解顺序是 `@PostMapping` **在前**、`@GlobalInterceptor` 在后，我最初向**后**找注解 → 4 项恒红，改为向前找且不越过方法签名。修正后 4 项正确转绿。
> - T0.2 采用**沙箱副本**而非「临时改写真实源文件 + 脏工作区守卫」：把门禁与其读取的 10 个文件复制到临时目录，门禁从副本位置解析 ROOT，天然只读沙箱，**不存在还原覆盖未提交改动的风险**。已验证「沙箱基线 == 真实基线」判定生效，并正确拒绝对尚未创建的 `MailService*` 记为通过。
> - 判别力实跑留到 T5.4（当前门禁仍红，此时跑变异脚本无意义）。

## 阶段一：鉴权面单测（先补安全网，再改鉴权代码）

- [x] **T1.1** **[TDD]** 新增 `GlobalOperationAspectTest`：Mockito mock `RedisUtils`，覆盖 `checkLogin` 无 token → `CODE_2001`、`checkAdmin` 非管理员 → `CODE_1003`、管理员放行、`checkRateLimit` token 为 null 时按 IP 计数且超限抛 `CODE_1001` — ≤2h
- [x] **T1.2** **[TDD]** 上述测试先落签名 + `UnsupportedOperationException` 桩，跑红并截图/记录输出 — ≤30min

> **T1 实施记录**：13 例。
> - **红灯（证据 `engineering/qa/2026-10-03-password-session-and-mail-tdd-red.txt`）**：13 例 / **5 红**，红的恰好是 `rateLimit_*` 五例，8 绿为既有 `checkLogin` / `checkAdmin` 行为 → 证明脚手架正确、红的原因确实是限流未实现，而非测试写错。
> - 过程中修正两处**测试自身**缺陷：① lambda 内 `Class#getMethod` 抛受检异常导致编译失败 → 端点解析提到 lambda 外；② `rateLimit_withToken_usesTokenDimensionKey` 原用 `getSysSetting`（只标 `@GlobalInterceptor`，`checkRateLimit=false`）根本走不到限流分支 → 查明**全仓 `checkRateLimit=true` 的 4 个端点全是 `checkLogin=false`**，token 维度分支只能靠「带旧 token 重登」触达，改用 `loginEndpoint` + token 头。

## 阶段二：C3 未登录端点限流

- [x] **T2.1** **[TDD]** `GlobalOperationAspect#checkRateLimit`：token 为 null 时改用 `rate_limit:ip:{ip}` 计数，**不 return**；IP 取 `X-Forwarded-For` 首段（回退 `getRemoteAddr()`） — ≤1h
- [x] **T2.2** `GlobalOperationAspectTest` 全绿；**变异检验**：把「不 return」改回 return，确认对应用例转红 — ≤30min

> **T2 实施记录**：新增 `resolveClientIp`（X-Forwarded-For 首段 → `getRemoteAddr` → `unknown`）与常量 `RATE_LIMIT_PER_MINUTE` / `RATE_LIMIT_WINDOW_SECONDS`（原本是散落字面量 `60`）。
> 绿：13/13。变异：把 IP 分支改回 `return` → **同样 5 例转红**，与红灯阶段完全对应 → 有判别力。已还原。
> 已知代价（design ADR-003）：`X-Forwarded-For` 客户端可伪造，直连部署下可换取多个限流配额。已在方法注释写明。

## 阶段三：C1 密码变更后会话失效

- [x] **T3.1** **[TDD]** `UserInfoServiceImplTest` 新增 2 例：`updatePassword` 成功 → 调 `cleanUserTokenByUserId(userId)` 且调 `forceOffLine(userId)`；`resetPasswordByEmail` 成功 → 同上。**先落桩跑红** — ≤1h
- [x] **T3.2** `UserInfoServiceImpl#updatePassword`：成功后追加 `cleanUserTokenByUserId` + `forceOffLine`；**保持调用点在密码写入之后、异常抛出处之后** — ≤30min
- [x] **T3.3** `UserInfoServiceImpl#resetPasswordByEmail`：同样追加两处；须确保「验证码错误 / 已过期 / 用户不存在」三条早退路径**不触发**会话失效 — ≤30min
- [x] **T3.4** 全量单测 `mvn test` 通过；**变异检验**：注释掉 `cleanUserTokenByUserId` 调用，确认 T3.1 用例转红 — ≤30min

> **T3 实施记录**：实际写了 **4** 例（比计划多 2 例守卫），全量 **217/217 绿**（基线 200 + 13 + 4）。
> - 红灯：56 例 / **3 红**，恰为 `updatePassword_success_invalidatesAllSessions`、`updatePassword_success_pushForceOffLine`、`resetPasswordByEmail_success_invalidatesAllSessions`。第 4 例守卫 `resetPasswordByEmail_codeNotFound_doesNotInvalidateSessions` 在红灯态即绿——**恒绿即无判别力**，故 T3.4 专门为它设计了变异。
> - 变异 B（把吊销挪到方法开头）→ 守卫用例**恰好转红**，证明它守得住「输错验证码就能踢人下线」这个 DoS 面。
> - 变异 A（注释掉 `forceOffLine`）→ 恰好 1 例转红。
> - **变异过程自身出过两次错，均已识别并纠正**：① 第一次变异输出为空，我一度以为「用例没判别力」，实为 `oldString` 吞掉了方法闭合大括号导致编译失败——**编译失败不是变异成功**；② 修复后重跑才拿到真实的 1 例转红。教训：看变异结果必须同时看 `BUILD` 行，不能只看有没有 FAILURE。
> - 实现落点：`updatePassword` 在 `recordLog` 之后；`resetPasswordByEmail` 在验证码标记已用之后。两者都在**全部校验与写库之后**，满足 design ADR-001。

## 阶段四：C2 邮箱验证码真实投递

- [x] **T4.1** **[TDD]** 新增 `MailServiceTest`：① `spring.mail.host` 为空 → 抛 `CODE_1002` 且**不调用** `JavaMailSender`；② host 已配但投递抛异常 → 包装为 `CODE_1002`；③ 正常路径 → 主题为固定文案、**不含 email**。先落桩跑红 — ≤2h
- [x] **T4.2** `pom.xml` 新增 `spring-boot-starter-mail`（**不写版本号**，由 parent 管理） — ≤15min
- [x] **T4.3** 新增 `MailService` 接口 + `MailServiceImpl`：`sendVerifyCode(email, code, type)`；未配置 host → fail-closed；主题固定，正文含 code 与有效期 — ≤1h
- [x] **T4.4** `UserInfoServiceImpl#sendEmailCode` 末尾：`logger.info(...code...)` **删除**，改为调 `mailService.sendVerifyCode`；**Mapper 写入顺序保持不变**（先落库再发信，发信失败不回滚验证码记录） — ≤30min
- [x] **T4.5** `application-dev.properties` / `application-prod.properties` / `.env.example` 新增 `spring.mail.*` / `SPRING_MAIL_*` 占位（**零裸值**） — ≤30min
- [x] **T4.6** 扩充 `scripts/verify/verify_no_hardcoded_secret.mjs`：断言 mail 密码无裸值 + prod mail 键均为占位符；实跑 19+N 全绿 — ≤30min

## 阶段五：验证与同步

- [x] **T5.1** `mvn test` 全绿（基线 200 例 + 新增）；记录例数 — ≤30min
- [x] **T5.2** `mvn package -DskipTests` 0 error，确认产物 `Spring-Boot-Version` 现状并记入 QA（`pom.xml` 插件锁 2.2.6 与 parent 2.6.1 倒挂属已知技术债，**本批不动**） — ≤30min
- [x] **T5.3** 活体冒烟脚本 `scripts/smoke/smoke_password_session.py`：① 改密后旧 token 请求 → `2001` ② 改密后新登录正常 ③ 找回密码后旧 token → `2001` ④ `sendEmailCode` 未配 SMTP → `1002` 且**日志中搜不到验证码明文** ⑤ 未登录端点连续请求触发限流 — ≤2h
- [x] **T5.4** 变异脚本 `mutation_password_session.mjs` 实跑有判别力（≥N/N） — ≤30min
- [x] **T5.5** 回归全绿：`check-api-contract --strict`（契约零漂移）/ `check-ipc-registration --strict` / `check-openspec-hygiene` / 原有 8 个门禁 — ≤30min
- [x] **T5.6** **修 `docs/system-facts.md` §12 事实失真**：删除「修改密码…成功后关闭 WS 强制重登」的错误记载（该行为本变更才首次成立），追加「邮件真实投递 + 未配 SMTP 时 fail-closed」与「限流键降级为 IP」两条事实 + 变更日志一行 — ≤30min
- [x] **T5.7** `engineering/qa/2026-10-03-password-session-and-mail.md`（含冒烟输出证据文件） — ≤30min

## 阶段六：收尾

- [x] **T6.1** `engineering/retro/2026-10-03-password-session-and-mail.md`（做得好 / 问题 / 原因 / 改进方案 四段式） — ≤30min
- [x] **T6.2** spec-delta 回写 `openspec/specs/password-bcrypt/spec.md`（MODIFIED 会话失效 + 新增邮件投递与限流 Requirement） — ≤30min
- [x] **T6.3** 登记后续批次 4 项（验证码 `try_count` / `operation_log.ip_address` / `@所有人` 服务端鉴权 / `verify_schema_drift.mjs`）到 `docs/system-facts.md` 遗留表 — ≤20min
- [x] **T6.4** `git mv openspec/changes/2026-10-03-password-session-and-mail openspec/archive/2026-10-03-password-session-and-mail`；按域拆提交（`auth` / `common` / `docs`） — ≤30min

## 遗留（本批明确不做）

| 项 | 原因 | 后续 |
|----|------|------|
| `email_verify_code` 补 `try_count` 做验证码失败次数限制 | 属 DB 结构变更（L4），避免与本批鉴权改造混在一起（design ADR-005） | 独立 Change，需 migration-013 |
| `operation_log.ip_address` 恒为 `null`（6 处调用全传 null） | 需 AOP 取 `RequestContextHolder`，属横切面改造 | 独立 Change |
| `@所有人` 权限下沉服务端 | 涉权限语义（L4） | 独立 Change |
| `verify_schema_drift.mjs` 连库版门禁 | 独立工程化 Change | 本会话下一批 |

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-10-03-password-session-and-mail/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵对应行执行：`mvn test` 全绿、`mvn package -DskipTests` 0 error
- [x] 新增生产依赖已登记（`spring-boot-starter-mail`），`verify_no_hardcoded_secret.mjs` 已覆盖 mail 键
- [x] `docs/system-facts.md` §12 事实失真已纠正，变更日志已追加
- [x] 证据已落 `engineering/qa/`（含冒烟终端输出快照），**遗留 GUI / SMTP 真实投递项如实标注未运行**
- [x] 归档闭环完成（spec-delta 回写 `specs/` + `git mv` 到 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`
