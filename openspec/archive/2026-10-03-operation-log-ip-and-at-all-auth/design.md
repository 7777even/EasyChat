# Design — 审计日志补记客户端 IP + @所有人 权限下沉服务端

- 关联 Proposal: `openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth/proposal.md`
- 创建日期: 2026-10-03

## 1. 架构设计

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | **零改动** | 路由 + @Valid + 调 Service |
| Service | `OperationLogServiceImpl`（IP 自动填充）、`ChatMessageServiceImpl`（@所有人 鉴权） | 业务规则 + 事务边界；**不感知 HttpServletRequest** |
| Utils | 新增 `IpTools`、新增 `ExtraDataTools` | 纯工具，无状态 |
| AOP | `GlobalOperationAspect`：私有 `resolveClientIp` 删除，改委托 `IpTools` | 横切关注点 |
| Mapper / SQL | **零改动** | — |
| Entity | **零改动** | — |

### 调用链

```
C1 审计 IP
  Service（无 request 感知） → OperationLogService#recordLog(userId, type, desc, null)
      └─ OperationLogServiceImpl: ipAddress 为空 → IpTools.getClientIp()
           └─ RequestContextHolder.getRequestAttributes() → ServletRequestAttributes
                → HttpServletRequest.getHeader("X-Forwarded-For") 首段
                → 回退 getRemoteAddr()
                → 回退占位值 "-"
           无请求上下文（非 HTTP 线程，如 MQ / @Async / 启动任务）
                → 直接返回 "-"，**不抛异常**

C2 IP 规则统一
  GlobalOperationAspect#checkRateLimit → IpTools.getClientIp()   （原私有 resolveClientIp 抽出）

C3 @所有人 鉴权
  ChatController#sendMessage → ChatMessageService#saveMessage
      └─ 群聊分支（已有 checkMuted）
           └─ ExtraDataTools.isAtAll(extraData) == true
                └─ groupInfoService.checkGroupRole(userId, groupId, ADMIN)
                     ├─ 非成员     → CODE_2304
                     ├─ MEMBER(2)  → CODE_2305（本次要堵的洞）
                     └─ ADMIN(1)/OWNER(0) → 放行
```

## 2. 接口设计

**零接口契约变更。**

| 端点 | Method | 变更 |
|------|--------|------|
| `/api/chat/sendMessage` | POST | 行为变更：群聊 + `extraData.atAll=true` + 发送者非群主/管理员 → `CODE_2305` |

### 错误码

**不新增错误码**，复用 `2305`（`CODE_2305` 无权执行此操作，落在 AGENTS §3.1 群组域 `2300-2399` 内）。

> 选它而非 `2304`（不在该群）的原因：发送者**确实在群里**，只是权限不足；
> 选它而非新增专用码的原因：`2305` 语义已完全覆盖，且新增码需同步 §3.1 分段表 + 前端 `ErrorCode` 枚举，
> 收益不抵扩散面。

## 3. 数据模型

**零表结构变更。** `operation_log.ip_address` 列早已存在（`varchar`，可空），本变更只让**新写入的行**带上真实 IP。

## 4. 安全设计

- **鉴权**：`/chat/sendMessage` 保持 `@GlobalInterceptor`（需登录），不变。
- **数据权限（本次核心）**：`atAll` 从「客户端自述」改为「服务端判定」。
  关键前提已核实：**`saveMessage` 全仓只有一个外部调用方**（`ChatController:179`），
  内部仅一处机器人自回复调用（第 298 行，走 `ROBOT_UID` 分支、跳过群校验）。
  因此**不存在绕过 HTTP 的第二条发送路径**，单点校验即全覆盖。
- **输入校验**：
  - `ExtraDataTools.isAtAll` **必须 fail-safe**：解析失败 / 字段缺失 / 类型不符 → 一律返回 `false`，
    **不得**因为解析异常而误判为 true（误判会让普通成员被无端拒绝），更不得抛异常打断正常发消息。
  - `X-Forwarded-For` 是客户端可伪造的输入：仅作**审计线索与限流维度**，不作为任何授权依据。
    已在方法注释写明这一限制。
- **SQL 注入防护**：零新增 SQL。
- **容错**：`recordLog` 全程在 try-catch 内，IP 获取失败也不得影响主流程。

## 5. ADR

### ADR-001: IP 在 `recordLog` 内部补齐，而不是改 6 处调用点

- 状态: 已接受
- 上下文: 6 处调用点分别位于 `UserInfoServiceImpl` 与 `ChatMessageServiceImpl`，都是 Service 层，
  **不持有也不应持有 `HttpServletRequest`**（AGENTS §3.4）。要让它们传真实 IP，必须把 request 一路传到 Service——
  那是明确禁止的。
- 决策: `recordLog` 内部在 `ipAddress` 为空时调 `IpTools.getClientIp()` 自行补齐，调用点零改动。
- 后果: 正面——6 处调用点不动，未来新增调用点**默认就带 IP**，不存在「新加一处又忘了传」的漏网。
  负面——`ipAddress` 参数变得「可传可不传」，语义稍模糊；用 Javadoc 注明「传 null 表示由实现自动获取」。

### ADR-002: `IpTools` 内部持有 `RequestContextHolder`，Service 仍不感知 request

- 状态: 已接受
- 上下文: AGENTS §3.4 的红线是「Service 不感知 HttpServletRequest」，目的是让业务逻辑可脱离 Web 层测试。
- 决策: 红线的实质是「**Service 里不出现 request 对象**」，而非「全项目不许有人读 request」。
  把读取封进 `IpTools` 工具类后，`OperationLogServiceImpl` 的代码里没有任何 `HttpServletRequest`，
  单测可直接 mock 静态/工具或用 `RequestContextHolder` 造上下文。
- 后果: 正面——满足红线字面与实质，且逻辑单点可测。
  负面：`IpTools` 隐式依赖 Web 上下文，在纯后台线程调用时返回占位值（已在 ADR-003 明确为预期）。

### ADR-003: 无请求上下文时填 `"-"` 而非 `null`

- 状态: 已接受
- 上下文: `recordLog` 也可能被非 HTTP 线程调用（启动任务、异步任务）。
- 决策: 返回固定占位值 `"-"`。
- 后果: 正面——「有值但表示未知」与「没采到所以是 NULL」在数据上可区分，便于日后统计真实覆盖率。
  负面——占位值本身不是合法 IP，分析时需过滤。

### ADR-004: `atAll` 权限不足时**拒绝**而非降级剥离

- 状态: 已接受
- 上下文: 普通成员伪造 `atAll=true` 时，可选「拒绝」或「静默剥离后照发」。
- 决策: 拒绝并抛 `CODE_2305`。
- 后果: 正面——行为明确，发送者立刻知道「你不能@所有人」，不会产生「以为@了实际没@」的隐蔽不一致
  （后者在 IM 里是更难受的 bug：用户以为已通知全群，实际只有自己看到）。
  负面——老客户端若曾错误地给普通成员展示过该入口，会开始报错；但前端本就只对群主/管理员显示，受影响面为零。

### ADR-005: 新增 `ExtraDataTools` 而不在 Service 里内联 fastjson 解析

- 状态: 已接受
- 上下文: `ChatController#sendMessage` 里已有一处 fastjson 解析位置消息的 `extraData`；
  若 Service 再内联一份，会出现两套解析风格。
- 决策: 抽出 `ExtraDataTools`，只暴露 `isAtAll`，解析细节与容错策略集中一处。
- 后果: 正面——`extraData` 的解析规则不再散落；将来加别的扩展字段判定有统一落点。
  负面：多一个小工具类（可接受）。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| **`isAtAll` 误判为 true**（如 `"atAll":"1"`、嵌套同名字段） | 中 | 高（普通成员被无端拒绝发言） | 只认**顶层** `atAll` 且用 `getBoolean`；单测覆盖 `"true"` / `"false"` / `"1"` / `"0"` / 缺字段 / 非法 JSON / 空串 / 超长 七种输入；门禁固化 |
| 机器人自回复路径被误拦 | 低 | 高（机器人不能发消息） | 校验放在既有 `!ROBOT_UID.equals(...)` 分支**之内**，与 `checkMuted` 同处 |
| `checkGroupRole` 的 `GROUP` 前缀判定遗漏非群聊 | 低 | 高 | 与既有 `checkMuted` 使用完全相同的 `UserContactTypeEnum.GROUP == getByPrefix(...)` 条件 |
| `IpTools` 在非 Web 线程抛异常 | 中 | 中（影响审计写入） | 全程 try-catch，异常时返回占位值；`recordLog` 外层本就有 catch |
| 限流行为因抽出 `resolveClientIp` 而改变 | 低 | 中 | `GlobalOperationAspectTest` 现有 13 例全量回归；补 `IpTools` 单测 |
| 历史 `operation_log.ip_address` 为 NULL 被误认为「没采到 IP」 | 高 | 低 | 文档说明：新记录起才有 IP，历史为 NULL 属正常 |

## 7. 依赖与前提

- **新增生产依赖**：无。
- **新增表/列**：无。
- **新增 WS 帧**：无。
- **新增接口**：无。
- **前置**：与 `2026-10-03-password-session-and-mail` 已归档 Change 无冲突（本变更在其之后，依赖其引入的 `resolveClientIp` 抽取）。
- **后续**：`X-Forwarded-For` 可伪造这一点在直连部署下仍然存在，若将来要求「可信 IP」需引入可信代理白名单（本批不做，避免范围扩散）。