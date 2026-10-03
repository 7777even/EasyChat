# Spec Delta — 审计日志补记客户端 IP + @所有人 权限下沉服务端

- 关联 Tasks: `openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth/tasks.md`
- 创建日期: 2026-10-03

> 格式对齐 `openspec/specs/<capability>/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 操作审计日志记录客户端 IP（对齐 Capability C1）

安全与审计类操作被记录时，日志行携带发起方客户端 IP，用于事后溯源。

#### Scenario: 登录失败记录来源 IP

- **WHEN** 用户登录失败（密码错误或账号已禁用）
- **THEN** 写入一条 `operation_log`，其 `ip_address` 为该请求的客户端 IP
- **AND** 不为 NULL

#### Scenario: 密码与强制下线类事件记录 IP

- **WHEN** 用户修改密码，或被管理员强制下线，或消息被管理员删除
- **THEN** 对应 `operation_log` 行的 `ip_address` 为该请求的客户端 IP

#### Scenario: 调用方显式传入 IP 时不被覆盖

- **WHEN** 调用 `recordLog` 时显式传入非空 `ipAddress`
- **THEN** 以传入值为准，自动补齐逻辑**不覆盖它**

#### Scenario: 无请求上下文

- **WHEN** `recordLog` 在非 HTTP 线程被调用（如启动任务、异步任务）
- **THEN** `ip_address` 记为固定占位值 `"-"`
- **AND** **不抛异常**、不影响主流程

#### Scenario: IP 获取异常不影响主流程

- **WHEN** 获取客户端 IP 的过程抛出异常
- **THEN** 记占位值 `"-"`，`recordLog` 正常返回
- **AND** 主业务流程不受影响

#### Scenario: 历史数据

- **WHEN** 查阅本变更之前写入的 `operation_log` 行
- **THEN** 其 `ip_address` 为 NULL 属正常（过去从未采集），本变更不回填

---

### Requirement: 客户端 IP 取值规则统一（对齐 Capability C2）

系统内所有需要「客户端 IP」的位置共用同一套取值规则。

#### Scenario: 反向代理场景

- **WHEN** 请求头 `X-Forwarded-For` 含多跳（形如 `client, proxy1, proxy2`）
- **THEN** 取**首段**（最初发起请求的客户端）

#### Scenario: 无代理场景

- **WHEN** 请求头无 `X-Forwarded-For` 或其为空白
- **THEN** 回退取 `HttpServletRequest#getRemoteAddr()`

#### Scenario: 两者皆不可得

- **WHEN** 两者均不可得（含无请求上下文）
- **THEN** 返回固定占位值 `"-"`，**不得抛出异常**

#### Scenario: 限流与审计共用同一实现

- **WHEN** 限流计算与审计记录都需要客户端 IP
- **THEN** 二者使用同一实现，不存在两套取值逻辑

---

## MODIFIED Requirements

### Requirement: @所有人 消息样式

@所有人的消息显示特殊样式，且**仅群主/管理员可发送**。

#### Scenario: 群主或管理员发送 @所有人

- **WHEN** 发送者在群聊中发送 `extraData.atAll = true` 的消息，且其群角色为群主或管理员
- **THEN** 消息发送成功
- **AND** 消息显示特殊样式

#### Scenario: 普通群成员发送 @所有人 被服务端拒绝

- **WHEN** 发送者群角色为普通成员（role=2），但其请求携带 `extraData.atAll = true`
- **THEN** 服务端返回 `CODE_2305`（无权执行此操作）
- **AND** 消息**不落库**、**不推送**给任何群成员
- **AND** 即使前端不展示该入口，手工构造请求也无法冒用群主/管理员身份

#### Scenario: 非成员发送

- **WHEN** 发送者不在该群
- **THEN** 服务端返回 `CODE_2304`（已不在该群组）

#### Scenario: 单聊不受影响

- **WHEN** 单聊消息的 `extraData` 含 `atAll = true`
- **THEN** **不做任何群角色校验**，正常发送（@所有人 只对群聊有意义）

#### Scenario: 解析失败按「非 @所有人」处理

- **WHEN** `extraData` 为非法 JSON、超长、缺失 `atAll` 字段，或 `atAll` 取值不是布尔
- **THEN** 视为**非** @所有人 消息，不触发角色校验
- **AND** **不得**因解析异常而抛错打断正常发消息

**变更前（引用原 spec）**：`openspec/specs/at-all/spec.md` 规定「群主/管理员可在群聊中@所有人」
且「普通群成员不显示@所有人选项」，并在「已知边界」中自述：**「权限仅在客户端生效：普通成员手工构造
请求并塞入 `extraData.atAll` 仍可使消息显示 @所有人 样式。服务端侧鉴权需单独 Change」**。

**变更后**：该权限判断下沉至服务端；权限不足时**拒绝发送**并返回 `CODE_2305`，
「已知边界」中的该项作废。

---

## REMOVED Requirements

无。

> 注：`REMOVED` 段为空并不表示本变更零风险——真正的「移除」是把一条**已写在 spec 里的不安全边界**
> 转为已实现的安全行为（见 MODIFIED 段的「变更前/变更后」对照）。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 审计日志记录 IP | ADDED: 操作审计日志记录客户端 IP | T1.2, T1.4, T1.5, T3.3 |
| C2 IP 取值规则统一 | ADDED: 客户端 IP 取值规则统一 | T1.1, T1.2, T1.3 |
| C3 @所有人 权限下沉 | MODIFIED: @所有人 消息样式 | T2.2, T2.3, T2.4, T3.3 |
| （安全网）门禁先红后绿 | — | T0.1, T0.2, T0.3 |