# Spec — 跨域（CORS）允许来源与宽松组合告警

> 本 spec 记录**跨域允许来源**的配置能力，以及「凭据 + 通配来源」这一组合的**显式告警**要求。
> 风险背景见 `docs/system-facts.md` §14 #30；来源 `openspec/archive/2026-10-21-cors-origin-whitelist/spec-delta.md`

## ADDED Requirements

### Requirement: 跨域允许来源可配置（cors-origin-whitelist）

系统 SHALL 允许通过配置项 `easychat.cors.allowed-origin-patterns` 指定跨域允许的来源集合
（逗号分隔，支持通配），且**默认值 SHALL 保持为 `*`** —— 即默认行为与既有部署**逐字一致**。

#### Scenario: 默认配置回显任意来源

- **WHEN** 未配置该键（或配置为 `*`），且请求带 `Origin: https://any.example.com`
- **THEN** 响应 SHALL 回显该来源（与既有行为一致）
- **AND** SHALL 仍携带 `Access-Control-Allow-Credentials: true`

#### Scenario: 配置为白名单时只回显白名单内来源

- **WHEN** 配置为 `https://a.example.com, https://b.example.com`
- **THEN** 来自 `https://a.example.com` 的请求 SHALL 被回显该来源
- **AND** 来自**白名单外**来源（如 `https://evil.example.com`）的请求 SHALL **不被回显**
  （否则「收紧」只是摆设）

#### Scenario: 空配置回退且不得静默

- **WHEN** 配置项为空串或只含逗号
- **THEN** 系统 SHALL **回退到 `*`**（误配不应让应用不可用）
- **AND** SHALL 记录 **ERROR** 级日志说明「配置无效、已回退」

---

### Requirement: 宽松组合在启动时显式告警（cors-permissive-warning）

系统 SHALL 在启动时检测「`allowCredentials(true)` 与含通配符的允许来源**并存**」这一组合，
并输出**可操作**的告警：说明**当前为何不构成 CSRF 暴露面**、**何时会构成**、以及**如何收紧**。

#### Scenario: 默认（通配）配置下启动给出告警

- **WHEN** 应用以通配来源启动
- **THEN** SHALL 输出告警，指明该组合与「凭据 + 通配来源」的含义
- **AND** 告警 SHALL 说明当前会话使用 `token` **请求头**，故任意站点**认证不了**、暂不构成暴露面
- **AND** 告警 SHALL 说明「**任何端点改用 Cookie 会话即构成 CSRF 暴露面**」
- **AND** 告警 SHALL 给出收紧用的配置键 `easychat.cors.allowed-origin-patterns`
  （并提示收紧前须把桌面端渲染层来源纳入白名单）

#### Scenario: 精确白名单下不告警

- **WHEN** 配置为不含通配符的精确来源列表
- **THEN** SHALL **不**输出该宽松组合告警

#### Scenario: 含前缀通配同样视为宽松

- **WHEN** 配置含前缀通配（如 `http://*`）
- **THEN** SHALL 判定为**宽松**并照常告警（不得因「不是恰好等于 `*`」而漏判）
