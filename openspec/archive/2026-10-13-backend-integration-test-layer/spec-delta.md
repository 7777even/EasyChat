# Spec Delta — 后端集成测试层（无 DB 方案 A）

- 关联 Tasks: `2026-10-13-backend-integration-test-layer/tasks.md`
- 创建日期: 2026-10-13

> 格式对齐 `openspec/specs/<capability>/spec.md`。
> 与 proposal Capabilities 一一对应。
> ⚠ 本变更的「能力」是**工程能力**（可验证性），不含产品功能变化。

## ADDED Requirements

### Requirement: 认证与限流守卫的行为可被自动化验证（对齐 C1）

系统的认证与限流守卫必须具备**行为级**自动化验证：仅断言「注解是否存在」不算覆盖，
因为守卫条件写反时结构断言照样通过。

#### Scenario: 非管理员访问管理端被拒

- **WHEN** 已登录的**非管理员**用户调用任一 `checkAdmin = true` 的端点
- **THEN** 响应 `code = 1003`（非管理员访问管理端）
- **AND** 不进入 Controller 方法体（Service 不被调用）

#### Scenario: 未登录端点超阈值被限流

- **WHEN** 未登录请求调用 `checkRateLimit = true` 的端点，且窗口内次数**超过**阈值
- **THEN** 响应 `code = 1001`
- **AND** 窗口内**未超**阈值时正常放行（否则"恒拒绝"也会让断言通过）

#### Scenario: 限流维度不可被伪造绕过

- **WHEN** 对以「目标 email」为限流维度的端点（`rateLimitByParam = "email"`）
  请求携带**不同的** `X-Forwarded-For`
- **THEN** 限流键**不随** XFF 变化（键由目标 email 决定）

---

### Requirement: 参数校验在真实 Web 上下文中触发（对齐 C2）

参数校验的两条路径必须在**真容器**中各自被验证；`MockMvcBuilders.standaloneSetup`
不注册 `MethodValidationPostProcessor`，在单测层无法覆盖方法级校验。

#### Scenario: 对象绑定校验（@Valid）触发字段级文案

- **WHEN** 以 `@ModelAttribute` 方式传入违反 Bean Validation 约束的 DTO（如空邮箱）
- **THEN** 响应 `code = 1001`，`message` 形如 `<字段名>: <提示>`
- **AND** 合法入参**不**触发校验（放行到 Service）

#### Scenario: 方法级校验（@Validated + 参数注解）触发

- **WHEN** 调用带类级 `@Validated` 且方法参数带约束注解的端点，且参数违反约束
- **THEN** 响应 `code = 1001`
- **AND** 该行为**必须**由真容器验证 —— 用 `standaloneSetup` 写此断言会**恒红**（环境缺失，非缺陷）

---

### Requirement: Spring 测试上下文可在无外部依赖下启动（对齐 C3）

集成测试上下文必须在**没有 MySQL / Redis / 邮件服务**的机器上可启动，
使行为验证在本机与 CI 上均可稳定复现。

#### Scenario: 无外部依赖时上下文可启动

- **WHEN** 在未提供 MySQL、Redis、SMTP 的环境运行集成测试
- **THEN** Spring 上下文成功加载，Flyway **不**执行迁移，数据源**不**建立真实连接
- **AND** 测试断言当前数据源 URL **不是**真实地址（防止误连活库）

#### Scenario: 测试上下文不得污染活库

- **WHEN** 集成测试运行
- **THEN** 不向任何真实数据库写入数据（Mapper 在测试中一律被 mock）

---

## MODIFIED Requirements

（无 —— 本变更不修改任何既有产品能力的行为。）

---

## REMOVED Requirements

### Requirement: 遗留 #32「`standaloneSetup` 无法验证类级 `@Validated` + 方法参数注解」

- **一句话描述**: 该盲区由本变更的 C2（真容器行为验证）取代，故从遗留台账移除。
- **移除原因**: 盲区的成因是**测试环境缺失**（`standaloneSetup` 不注册校验处理器），
  而非产品缺陷；一旦引入真容器层，该路径即可被真实行为断言覆盖。
- **替代方案**: `specs/integration-test` 的「参数校验在真实 Web 上下文中触发」Requirement。
- **⚠ 移除前提**: tasks T10 / T11 必须**已完成且转红验证通过**（T14）——
  若只写了结构断言就删行，等于把盲区换个地方继续存在。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 认证与限流守卫的行为可被自动化验证 | T6, T7, T8 |
| C2 | ADDED: 参数校验在真实 Web 上下文中触发 | T9, T10, T11 |
| C3 | ADDED: Spring 测试上下文可在无外部依赖下启动 | T1, T2, T3, T5 |
| （台账）遗留 #32 | REMOVED: 盲区被 C2 取代 | T10, T11, T14 |