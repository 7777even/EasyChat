# Proposal — 后端通话支持多实例部署

- 创建日期: 2026-10-14
- 效率等级: **L4**（命中 §8：`WebSocket 协议` + 部署形态 + 引入跨实例一致性语义）

## Why

`docs/system-facts.md` §1.1 与本仓 `CallService` 的类注释都写着同一句判断：

> 「信令能跨实例是因为广播走 Redisson RTopic，但**通话房间注册表不跨实例**：一旦后端跑多份，
> A 与 B 被负载均衡打到不同实例时，呼叫/接听信令将找不到对方，通话直接建立失败。」

**2026-10-14 实测，这句话对通话信令是错的，且真实缺口比台账多一层：**

| 链路 | 实现 | 跨实例 |
|---|---|---|
| 普通消息分发 | `MessageHandler.sendMessage` → RTopic `message.topic` 广播 → 各实例经 `USER_CONTEXT_MAP` 本地投递 | ✅ **可用** |
| **通话信令分发** | `CallService.sendTo` → `ChannelContextUtils.sendRawToUser` → **直接写本地 Netty `ChannelGroup`** | ❌ **不可用** |
| **通话房间注册表** | `CallService.rooms` / `userCall` = 进程内 `ConcurrentHashMap` | ❌ **不可用** |

也就是说：多实例下**不是「走到某一步才失败」，而是 invite 一发出就送不到对端**
（`sendRawToUser` 只 `USER_CONTEXT_MAP.get(userId)`，查不到本地 ChannelGroup 就静默return）。

**后果**：本仓任何「横向扩容」的部署动作都会让通话**静默失效**（没有报错、没有日志，
只是「拨不通」），而消息、在线状态等其余功能仍然正常 —— 故障面被压缩在一个功能上，极难察觉。

## 现状事实（2026-10-14 实测）

| 事实 | 证据 |
|---|---|
| `rooms` / `userCall` 进程内 | `CallService.java:66,68` `ConcurrentHashMap` |
| `sendTo` 只走本地投递 | `CallService.java:386-388` → `channelContextUtils.sendRawToUser` |
| `sendRawToUser` 只写本地 ChannelGroup | `ChannelContextUtils.java:206-215`，`USER_CONTEXT_MAP.get(userId)` 为空即静默 return |
| 普通消息已走 RTopic 广播 | `MessageHandler.java:38-41` |
| `USER_CONTEXT_MAP` 同为进程内 | 同上（compose 注释已警告勿 `--scale`）|
| 仓库内**无负载均衡器** | compose services = `mysql, redis, coturn, migrate, backend`（无 nginx/haproxy）|

## What Changes

### 方案 A（我倾向）：房间注册表迁 Redis + 通话信令走独立广播 Topic

- 后端:
  - `rooms` / `userCall` 迁 Redis（`RMap` + TTL），并保留进程内读缓存以降低延迟。
  - 新增 `CallFrameTopic`（RTopic）：`sendTo` 改为广播，各实例按 `USER_CONTEXT_MAP` 本地投递
    （**完全复用 `MessageHandler` 已验证的模式**，不发明新机制）。
  - 关键路径全部改为「先广播、再本地投递」，不依赖「目标和我在同一实例」。
  - `onUserDisconnect` 需处理「清理发生在另一实例」的情况（广播 disconnect 或 Redis TTL 兜底）。
- 部署: 需要外部负载均衡（**不在本仓交付**），仓库提供配置示例与验收步骤。
- 数据库: **无表结构变更**（`call_log` 不动）。

### 方案 B：会话保持（sticky session），不改代码

负载均衡按 `userId` 哈希到固定实例。**不改任何代码**，但：
- 实例重启/扩缩容时用户会漂移，通话再次静默失效；
- 只是把进程内约束**转移给了 LB 配置**，没有消除；
- 需在 compose 注释与 README 明确「必须配 sticky」。

### 方案 C：维持现状 + 强化告警

只把「多实例不可用」做成**启动期校验**（检测到自身不在单实例拓扑时报错或显著告警）。
成本最低，但**问题本身未解决**。

## Capabilities

- **C1**：通话房间注册表跨实例一致（Redis 为唯一真源）。
- **C2**：通话信令可跨实例送达（广播 + 本地投递）。
- **C3**：实例内用户断开时，房间状态能正确收敛（不残留幽灵房间）。
- **C4**：启动期能检测并明确报出「当前部署形态不支持多实例」（若最终选 B/C 亦适用）。

## Impact

- **对外接口**: **协议不变**（仍复用 `-10~-17` 帧），**帧内容不变**。
  变化的是**路由与一致性语义**（跨实例），客户端零改动。
- **存量数据**: `call_log` 无变更。房间数据迁 Redis 后，**正在进行的通话会有一秒级窗口受迁移影响**
  （需热替换或停机发布）。
- **性能**: Redis 读写增多（每次信令 1 次读），通话信令本身低频，影响可忽略；
  但**引入了新的故障依赖**（Redis 不可用时通话建立失败，而非降级）。
- **安全**: 广播 Topic 需防串扰（`callId` 必须全局唯一；现有逻辑复用客户端 `callId`，
  **需评估是否要改为服务端强制生成**，否则跨实例下可能碰撞）。
- **回退方案**: 还原为进程内实现即可；**无数据迁移、无协议变更**。
  但已部署的 Redis key 结构与回退后不兼容，需版本门禁。

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 <YYYY-MM-DD> 确认，允许进入 design 阶段。
>
> - [ ] 同意方案 A，允许继续
> - [ ] 选方案 B（sticky session，不改代码）
> - [ ] 选方案 C（只做启动期告警）
> - [ ] 退回重新评估

---

## 需你知悉的三点（影响是否值得做）

1. **当前部署是单实例，这不是「正在发生」的故障。** 它是**能力缺口**：
   一旦有人为了扩容跑 `--scale` 或加 LB，通话会静默失效。
   所以真实收益是「扩容不再踩雷」，而不是「用户此刻遇到 bug」。
2. **本仓没有负载均衡器。** 即使代码改完，多实例仍需外部 LB；
   本次只能交付「代码层面可多实例」+ 配置示例，**无法在仓库内闭环验证**。
3. **`callId` 跨实例唯一性是个真实风险点。** 现有逻辑优先复用客户端传入的 `callId`
   （`CallService.java:223-228`，为了保证发起方能拿到自己的 callId）。单实例下无害，
   多实例下**两个客户端的相同 `callId` 会撞到同一个房间** —— 这是本 Change 必须处理的安全点，
   也可能需要**改客户端协议**（那样范围会扩大，须你拍板）。
