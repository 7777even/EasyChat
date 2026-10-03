# Spec — 操作审计日志

## ADDED Requirements

### Requirement: 审计日志记录客户端 IP

安全与审计类操作被记录时，日志行携带发起方客户端 IP，用于事后溯源。

#### Scenario: 登录事件记录来源 IP

- **WHEN** 用户登录成功，或登录失败（密码错误 / 账号已禁用）
- **THEN** 写入的 `operation_log` 行其 `ip_address` 为该请求的客户端 IP
- **AND** 不为 NULL

#### Scenario: 密码与强制下线类事件记录 IP

- **WHEN** 用户修改密码，或被管理员强制下线，或消息被管理员删除
- **THEN** 对应 `operation_log` 行的 `ip_address` 为该请求的客户端 IP

#### Scenario: 调用方显式传入 IP 时不被覆盖

- **WHEN** 调用记录接口时显式传入非空 IP
- **THEN** 以传入值为准，自动补齐逻辑**不覆盖**它

#### Scenario: 无请求上下文

- **WHEN** 记录操作发生在非 HTTP 线程（启动任务、异步任务）
- **THEN** `ip_address` 记为固定占位值 `"-"`
- **AND** **不抛异常**、不影响主流程

#### Scenario: IP 获取异常不影响主流程

- **WHEN** 获取客户端 IP 的过程抛出异常
- **THEN** 记占位值 `"-"`，记录调用正常返回
- **AND** 主业务流程不受影响

#### Scenario: 历史数据

- **WHEN** 查阅 IP 采集功能上线之前写入的 `operation_log` 行
- **THEN** 其 `ip_address` 为 NULL 属正常（过去从未采集），**不回填**

---

### Requirement: 客户端 IP 取值规则统一

系统内所有需要「客户端 IP」的位置共用同一套取值规则。

#### Scenario: 反向代理场景

- **WHEN** 请求头 `X-Forwarded-For` 含多跳（形如 `客户端, 代理1, 代理2`）
- **THEN** 取**首段**（最初发起请求的客户端），并裁掉首段两侧空白

#### Scenario: 无代理场景

- **WHEN** 请求头无 `X-Forwarded-For`，或其为空白，或首段切出后为空
- **THEN** 回退取 `HttpServletRequest#getRemoteAddr()`

#### Scenario: 两者皆不可得

- **WHEN** 两者均不可得，或不存在请求上下文
- **THEN** 返回固定占位值 `"-"`
- **AND** **不得抛出异常**（否则会被上层 catch 吞成「审计日志静默丢失」）

#### Scenario: 限流与审计共用同一实现

- **WHEN** 限流计算与审计记录都需要客户端 IP
- **THEN** 二者使用同一实现，不存在两套取值逻辑

#### Scenario: 伪造边界

- **WHEN** 判定该 IP 的可信度
- **THEN** 承认 `X-Forwarded-For` 可被客户端伪造，该值**仅作审计线索与限流维度，不作为任何授权依据**