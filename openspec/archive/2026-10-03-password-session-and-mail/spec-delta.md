# Spec Delta — 密码变更后会话失效 + 邮箱验证码真实投递

- 关联 Tasks: `openspec/changes/2026-10-03-password-session-and-mail/tasks.md`
- 创建日期: 2026-10-03

> 格式对齐 `openspec/specs/password-bcrypt/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 邮箱验证码真实投递（对齐 Capability C2）

系统通过 SMTP 将邮箱验证码投递到用户邮箱；未配置邮件服务时显式失败，任何情况下不把验证码写入日志。

#### Scenario: 验证码投递成功

- **WHEN** 用户请求发送邮箱验证码（注册或找回密码）且邮件服务已配置
- **THEN** 系统通过 SMTP 向该邮箱投递含验证码与有效期的邮件
- **AND** 邮件主题为固定文案，**不包含用户输入的邮箱地址**（防邮件头注入）
- **AND** 验证码记录已落 `email_verify_code`

#### Scenario: 邮件服务未配置

- **WHEN** `spring.mail.host` 未配置
- **THEN** 接口返回 `CODE_1002`
- **AND** **不发送邮件**
- **AND** **日志中不出现验证码明文**

#### Scenario: SMTP 投递失败

- **WHEN** 邮件服务已配置但投递过程抛异常（拒收 / 超时 / 认证失败）
- **THEN** 接口返回 `CODE_1002`
- **AND** 异常信息中不包含验证码明文

---

### Requirement: 未登录端点按 IP 限流（对齐 Capability C3）

未携带 token 的请求按客户端 IP 维度限流，使登录、注册、发送验证码、重置密码四个端点的限流真实生效。

#### Scenario: 未登录端点超限

- **WHEN** 同一客户端 IP 在 60 秒内对 `/account/login`、`/account/register`、`/account/sendEmailCode`、`/account/resetPassword` 发起超过 60 次请求
- **THEN** 返回 `CODE_1001`，提示「请求过于频繁，请稍后再试」
- **AND** 计数键为该 IP 专属，**不影响其他客户端**

#### Scenario: 登录态端点限流键不回归

- **WHEN** 请求携带有效 token
- **THEN** 限流键仍为 `rate_limit:{token}`（按 token 维度），**行为不变**

---

## MODIFIED Requirements

### Requirement: 密码变更后的会话失效

修改密码或通过邮箱验证码重置密码成功后，该用户全部端的会话立即失效，各端被强制下线。

#### Scenario: 修改密码后会话失效

- **WHEN** 用户凭正确的旧密码完成改密
- **THEN** 系统清除该用户在 Redis 中的**全部端** Token
- **AND** 向该用户各端推送 `FORCE_OFF_LINE(7)` 帧强制下线
- **AND** 用户后续携带旧 Token 的请求返回 `CODE_2001`，客户端需重新登录

#### Scenario: 改密失败不吊销会话

- **WHEN** 改密因旧密码错误或新旧密码相同而失败
- **THEN** 抛出 `CODE_2103` 或业务异常
- **AND** **不吊销任何 Token**（清 Token 发生在密码写入成功之后）

**变更前（引用原 spec）**：`password-bcrypt` spec 仅规定密码以 BCrypt 存储、服务端承担哈希、旧 MD5 账号登录时自动升级。对改密后的会话处理**无任何规定**；实现上既不清 Token 也不推帧（`UserInfoServiceImpl#updatePassword` 仅写库 + 记 `operation_log`）。

**变更后**：改密成功后会话被全量吊销并强制下线。

---

### Requirement: 通过邮箱验证码重置密码

用户凭邮箱验证码重置密码；成功后该用户全部端会话立即失效。

#### Scenario: 重置密码后会话失效

- **WHEN** 用户凭正确的邮箱验证码完成密码重置
- **THEN** 系统清除该用户在 Redis 中的**全部端** Token
- **AND** 向该用户各端推送 `FORCE_OFF_LINE(7)` 帧强制下线

#### Scenario: 验证码校验失败不吊销会话

- **WHEN** 重置因验证码错误 / 已过期 / 邮箱未注册而失败
- **THEN** 抛出对应业务异常
- **AND** **不吊销任何 Token**

**变更前（引用原 spec）**：`password-bcrypt` spec 规定 BCrypt 存储与 MD5 双验证自动升级；邮箱重置路径存在于 `AccountController#resetPassword`，但 spec 未定义其会话后果。实现上重置成功后仅写库 + 标记验证码已用，**旧 Token 继续有效 2 天**。

**变更后**：重置成功后全会话失效；三条校验失败路径明确不触发吊销。

---

## REMOVED Requirements

### Requirement: 找回密码验证码写日志

系统把未配置邮件服务时的邮箱验证码以明文写入应用日志，便于本地联调。

**移除原因**：应用日志（容器 stdout、日志聚合、CI 归档）通常有远超普通用户的读权限，等价于「谁能看到日志谁就能重置任意账号（含 admin）的密码」，是真实的凭据泄露通道。同时前端已向用户暴露完整找回密码流程，把验证码写日志导致该功能对真实用户永远不可用。

**替代方案**：新增 `MailService` 通过 SMTP 真实投递；未配置邮件服务时 **fail-closed** 返回 `CODE_1002`（`design.md` ADR-002）。本地开发如需联调，在 `application-dev.properties` 配真实 SMTP。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 密码变更后会话失效 | MODIFIED: 密码变更后的会话失效 | T3.1, T3.2 |
| C1 密码变更后会话失效 | MODIFIED: 通过邮箱验证码重置密码 | T3.1, T3.3 |
| C2 邮箱验证码真实投递 | ADDED: 邮箱验证码真实投递 | T4.1, T4.3, T4.4, T4.5 |
| C2 邮箱验证码真实投递 | REMOVED: 找回密码验证码写日志 | T4.4 |
| C3 未登录端点按 IP 限流 | ADDED: 未登录端点按 IP 限流 | T1.1, T2.1 |
| （安全网）门禁先红后绿 | — | T0.1, T0.2, T0.3 |
| （安全网）鉴权面单测 | — | T1.1, T1.2 |