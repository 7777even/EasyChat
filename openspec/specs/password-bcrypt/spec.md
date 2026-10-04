# Spec — MD5 → BCrypt 密码加密升级

## ADDED Requirements

### Requirement: BCrypt 密码加密

新注册用户使用 BCrypt 加密密码。

#### Scenario: 注册使用 BCrypt

- **WHEN** 用户注册
- **THEN** 使用 BCrypt 加密密码
- **AND** 密码哈希长度 60 字符
- **AND** 哈希前缀为校验器当前默认生成的 BCrypt 变体

> **前缀不是契约**：`$2a$` 只是 `spring-security-crypto 5.6.0` 的当前默认输出，
> 属**实现细节**。契约是「**长度为 60 的 BCrypt modular-crypt 格式**」。
> 本条曾把「`$2a$` 开头」写进规范，直接诱发了下游的单一前缀判定缺陷，
> 见下方「BCrypt 格式判定与校验口径一致」。

---

### Requirement: BCrypt 格式判定与校验口径一致

密码哈希的**格式判定**（`isBCrypt`）不得比**校验**（`matches`）更严：
凡校验器能够接受的 BCrypt 哈希，格式判定必须认定其为 BCrypt。

#### Scenario: 校验器接受的变体不被误判为非 BCrypt

- **WHEN** 待判定哈希的变体前缀属于 BCrypt 已知变体集合（`$2a$` / `$2b$` / `$2x$` / `$2y$`）
- **THEN** `isBCrypt` 返回 `true`
- **AND** 密码链路据此走 BCrypt 分支，**不得**落到 MD5 双验证分支

#### Scenario: 判定方向的安全边界

- **WHEN** `isBCrypt(p)` 返回 `true` 但校验器无法验证 `p`
- **THEN** 视为密码错误，登录/改密失败
- **AND** **不修改库中密码**（已加密的哈希不得被当作明文再次加密）

> 违反方向**不对称**，这是本 Requirement 存在的理由：
> 判严（漏放变体）→ 落 MD5 分支 → 登录后「自动升级」把哈希再加密一次 → **不可逆损坏**；
> 判松（多放非 BCrypt）→ 校验失败 → 返回密码错误、**不改数据**，安全。
> 故规则只能向「放宽」方向收紧。

#### Scenario: 不得漏放校验器已支持的变体

- **WHEN** 依赖升级后校验器新增支持某个 BCrypt 变体
- **THEN** 回归测试自动转红，提示补全放行集合
- **AND** 该断言由**校验器实测行为推导**得出，不依赖硬编码的版本清单

> 硬编码清单会让**测试与实现基于同一份过时知识互相印证而假绿**——
> 这是 AGENTS §2.1 第 1 条「门禁须先有判别力」的反例。
> 实测 `spring-security-crypto 5.6.0` 接受集为 `[aby]`（**`$2x$` 被拒**），
> 与「BCrypt 有四个变体」的通行印象不符，故更不能照抄。

#### Scenario: 判定不得放宽到非 BCrypt 数据

- **WHEN** 待判定值为 MD5 哈希（32 位十六进制）、`$1$` 系列、
      `$2$`（无 minor 版本号）或 `$20$`~`$29$`
- **THEN** `isBCrypt` 返回 `false`
- **AND** 空值、空串亦返回 `false`
- **AND** 前缀判定采用白名单枚举，**不得**用 `startsWith("$2")` 之类的通配

#### Scenario: 截断串不得被误判为 BCrypt

- **WHEN** 待判定值前缀形似 BCrypt 但**长度不等于 60**
      （如 `"$2a$"`、`"$2a$10$tooshort"`）
- **THEN** `isBCrypt` 返回 `false`
- **AND** 该判定方向是安全的：调用方落到 MD5 分支并校验失败，**不修改库中密码**

> BCrypt modular-crypt 格式长度恒为 60，故长度校验属**格式识别的必要组成部分**，
> 而非启发式阈值。仅做前缀匹配的实现会把上述截断串误判为 BCrypt。

---

### Requirement: 双验证登录

服务端对 BCrypt 与 MD5 两种存量格式均接受明文登录，并在 MD5 路径成功后自动升级。

#### Scenario: MD5 用户登录自动升级

- **WHEN** 用户使用 MD5 密码登录成功
- **THEN** 自动将密码升级为 BCrypt 加密
- **AND** 后续登录使用 BCrypt 验证

#### Scenario: 已是 BCrypt 的密码不得被二次加密

- **WHEN** 用户以任意 BCrypt 变体的密码登录成功
- **THEN** **不触发**「MD5 → BCrypt 自动升级」分支
- **AND** 库中密码保持原哈希不变

> 这是本能力中**唯一涉及不可逆数据损坏**的路径。
> 自动升级分支以「判定为非 BCrypt」为条件，故判定口径必须与校验器对齐
> （见「BCrypt 格式判定与校验口径一致」）。

---

### Requirement: 密码传递口径统一为明文

注册、登录、修改密码、找回密码四条链路，客户端一律发送明文密码，由服务端统一承担加密存储与校验。

#### Scenario: 注册后可立即登录

- **WHEN** 用户用明文密码 P 注册成功（服务端存 `bcrypt(P)`）
- **THEN** 用户用同一明文 P 登录成功
- **AND** 登录成功后密码仍为 BCrypt 哈希（60 字符），未被改写为其他形式

#### Scenario: 客户端不得自行哈希

- **WHEN** 任一密码链路构造请求
- **THEN** `password` 字段为明文，不做 MD5 等客户端哈希
- **AND** 发送 `md5(P)` 的请求视为非法输入，登录返回密码错误（而非系统错误）

#### Scenario: 传输安全边界

- **WHEN** 客户端以明文发送密码
- **THEN** 系统不做传输层加密
- **AND** 部署方需自行提供 HTTPS 以获得密码传输保护（独立变更，不在本能力范围）

---

### Requirement: 存量库结构与基线对齐

密码升级所需的 DDL 必须有可执行迁移脚本，使存量库结构不落后于 `easychat.sql` 基线。

#### Scenario: password 列宽足够承载 BCrypt

- **WHEN** 在存量库（`user_info.password` 为 `varchar(32)`）上执行迁移
- **THEN** 该列被放宽为 `varchar(60)`
- **AND** 存量 MD5 账号登录成功时，自动升级写入 BCrypt 不再触发 `Data too long` 截断异常
- **AND** 登录接口不返回 500

#### Scenario: 基线变更必须配迁移

- **WHEN** 任一变更修改了表结构或列宽
- **THEN** 同批交付 `easychat-migration-<NNN>-*.sql` 脚本
- **AND** 基线 `easychat.sql` 与迁移脚本内容一致，不允许只改基线

#### Scenario: 迁移脚本幂等

- **WHEN** 同一迁移脚本被重复执行
- **THEN** 不报错、不破坏既有数据（建表使用 `IF NOT EXISTS`，列宽变更向后兼容）

---

### Requirement: 密码链路回归测试覆盖

BCrypt 登录 / 注册 / 改密路径必须有自动化回归用例覆盖。

#### Scenario: BCrypt 路径纳入回归

- **WHEN** 运行后端单元测试
- **THEN** 覆盖以下用例且全部通过：BCrypt 账号明文登录成功、注册后可登录、MD5 老账号登录并自动升级、BCrypt 账号拒绝 MD5 摘要登录
- **AND** 存在机控守卫断言「客户端不得自行哈希」与「冒烟脚本发送明文」

---

### Requirement: 修改密码使用 BCrypt

用户修改密码时使用 BCrypt 加密。

#### Scenario: 修改密码

- **WHEN** 用户修改密码
- **THEN** 使用 BCrypt 加密新密码
- **AND** 旧密码验证支持 MD5/BCrypt 双验证

---

### Requirement: 密码变更后会话失效

修改密码或通过邮箱验证码重置密码成功后，该用户全部端的会话立即失效，各端被强制下线。语义为「改密即视为凭据可能泄露」。

#### Scenario: 修改密码后全部端会话失效

- **WHEN** 用户凭正确的旧密码完成改密
- **THEN** 系统清除该用户在 Redis 中的**全部端** Token（`easychat:ws:token:{token}`、`:token:userid`、`:token:userid:list:{userId}` 三处）
- **AND** 向该用户各端推送 `FORCE_OFF_LINE(7)` 帧强制下线
- **AND** 用户后续携带旧 Token 的请求返回 `CODE_2001`，客户端需重新登录
- **AND** 发起改密的当前会话同样失效（与微信一致）

#### Scenario: 改密失败不吊销会话

- **WHEN** 改密因旧密码错误（`CODE_2103`）或新旧密码相同而失败
- **THEN** **不吊销任何 Token**（会话失效发生在密码写入成功之后）

#### Scenario: 会话失效不进事务

- **WHEN** 改密流程执行
- **THEN** 会话失效在 Service 内同步执行，**不包裹在事务中**
- **AND** 因前置校验（旧密码 / 新旧相同）失败时在写库前即抛异常，正常路径不存在「密码未改但 Token 已被清」的窗口

---

### Requirement: 通过邮箱验证码重置密码

用户凭邮箱验证码重置密码；成功后该用户全部端会话立即失效。

#### Scenario: 重置密码后全部端会话失效

- **WHEN** 用户凭正确的邮箱验证码完成密码重置
- **THEN** 系统清除该用户在 Redis 中的**全部端** Token
- **AND** 向该用户各端推送 `FORCE_OFF_LINE(7)` 帧

#### Scenario: 验证码校验失败不吊销会话

- **WHEN** 重置因验证码错误、验证码过期或邮箱未注册而失败
- **THEN** 抛出对应业务异常
- **AND** **不吊销任何 Token**（防止任何人输错验证码即可将受害者踢下线，构成拒绝服务）

---

### Requirement: 邮箱验证码真实投递

系统通过 SMTP 将邮箱验证码投递到用户邮箱；未配置邮件服务时**显式失败**，任何情况下不把验证码写入日志。

#### Scenario: 验证码投递成功

- **WHEN** 用户请求发送邮箱验证码且邮件服务已配置
- **THEN** 系统通过 SMTP 向该邮箱投递含验证码与有效期的邮件
- **AND** **落库的验证码与发出去的验证码是同一个值**（否则用户收到邮件却输不进）
- **AND** 邮件主题为固定文案，**不包含用户输入的邮箱地址**

#### Scenario: 邮件服务未配置时 fail-closed

- **WHEN** `spring.mail.host` 未配置（dev 与 prod 均默认为空）
- **THEN** 接口返回 `CODE_1002`
- **AND** **不发送邮件**
- **AND** **日志中不出现验证码明文**——应用日志的读权限通常远宽于普通用户，写入日志等价于授予其重置任意账号（含管理员）密码的权限

#### Scenario: 投递失败包装且不泄露

- **WHEN** SMTP 投递抛异常（拒收 / 超时 / 认证失败）
- **THEN** 接口返回 `CODE_1002`
- **AND** 异常消息与日志均**不含验证码明文**（异常会经全局异常处理器回给前端并落日志）

#### Scenario: 验证码入参守卫

- **WHEN** 邮箱格式非法、或验证码不是 4–8 位纯数字
- **THEN** 返回 `CODE_1001`
- **AND** 纯数字约束同时阻断邮件头注入（`\r\n Bcc: …`）与模板注入

---

### Requirement: 未登录端点按 IP 限流

未携带 token 的请求按客户端 IP 维度限流，使登录、注册、发送验证码、重置密码四个端点的限流真实生效。

#### Scenario: 未登录端点超限

- **WHEN** 同一客户端 IP 在 60 秒内对 `/account/login`、`/account/register`、`/account/sendEmailCode`、`/account/resetPassword` 发起超过 60 次请求
- **THEN** 返回 `CODE_1001`，提示「请求过于频繁，请稍后再试」
- **AND** 计数键为该 IP 专属，不影响其他客户端

#### Scenario: 登录态请求仍按 token 限流

- **WHEN** 请求携带 token
- **THEN** 限流键为 `rate_limit:{token}`，既有语义不变

#### Scenario: 客户端 IP 取值

- **WHEN** 解析客户端 IP
- **THEN** 优先取 `X-Forwarded-For` 的**首段**（最初发起请求的客户端），缺失时回退 `HttpServletRequest#getRemoteAddr()`
- **AND** 取不到时使用固定占位值（仍参与计数，不得退化为放行）

#### Scenario: 限流键前缀不得变更

- **WHEN** 修改限流键前缀
- **THEN** 必须保持 `rate_limit:` 前缀——改名会使既有 Redis 计数键失效，等同于静默清零所有用户的限流额度

---

## MODIFIED Requirements

### Requirement: 用户密码存储

用户密码使用 BCrypt 加密存储。

#### Scenario: 密码存储

- **WHEN** 用户注册/修改密码
- **THEN** 使用 BCrypt 加密存储

**变更前**: 使用 MD5 加密存储

**变更后**: 使用 BCrypt 加密存储

---

## REMOVED Requirements

### Requirement: 找回密码验证码写日志

系统把未配置邮件服务时的邮箱验证码以明文写入应用日志，便于本地联调。

**移除原因**: 应用日志（容器 stdout、日志聚合平台、CI 构建归档）的读权限通常远宽于普通用户——「谁能看到日志谁就能重置任意账号（含管理员）的密码」，是真实的凭据泄露通道。同时前端已向用户暴露完整找回密码流程，把验证码只写日志意味着该功能对真实用户永远不可用。

**替代方案**: 新增 `MailService` 通过 SMTP 真实投递；未配置邮件服务时 fail-closed 返回 `CODE_1002`（见上方「邮箱验证码真实投递」）。本地开发如需联调，在 `application-dev.properties` 或仓库根 `.env` 配真实 SMTP。
