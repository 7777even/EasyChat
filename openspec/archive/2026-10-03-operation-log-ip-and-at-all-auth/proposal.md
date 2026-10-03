# Proposal — 审计日志补记客户端 IP + @所有人 权限下沉服务端

- 创建日期: 2026-10-03
- 效率等级: **L4**

## Why

两处「表/字段早就备好、行为却从未落地」的缺口，构成审计与权限两条失效的控制面：

1. **`operation_log.ip_address` 恒为 `null`**。表列已存在、Service 接口已带 `ipAddress` 参数，
   但**全仓 6 处调用点无一例外传 `null`**（`UserInfoServiceImpl:270/275/280/420/504`、
   `ChatMessageServiceImpl:673`）。结果是恰恰最需要 IP 溯源的事件全部没有 IP：
   `LOGIN_FAILED`（密码爆破无法定位来源 IP）、`FORCE_OFFLINE`、`UPDATE_PASSWORD`（疑似泄露）。
   审计日志存在，却无法用于审计。

2. **`@所有人` 权限仅在客户端生效**。`openspec/specs/at-all/spec.md` 自己写着「已知边界：
   权限仅在客户端生效」。Java 侧 `atAll` **零命中**——`saveMessage` 不做任何群角色校验，
   普通成员手工构造 `extraData={"atAll":true}` 即可冒用群主/管理员身份在群里发特殊样式消息。
   前端隐藏一个选项**不是安全边界**（AGENTS §6.2-4）。

> 两者都属 AGENTS §8 L4：前者是审计控制面，后者是权限语义。

## What Changes

- 后端:
  - 新增 `utils/IpTools.getClientIp()`：内部读 `RequestContextHolder`，取 `X-Forwarded-For`
    首段（回退 `getRemoteAddr()`，再回退固定占位值）。**从 `GlobalOperationAspect#resolveClientIp`
    抽出**，两处共用同一实现，消除重复。
  - `GlobalOperationAspect#checkRateLimit`：改为委托 `IpTools.getClientIp()`，行为不变。
  - `OperationLogServiceImpl#recordLog`：`ipAddress` 为空时自动填 `IpTools.getClientIp()`；
    无请求上下文（异步线程）时填占位值，**绝不抛异常**（保持「日志写入不影响主流程」语义）。
    6 处调用点**无需改动**。
  - 新增 `utils/ExtraDataTools.isAtAll(String extraData)`：容错解析 `atAll` 标记。
  - `ChatMessageServiceImpl#saveMessage`：群聊且 `atAll=true` 时，
    `groupInfoService.checkGroupRole(userId, groupId, ADMIN)`；权限不足抛 `CODE_2305`。
- 前端: **无代码改动**。`@所有人` 入口本就只对群主/管理员可见；服务端拒绝后前端已有
  `Message.error` 兜底展示 `code` 消息。
- 数据库: **无表结构变更**（`operation_log.ip_address` 列早已存在）。

## Capabilities

- **C1**: 操作审计日志记录发起方客户端 IP；无请求上下文时记占位值而非丢失或抛异常。
- **C2**: IP 取值规则统一（`X-Forwarded-For` 首段 → `getRemoteAddr()` → 占位值），
  限流与审计共用同一实现。
- **C3**: `@所有人` 权限下沉服务端：仅群主/管理员可发，普通成员服务端拒绝（`CODE_2305`）。

## Impact

- **对外接口**: 路由、入参、`Result<T>` 结构**均不变**。行为变更一处：
  `POST /chat/sendMessage` 在**群聊 + `extraData.atAll=true` + 发送者非群主/管理员**时
  由「发送成功」改为返回 `CODE_2305`。`check-api-contract.mjs` 应保持 0 漂移。
- **存量数据**: 不改表、不改存量行。历史 `operation_log` 的 `ip_address` 仍为 `NULL`
  （不回填——历史无数据可回填，属正常）。
- **性能 / 安全**：
  - C1 为一次 `RequestContextHolder.getRequestAttributes()` 读取 + 字符串解析，无 IO，耗时可忽略；
  - C3 多一次 `user_contact` 查询（`checkGroupRole` 已有缓存路径），仅在群聊带 `atAll` 时触发。
  - 安全收益：堵住权限冒充；审计日志恢复可用性。
- **回退方案**: `git revert` 即可。回退后 `@所有人` 退回纯前端限制（**即回到当前的不安全状态**，
  故回退不应作为常态选项）。

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
| IP 采集方式 | 抽出 `utils/IpTools` + `recordLog` 为空时自动填充（6 处调用点零改动） | 新增日志切面；从接口移除 `ipAddress` 参数 |
| `@所有人` 越权行为 | **拒绝并抛 `CODE_2305`** | 静默剥离 `atAll` 降级发送；新增专用错误码 |
| Change 粒度 | #2 与 #3 合为一个 Change | 拆成两个独立 Change |