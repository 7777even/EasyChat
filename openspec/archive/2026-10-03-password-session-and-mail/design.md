# Design — 密码变更后会话失效 + 邮箱验证码真实投递

- 关联 Proposal: `openspec/changes/2026-10-03-password-session-and-mail/proposal.md`
- 创建日期: 2026-10-03

## 1. 架构设计

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | **零改动**（路由 / 入参 / 出参全不变） | 路由 + @Valid + 调 Service |
| Service | `UserInfoServiceImpl` 三处：改密后吊销会话、`sendEmailCode` 改调 `MailService`；新增 `MailService` / `MailServiceImpl` | 业务规则 + 事务边界；**不感知 HttpServletRequest** |
| AOP | `GlobalOperationAspect#checkRateLimit` 降级为 IP 限流 | 横切关注点 |
| Mapper / SQL | **零改动** | — |
| Entity | **零改动** | — |
| 配置 | `pom.xml` + 三段 properties + `.env.example` | 见 §7 |

### 调用链

```
改密 / 找回密码
  Controller → UserInfoServiceImpl#updatePassword / #resetPasswordByEmail
                  ├─ 校验旧密码 / 校验验证码（既有）
                  ├─ userInfoMapper.updateByUserId（既有）
                  ├─ operationLogService.recordLog（既有）
                  └─ ★ NEW：会话失效
                       ├─ redisComponet.cleanUserTokenByUserId(userId)
                       │    → 删 token:{t} 全量 + token:userid + token:userid:list
                       └─ userInfoService.forceOffLine(userId)  ← 复用既有方法，推帧 7

验证码投递
  Controller → UserInfoServiceImpl#sendEmailCode
                  ├─ 邮箱存在性校验 / 60s 防重发（既有）
                  ├─ emailVerifyCodeMapper.insert（既有）
                  └─ ★ NEW：mailService.sendVerifyCode(email, code, purpose)

未登录端点限流
  GlobalOperationAspect#interceptorDo → checkRateLimit()
                  ├─ token != null → rate_limit:{token}     （既有，登录态）
                  └─ token == null → ★ NEW rate_limit:ip:{ip} （未登录态）
```

## 2. 接口设计

**本变更零接口契约变更。** 四个端点的路由、入参、`Result<T>` 出参与 HTTP 状态码全部不变。

| 端点 | Method | 入参 | 出参 | 变更 |
|------|--------|------|------|------|
| `/api/account/updatePassword` | POST | 不变 | `Result<Void>` | 行为变更：成功后全会话失效 |
| `/api/account/sendEmailCode` | POST | 不变 | `Result<Void>` | 行为变更：真实发信；未配置 SMTP → `CODE_1002` |
| `/api/account/resetPassword` | POST | 不变 | `Result<Void>` | 行为变更：成功后全会话失效 |

### 错误码

**不新增错误码**，复用现有分段（根 `AGENTS.md` §3.1）：

| 场景 | 码 | 理由 |
|------|----|------|
| 邮件服务未配置 | `CODE_1002`（1000-1999 系统错误） | 属部署配置缺失，非用户输入错误 |
| 邮件投递失败（SMTP 拒收/超时） | `CODE_1002` | 同上 |
| 验证码错误 / 过期 | 既有 `BusinessException("验证码错误")` | 不动 |
| 会话已失效（后续请求） | `CODE_2001` | 既有，前端已处理为 `reLogin` |

> 刻意**不**用 `CODE_1001`（参数非法）表达「未配 SMTP」——那是运维问题不是用户参数问题，语义错误会误导前端。

## 3. 数据模型

**本变更零表结构变更。** 不新增 `email_verify_code.try_count` 列（已确认留后续批次），因此**无需 migration-013，无需改 `easychat.sql`**。

> 登记后续：`email_verify_code` 补 `try_count INT NOT NULL DEFAULT 0` 做验证码失败次数限制（属独立 L4，需 migration-013）。

## 4. 安全设计

- **鉴权**：`sendEmailCode` / `resetPassword` 保持 `checkLogin=false`（未登录端点）；`updatePassword` 保持 `@GlobalInterceptor`（需登录）。
- **数据权限**：`resetPasswordByEmail` 凭「邮箱 + 验证码」双因子，不依赖登录态，与既有设计一致（**该弱面由 C2 封堵：验证码不再进日志**）。
- **输入校验**：不变。`newPassword` 由 `@Pattern(regexp = Constants.REGEX_PASSWORD)` 校验；`MailService` 内部对 `email` 做非空与格式守卫，`code` 做纯数字校验，防止注入邮件头（**邮件正文与主题均由服务端模板拼装，绝不反射用户输入的 `email` 进主题**）。
- **SQL 注入防护**：零新增 SQL。
- **会话失效时机**：**在 Service 内、事务提交后立即执行**（本变更不引入新的 `@Transactional`，与既有 `updatePassword` 一致保持原样，避免扩大事务边界）。若后续发现改密需要事务包裹，须连带评估「事务回滚后 Token 已被清」的补偿。

## 5. ADR

### ADR-001: 会话失效在 Service 内同步执行，不走 afterCommit

- 状态: 已接受
- 上下文: 改密与吊销 Token 若放同一事务，事务回滚会导致「密码没改但 Token 被清」；放 afterCommit 又需要引入事务同步机制。
- 决策: **保持现有非事务写法**，改密成功即清 Token。理由：改密失败（密码错 / 新旧相同）在清 Token **之前**就抛异常，正常路径不存在「清了又回滚」的窗口。
- 后果: 正面——实现最简、无新增事务边界。负面——若未来在 `updatePassword` 中途插入会抛异常的操作，需重新评估。

### ADR-002: 邮件未配置时 fail-closed，而非降级打日志

- 状态: 已接受
- 上下文: 现状是「未配置邮件 → 打日志」，这在开发期方便、在生产期是**凭据泄露通道**（日志读权限 = 任意账号改密权限）。
- 决策: 新增 `MailService`，未配置 `spring.mail.host` 时抛 `CODE_1002` **拒绝发送**；日志中**永不出现验证码明文**。
- 后果: 正面——消除泄露通道，行为可预期。负面——本地开发若想跑通找回密码，需在 `application-dev.properties` 配一个真实 SMTP（否则该功能显式不可用，这是**有意的诚实降级**）。

### ADR-003: 限流键从「token 优先」改为「token 优先、缺失时按 IP」

- 状态: 已接受
- 上下文: 登录态用户按 token 限流是正确粒度；未登录态无 token，若强行共用一个 `rate_limit:` 空前缀键会导致**全体匿名用户共享 60 次/分钟**的全局配额（等于把限流改成 DoS 放大器）。
- 决策: `token != null` → `rate_limit:{token}`；`token == null` → `rate_limit:ip:{X-Forwarded-For ?? getRemoteAddr()}`。两者独立计数、独立 60 秒窗口。
- 后果: 正面——未登录端点真正受限。负面——**NAT 后所有用户共享同一 IP**，同一出口 IP 下 60 次/分钟后登录接口会被限流。缓解：阈值 60/min 对登录场景足够宽松；若后续被 NAT 场景误伤，再按端点分级（本次不做，避免范围扩散）。

### ADR-004: 复用既有 `forceOffLine` 而非新写推帧逻辑

- 状态: 已接受
- 上下文: `UserInfoServiceImpl#forceOffLine` 已实现「推 `FORCE_OFF_LINE(7)` 帧」，被 `login`（单端模式）与管理端 `forceOffLine` 端点复用。
- 决策: 改密 / 找回密码成功后直接调它，**不新写推帧代码**。
- 后果: 正面——零新增 WS 逻辑，不触碰 WS 协议（L4 面最小化）。负面：无。

### ADR-005: 不引入验证码失败次数限制（范围裁剪）

- 状态: 已接受
- 上下文: `email_verify_code` 无 `try_count` 列，6 位码 10 分钟有效可暴力。
- 决策: **本批不做**，登记为后续独立 L4（需加列 + migration-013 + 同步 `easychat.sql` + Mapper XML）。
- 后果: 正面——本批不碰数据库结构，风险面收窄到「无 DDL」。负面——暴力风险仍在，但已由 C3（IP 限流 60/min）+ 60 秒防重发 + 10 分钟有效期把可行尝试压到约 600 次/10 分钟/单 IP，属可接受残余风险。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| **改密踢出全部端 → 用户以为「被踢」而困惑** | 高 | 中 | 复用 `FORCE_OFF_LINE` 帧，前端已有「登录超时 → `reLogin`」处理，用户体验为「改完密需重新登录」，与微信一致。QA 须显式验证该路径不报错 |
| **NAT 出口 IP 共享导致登录被限流** | 中 | 中 | ADR-003 已记。阈值 60/min；若实测误伤，按端点分级调阈值（不改本批范围） |
| **SMTP 未配置 → 本地开发「找回密码」不可用** | 高 | 低 | fail-closed 是有意的（ADR-002）。`application-dev.properties` 提供真实 SMTP 配置位，README 补说明 |
| **新增生产依赖引入 CVE / 冲突** | 低 | 高 | `spring-boot-starter-mail` 由 Spring Boot parent `2.6.1` 管理版本，不手写版本号；`mvn package -DskipTests` 必须 0 error |
| **`cleanUserTokenByUserId` 从未被调用过，行为未经验证** | 中 | 高 | TDD：先写失败测试断言「改密后 `cleanUserTokenByUserId` 被调用」，再实现；活体冒烟验证旧 token 真返回 `2001` |
| 邮件主题反射用户输入导致头注入 | 低 | 中 | ADR + `MailService` 内部：主题用固定文案，邮箱只出现在正文；`email` 做格式守卫 |

## 7. 依赖与前提

### 新增生产依赖（§8 L4）

| 依赖 | 版本 | 来源 |
|------|------|------|
| `spring-boot-starter-mail` | 由 parent `2.6.1` 管理（**不手写版本号**） | Spring Boot 官方 |

### 配置落点（全部走 `${ENV:}` 占位，零裸值）

| 文件 | 键 | 说明 |
|------|-----|------|
| `application-dev.properties` | `spring.mail.host` / `username` / `password` / `port` | 本机联调 SMTP；**不入库真实凭据**，用占位或留空（留空即 fail-closed） |
| `application-prod.properties` | 同上，取 `${SPRING_MAIL_*}` | 生产模板，无默认值 |
| `.env.example` | `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` / `_FROM` | 仅占位值 |

> `verify_no_hardcoded_secret.mjs` 须扩充断言：`spring.mail.password` 不得出现裸值；prod profile 的 mail 键须为占位符。

### 前提与后续

- **前置**：无。未与其他进行中 Change 冲突（`openspec/changes/` 此前为空）。
- **后续批次（本批明确不做，须登记）**：
  1. `email_verify_code` 补 `try_count` 做验证码失败次数限制（L4，需 migration-013）。
  2. `operation_log.ip_address` 恒为 `null`（6 处调用全传 `null`），需 AOP 取 `RequestContextHolder`。
  3. `@所有人` 权限下沉服务端（`saveMessage` 校验发送者 `role`）。
  4. `verify_schema_drift.mjs` 连库版门禁（独立 Change）。