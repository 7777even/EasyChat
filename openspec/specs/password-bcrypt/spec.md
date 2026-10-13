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

#### Scenario: 验证码端点按目标邮箱限流（2026-10-12，遗留 #1 前半）

- **WHEN** 请求携带 `rateLimitByParam = "email"`
- **THEN** 限流键为 `rate_limit:email:{邮箱 trim + 小写}`
- **AND** 取不到该参数时**退回 IP 维度**，而非退化为「全体匿名用户共用一个键」（那等于把限流变成 DoS 放大器）
- **AND** `login` / `register` **不**改维度（防的是密码暴力而非验证码暴力，且已有图形验证码把关）

---

### Requirement: 邮箱验证码的猜测次数有上界（2026-10-13，遗留 #1 根治）

限流管的是**请求频率**，猜测次数是**另一个维度**——低频慢速的暴力破解完全躲得过频率限流。
故猜错次数必须有独立上界，且该上界**跨验证码累计**。

**规模**（实测常量）：端点限流 60 次/分 × 验证码寿命 10 分钟 ⇒ 单邮箱 600 次尝试；
6 位码空间 10⁶ ⇒ 命中 **0.06%**。命中即等于账号被接管，且无任何告警。
下界设为 **5 次** ⇒ 命中 0.0005%（降 120 倍）。

#### Scenario: 猜错后计数累加

- **WHEN** 提交的码**查不到未使用的有效码**（即猜错）
- **THEN** 该邮箱失败计数 **+1**，键为 `easychat:emailcode:fail:{邮箱 trim + 小写}`
- **AND** 窗口 600 秒，只在**首次**计数时设置

#### Scenario: 计数跨验证码累计

- **WHEN** 攻击者连续猜错 3 次，随后调用 `sendEmailCode` 让受害者收到新验证码
- **THEN** 失败计数**仍为 3**，**不**被重置
- **AND** 新码只有剩余的 2 次机会
- **AND** **理由**：重发验证码是**受害者无法阻止**的动作；若重发能重置额度，限流等于没加

#### Scenario: 超过阈值后拒绝，且先于数据库查询

- **WHEN** 失败计数达到 5
- **THEN** 返回 `CODE_1001` +「验证码错误次数过多，请 10 分钟后再试」
- **AND** **不**执行任何 `email_verify_code` 查询——攻击流量在 Redis 层被挡，不打到 MySQL
- **AND** **不**报出此前失败了几次（避免给攻击者精确的进度反馈）

#### Scenario: 成功验证后清零

- **WHEN** 用正确验证码完成重置
- **THEN** 清除该邮箱的失败计数（否则下次找回密码会被陈旧计数挡住）

#### Scenario: 窗口过后自动恢复

- **WHEN** 计数超过 600 秒未再变动
- **THEN** Redis 键自动过期，无需任何人工清理

#### Scenario: 计数键须做大小写归一

- **WHEN** 邮箱以不同大小写提交（如 `A@x.com` 与 `a@x.com`）
- **THEN** 二者**共用同一个**计数键（否则攻击者靠大小写变体即可翻倍额度）

### Requirement: 只有「猜错」才计入失败次数

次数限制若被用在与猜测无关的失败路径上，会把限流本身变成**拒绝服务**工具。

#### Scenario: 验证码已过期不计数

- **WHEN** 提交的码**存在**但已过期
- **THEN** 抛「验证码已过期」（既有文案）
- **AND** **不**累加失败计数
- **AND** **理由**：此时无可猜的目标。若计数，攻击者只需等码一过期就发 5 次垃圾，
  即可把受害者**永久锁死**——限流反而成了更严重的 DoS

#### Scenario: 用户不存在不计数

- **WHEN** 码有效但 `selectByEmail` 返回空
- **THEN** 抛 `CODE_2101`（既有行为）
- **AND** **不**累加失败计数（该分支实际只在「发码后账号被删」时出现，计它会把罕见状态当攻击信号）

#### Scenario: 既有错误文案一个都不改

- **WHEN** 未超限
- **THEN** 「验证码错误」/「验证码已过期」/`CODE_2101` 三条文案**保持原样**
- **AND** 本变更**不新增**任何用户枚举面

### Requirement: 失败次数计数的常量与降级行为

#### Scenario: 阈值与窗口是具名常量且被值锚定

- **WHEN** 审视 `Constants`
- **THEN** `EMAIL_CODE_FAIL_MAX`（5）与 `EMAIL_CODE_FAIL_WINDOW_SECONDS`（600）为具名常量，不出现魔法数字
- **AND** 窗口与验证码寿命**一致**（短于则计数在码还有效时就过期；长于则受害者被锁超过码本来的可用时长）
- **AND** 单测以**字面量**把 5 与 600 钉死——阈值调大是**静默的安全降级**，
  须迫使改动者重新评估安全口径而非无声改掉

#### Scenario: Redis 不可用时 fail-open

- **WHEN** `redisUtils.get` 返回 `null`（键不存在或连接异常）
- **THEN** 视为「未超限」，放行到原有校验流程
- **AND** **理由**：与端点限流 `applyRateLimit` 同一故障域、同一取舍——计数不可用时
  应退化为「无失败计数限制」，而非把**所有**用户的密码重置全部拒绝

### Requirement: 邮箱验证码失败计数的已知边界

#### Scenario: 边界一：可被用于锁死受害者（**接受，不假装没有**）

- **WHEN** 某人知道受害者的邮箱
- **THEN** 可用 5 次错误尝试把该邮箱锁 10 分钟
- **AND** 缓解：窗口与验证码寿命一致，被锁时长不超过码本来的可用时长；
  受害者仍能**正常收到**新验证码，只是暂时用不了；窗口过后自动恢复
- **AND** **未**采用按 IP 叠加维度——共享办公网会把正常用户一并锁死，代价大于收益
- **AND** 这是**所有**基于次数的验证码限流的固有代价（GitHub / Google 同）

#### Scenario: 边界二：Redis 丢失即计数清零

- **WHEN** Redis 数据丢失或重启
- **THEN** 失败计数归零，退化为无失败次数限制
- **AND** 与端点限流同一故障域，故不产生新的不一致来源

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

---

### Requirement: 认证入口字段级校验真正生效

login / register / resetPassword 三个端点必须在**进入 Service 之前**校验请求字段，
非法输入返回 `code=1001` 与**字段级**中文提示。

> 2026-10-12 补记（`openspec/archive/2026-10-12-account-dto-validation`）：
> 本仓库此前 `@Valid` **全仓使用 0 次**，而 `UserLoginDTO` / `UserRegisterDTO` 上共 11 条
> `@NotEmpty` / `@Email` 约束——参数以 `@ModelAttribute` 绑定时必须显式标注 `@Valid` 才触发，
> 故那 11 条约束**一条都没生效**。实测：空邮箱 / 非法邮箱 / 空昵称均 `HTTP 200` 直达 Service 层。

#### Scenario: 登录邮箱格式非法

- **WHEN** `POST /account/login`，`email=not-an-email`，其余字段合法
- **THEN** 返回 `HTTP 400` + `code=1001`
- **AND** `message` 为 `email: 邮箱格式不正确`
- **AND** **不调用** `userInfoService.login`

#### Scenario: 登录邮箱为空

- **WHEN** `POST /account/login`，`email=`（空）
- **THEN** 返回 `code=1001` + `email: 邮箱不能为空`
- **AND** 不调用 Service

#### Scenario: 注册昵称为空

- **WHEN** `POST /account/register`，`nickName=`（空），其余合法
- **THEN** 返回 `code=1001` + `nickName: 昵称不能为空`
- **AND** 不调用 Service

#### Scenario: 重置密码新密码强度不足

- **WHEN** `POST /account/resetPassword`，`newPassword=123`（不含字母、不足 8 位）
- **THEN** 返回 `code=1001` + `newPassword: 密码格式不正确`
- **AND** 不调用 `userInfoService.resetPasswordByEmail`

#### Scenario: 多个字段同时非法

- **WHEN** `POST /account/register`，`email` 与 `nickName` 均为空
- **THEN** 返回 `code=1001`，`message` 含各违规字段的提示（以 `"; "` 连接）
- **AND** 不调用 Service

> **文案格式说明**：`字段名: 提示` 是 `AGlobalExceptionHandlerController#handleBindException`
> **既有**的实现（`field + ": " + message`），本次**刻意未改**——改格式会影响所有走
> `BindException` 的端点（各类 Query 对象）。字段名前缀会暴露给终端用户，
> 属**已知的 UX 特性而非缺陷**；若要更友好的文案须单独立项。

---

### Requirement: 认证入口校验口径统一

三个端点一律「DTO + `@Valid`」，不再存在「DTO 靠注解、散参数靠 `@NotNull`」的双轨。

#### Scenario: resetPassword 接收 DTO

- **WHEN** 检查 `AccountController#resetPassword` 的签名
- **THEN** 形参为**单个** `UserResetPasswordDTO`（`email` / `code` / `newPassword`），
      不再是三个 `String`
- **AND** 该参数标注 `@Valid`

#### Scenario: 请求参数名保持不变（前端零改动）

- **WHEN** 以表单参数 `email` / `code` / `newPassword` 调用 `/account/resetPassword`
- **THEN** 参数正确绑定到 DTO 对应字段，校验通过时正常调用 Service
- **AND** **前端无需任何改动**（`check-api-contract.mjs` 实测 exit 0）

#### Scenario: 类级 @Validated 仍为现存机制

- **WHEN** `AccountController#sendEmailCode` 仍用 `@NotEmpty String email`
- **THEN** 类级 `@Validated` 仍存在并对其生效
- **AND** 该方法**本轮刻意未改**（本次只收敛 `resetPassword`）

> ⚠ **已知测试盲区**：`MockMvcBuilders.standaloneSetup` **不注册**
> `MethodValidationPostProcessor`，故「类级 `@Validated` + 方法参数注解」这条路
> 在单测环境下**根本不触发**，端到端断言必然恒红——那是**环境缺失**而非缺陷。
> `AccountControllerMockMvcTest#classLevelValidatedIntact` 因此改用**结构断言**
> （反射检查注解仍在），并按 §2.1 第 3 条登记为不可单测检测项（`docs/system-facts.md` §14 #32）。
