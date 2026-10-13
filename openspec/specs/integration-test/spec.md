# Spec — 集成测试层

> 本 spec 记录**工程能力**（可验证性），不含产品功能。
> 来源：`openspec/archive/2026-10-13-backend-integration-test-layer/spec-delta.md`

## ADDED Requirements

### Requirement: 认证与限流守卫的行为可被自动化验证

系统的认证与限流守卫必须具备**行为级**自动化验证：仅断言「注解是否存在」不算覆盖，
因为守卫条件写反时结构断言照样通过。

#### Scenario: 非管理员访问管理端被拒

- **WHEN** 已登录的**非管理员**用户调用任一 `checkAdmin = true` 的端点
- **THEN** 响应 `code = 1003`（非管理员访问管理端）
- **AND** Service 方法体**从未被执行**（拦截发生在进入业务之前）

#### Scenario: 管理员访问同一端点必须放行

- **WHEN** 已登录的管理员调用同一端点
- **THEN** 响应 `code = 0` 且 Service 方法体被调用**恰好一次**
- **AND** （与上一条构成制衡）守卫不得被实现为「无条件拒绝」

#### Scenario: 会话缺失时判登录失效

- **WHEN** 请求携带的 token 在会话存储中查不到
- **THEN** 响应 `code = 2001`（TOKEN_EXPIRED），Service 未被执行

#### Scenario: 限流阈值边界

- **WHEN** 未登录端点的限流计数**超过**阈值（默认 60 次 / 60 秒）
- **THEN** 响应为限流错误，且携带限流专属文案
- **AND** 计数**正好等于**阈值时**放行**（判定为 `count > 阈值`，不是 `>=`）

#### Scenario: 限流按标注生效，而非全局生效

- **WHEN** 调用**未标注** `checkRateLimit` 的端点
- **THEN** 限流计数器**从未被调用**（而登录校验仍执行）
- **AND** 同一端点带合法 token 时不进入限流分支

#### Scenario: 限流文案不得与业务错误码混淆

- **WHEN** 需要判定「响应是否为限流所致」
- **THEN** 应依据**限流专属文案**而非错误码 —— 限流与部分业务校验**共用 `CODE_1001`**

---

### Requirement: 参数校验在真实 Web 上下文中触发

参数校验的两条路径必须在**真容器**（`@SpringBootTest` + `@AutoConfigureMockMvc`）中各自被验证；
`MockMvcBuilders.standaloneSetup` 不注册 `MethodValidationPostProcessor`，在单测层无法覆盖方法级校验。

#### Scenario: 对象绑定校验（@Valid）触发字段级文案

- **WHEN** 以对象绑定方式传入违反 Bean Validation 约束的 DTO（如邮箱格式非法）
- **THEN** 响应 `code = 1001`，`message` 形如 `<字段名>: <提示>`
- **AND** Service 方法体从未被执行

#### Scenario: 方法级校验（@Validated + 参数注解）触发

- **WHEN** 类上标注 `@Validated`、且方法参数带约束注解（如 `@NotEmpty String email`），
  调用时传入违反约束的值
- **THEN** 响应 `code = 1001`，且 Service 方法体**从未被执行**
- **AND** 删除类级 `@Validated` 后该约束**完全失效**（请求进入方法体并返回成功）
  —— 本条是遗留 #32 的行为层面闭环证据

#### Scenario: 两条路径互相独立

- **WHEN** 仅删除类级 `@Validated`
- **THEN** 对象绑定路径（`@Valid`）的校验**仍然生效**（两者互不依赖）

#### Scenario: 合法入参不得被误伤

- **WHEN** 入参满足全部约束
- **THEN** 请求**必须进入**方法体
- **AND** 其它守卫（如限流计数）**照常执行**

---

### Requirement: Spring 测试上下文可在无外部依赖下启动

集成测试上下文必须在**没有 MySQL / Redis / 邮件服务**的机器上可启动，
使行为验证在本机与 CI 上均可稳定复现。

#### Scenario: 无外部依赖时上下文可启动

- **WHEN** 在未提供 MySQL、Redis、SMTP 的环境运行集成测试
- **THEN** Spring 上下文成功加载，Flyway **不**执行迁移，数据源**不**建立真实连接

#### Scenario: 测试上下文不得污染活库

- **WHEN** 集成测试运行
- **THEN** 数据源 URL 经断言确认**不是**真实地址（该断言**只读配置、不建立连接**）
- **AND** 不向任何真实数据库写入数据

#### Scenario: 启动期外部依赖须被逐一识别

- **WHEN** 首次建立该测试基建
- **THEN** 须以**实测**（而非读代码推断）确认启动期需要外部依赖的 Bean 清单并逐一隔离
- **AND** 已识别（2026-10-13 实测）：敏感词服务的 `@PostConstruct` 查库、
      Redisson 在 **Bean 创建期**建连（非懒加载）、消息处理器订阅广播 topic

#### Scenario: 测试专用配置不得含裸凭据

- **WHEN** `src/test/resources/**` 下的 properties 含口令 / 密钥 / 令牌类键
- **THEN** 凭据门禁（`verify_no_hardcoded_secret.mjs`）须报红
- **AND** 该门禁的扫描范围**须包含测试资源**（2026-10-13 前不包含，已补）

#### Scenario: 测试日志须降噪且可读

- **WHEN** 集成测试启动失败
- **THEN** 同一条根因在日志中**不得**重复数十次淹没首行原因
- **AND** 根因（`Caused by:` 末层）须可直接定位

---

### Requirement: 测试断言的判别力须被证明

新增的行为断言若未经变异验证，不得视为已覆盖 —— 未触发过失败的断言可能恒为真。

#### Scenario: 每条守卫行为须有对应变异

- **WHEN** 新增拦截 / 校验 / 限流的行为断言
- **THEN** 须以变异（把守卫条件、比较方向或阈值**写反**）确认对应用例**转红**
- **AND** 变异**必须还原**，且以 `git diff` 复核生产代码零改动

#### Scenario: 断言中文文案时须显式字符集

- **WHEN** 断言依赖响应体中的中文文案
- **THEN** 须显式使用 `getContentAsString(StandardCharsets.UTF_8)`
- **AND** 否则该断言在默认 ISO-8859-1 解码下**恒为假**，形成**假通过**（2026-10-13 实测踩中）