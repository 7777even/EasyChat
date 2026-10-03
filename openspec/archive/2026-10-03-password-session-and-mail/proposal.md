# Proposal — 密码变更后会话失效 + 邮箱验证码真实投递

- 创建日期: 2026-10-03
- 效率等级: **L4**

## Why

三处认证面缺陷构成一条可利用的账号接管链，且**全部无自动化测试覆盖**：

1. **改密 / 找回密码后旧 Token 不失效**。`UserInfoServiceImpl#updatePassword` 与 `#resetPasswordByEmail` 改完密码只写库 + 记 `operation_log`，**既不删 Redis token，也不推 `FORCE_OFF_LINE`**。`RedisComponet#cleanUserTokenByUserId` 已实现且能清理多端全部 token，**全仓零调用**。Token TTL 为 2 天（`REDIS_KEY_EXPIRES_DAY * 2`）→ 账号被盗后受害者改密码，攻击者照常在线。
2. **邮箱验证码只写日志**。全仓 `JavaMailSender` / `spring.mail` / `smtp` **零命中**，`UserInfoServiceImpl#sendEmailCode:459` 直接 `logger.info("…code={}", code)`。前端 `Login.vue` 却把完整找回密码流程暴露给用户 → 用户侧永远收不到码（功能不可用），同时**任何有日志读权限者可重置任意账号（含 admin）密码**。
3. **未登录端点限流形同虚设**。`GlobalOperationAspect#checkRateLimit` 第 85-87 行 `token == null` 即 `return`；`Request.js:170-174` 登录页 `localStorage.getItem('token')` 为 `null`，axios 丢弃 null header → `login` / `register` / `sendEmailCode` / `resetPassword` 四个 `checkLogin=false, checkRateLimit=true` 端点**限流从未生效**，唯一防护只剩图形验证码。

> 附带事实失真：`docs/system-facts.md` §12 记载「修改密码…成功后关闭 WS 强制重登」，**与代码不符**——该行为从未实现。

## What Changes

- 后端:
  - `UserInfoServiceImpl`：`updatePassword` / `resetPasswordByEmail` 成功后调用 `RedisComponet#cleanUserTokenByUserId` + 推 `FORCE_OFF_LINE` 帧。
  - 新增 `MailService`（接口 + `MailServiceImpl`）：基于 `spring-boot-starter-mail` 投递验证码邮件；**未配置 host 时 fail-closed**（抛业务异常，不再打日志）。
  - `UserInfoServiceImpl#sendEmailCode` 改为调 `MailService` 真实发送。
  - `GlobalOperationAspect#checkRateLimit`：`token == null` 时降级为按客户端 IP 限流，不再 `return`。
  - `pom.xml`：新增 `spring-boot-starter-mail`（生产依赖，属 §8 L4）。
  - `application-dev.properties` / `application-prod.properties` / `.env.example`：新增 `spring.mail.*` 占位。
- 前端: **无改动**。现有 `Login.vue` 找回密码流程与 `Result<T>` 契约本就正确。
- 数据库: **无表结构变更**（不新增 `try_count` 列，不改 `email_verify_code`）。

## Capabilities

- **C1**: 密码变更（改密 / 找回密码）成功后，该用户**全部端**的 Redis Token 立即失效，且各端收到 `FORCE_OFF_LINE` 帧被强制下线，需重新登录。
- **C2**: 邮箱验证码通过 SMTP **真实投递**给用户；未配置邮件服务时**显式失败**（返回业务错误码），**任何情况下不再把验证码写入日志**。
- **C3**: 未登录端点（`/account/login`、`/account/register`、`/account/sendEmailCode`、`/account/resetPassword`）的限流按**客户端 IP** 生效，不再因缺失 token 头而失效。

## Impact

- **对外接口**: 路由、入参、出参 `Result<T>` 结构**均不变**（契约零变更，`check-api-contract.mjs` 应保持 0 漂移）。行为变更两处：
  - `/account/resetPassword` 成功后客户端 token 立即失效 → 前端现有拦截器已把 `code=2001` 处理为 `reLogin`，无需改前端代码。
  - `/account/sendEmailCode` 在未配 SMTP 时由「成功」改为返回错误码（前端已有 `Message.error` 兜底）。
- **存量数据**: 无。不改表、不改存量行。
- **性能 / 安全**: 安全收益显著（堵住日志泄露与改密不踢人）。性能上邮件投递为同步阻塞调用，单次 SMTP 往返约 100–500ms，发生在「找回密码」低频路径；`sendEmailCode` 已有 60 秒防重发 + 图形验证码 + IP 限流三重约束，不会成为放大点。
- **回退方案**: `git revert` 单个提交即可。配置侧回退只需清空 `SPRING_MAIL_*` 环境变量（此时 `sendEmailCode` 回到 fail-closed 拒绝发送，比回退到「打日志」安全）。

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 <YYYY-MM-DD> 确认，允许进入 design 阶段。
>
> - [ ] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估

### 已获人工决策记录（2026-10-03）

| 决策点 | 选定方案 | 否决方案 |
|--------|----------|----------|
| 邮件投递 | 新增 `spring-boot-starter-mail`，真发邮件 | 不加依赖仅 fail-closed；两者分两批 |
| Token 吊销范围 | 该用户**全部端** Token + 推 `FORCE_OFF_LINE` | 仅吊销发起方当前 token；全部端但不推帧 |
| 验证码失败次数限制 | **不并入本批**（留后续批次） | — |
| `operation_log.ip_address` | **不并入本批**（留后续批次） | — |