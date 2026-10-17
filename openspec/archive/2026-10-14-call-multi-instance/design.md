# Design — 后端通话支持多实例部署

- 关联 Proposal: `2026-10-14-call-multi-instance/proposal.md`
- 创建日期: 2026-10-14
- 效率等级: **L4**

## 1. 现状事实（2026-10-14 实测）

| 事实 | 证据 |
|---|---|
| `rooms` / `userCall` 为进程内 `ConcurrentHashMap` | `CallService.java:66,68` |
| `sendTo` → `sendRawToUser` → **只写本地 Netty ChannelGroup** | `CallService.java:386-388` → `ChannelContextUtils.java:206-215` |
| 普通消息已用 RTopic 广播、各实例本地投递 | `MessageHandler.java:38-41`（topic `message.topic`）|
| `USER_CONTEXT_MAP` 同为进程内，信令/在线状态都受其限 | 同上 |
| **`callId` 由客户端 `crypto.randomUUID()` 生成** | `useCallStore.js:78` |
| 仓库内无负载均衡器 | compose services = `mysql, redis, coturn, migrate, backend` |

## 2. 架构决策（ADR）

### ADR-001：通话信令走**独立**广播 Topic，复刻 `MessageHandler` 已验证的模式

**决策**：新增 `RTopic` `call.frame.topic`，载荷为 `{targetUserId, frameJson}`。
各实例监听后经 `ChannelContextUtils.sendRawToUser` **本地投递**。

**为何独立 topic 而非复用 `message.topic`**：后者的载荷类型是 `MessageSendDto`，
混入通话帧会污染语义与序列化边界；且两者生命周期与重试策略不同。

**为何「广播 + 本地投递」而非「定向投递」**：目标用户在哪个实例**本机不掌握**
（`USER_CONTEXT_MAP` 是进程内的）。广播让每个实例各自判断本地是否有该连接 —— 
这与普通消息完全同构，**已被本仓验证过的机制**，不发明新东西。

**代价与缓解**：广播 N 个实例 = N 倍消息量。缓解：只有 `targetUserId` 非空时广播；
`npeersSize = n * instances`，实例数有限（个位数）时可接受。

### ADR-002：房间注册表迁 Redis，但**不改客户端可感知的任何语义**

**决策**：房间改为 Redis Hash（`call:room:<callId>`）+ 成员子 Map
（`call:room:<callId>:members`）+ `userCall` 反查 Map（`call:user`），带 TTL。

**为何用 Hash + 子 Map 而非整房 JSON**：成员状态变更（接受/挂断/加入）是**高频字段级写**。
整房 JSON 需要 read-modify-write，并发下会互相覆盖（ lost update ）。
字段级操作天然原子，避免引入分布式锁。

**为何保留进程内读缓存**：通话期间同一房间会被反复读。缓存价值有限
（信令本就低频），且缓存会引入一致性问题。
→ **第一版不加缓存**，若实测有压力再加，不预先优化。

### ADR-003：`callId` 唯一性用「拒绝」解决，**不改协议**

**决策**：创建房间时若 Redis 已存在该 `callId` → **拒绝该 invite**（`sendCallError`），
日志 WARN，**绝不静默合并两个房间**。

**为何不改协议**：客户端用 `crypto.randomUUID()`（UUID v4），碰撞概率 `≈ 2^-122`。
为概率 0 的事件改协议/改客户端，属典型的**用契约变更换零收益**。
「拒绝 + 报错」已足够防住最坏情况（静默合并）。

### ADR-004：用户断开跨实例收敛，用「广播断开事件」而非 Redis 心跳

**决策**：`onUserDisconnect` 仍只处理本地连接；**同时广播**一条
`call:disconnect` 事件，各实例据此清理与该用户相关的本地状态。

**为何不用心跳/TTL 替代**：Redis TTL 是**兜底**而非主路径（房间设 TTL，异常残留最终会被回收）。
断开是即时语义，应由事件驱动。

### ADR-005：拆成两层交付，先广播、后 Redis 房间

**理由**：广播（C2）**独立可验证且立刻产生价值**（跨实例投递打通），
Redis 房间（C1）是健壮性增量。分两层交付使每层可独立回归，
避免「一次改完但哪层坏了说不清」。

## 3. 关键实现细节

### 3.1 分层的收益界定（避免宣称「多实例完全支持」）

广播落地后，**跨实例的常规通话链路已通**（invite 广播到达对端 → 对端本地应答 →
answer 广播回发起方实例 → 发起方实例持房并更新）。
**仍未解决**：发起方所在实例宕机 → 房间丢失 → 其余参与者悬挂，直到 TTL 回收或超时。

→ **规格必须如实写明**这是两层能力，不得合并宣称。

### 3.2 需抽取纯核心的部分

房间判定逻辑（创建时的冲突检测、成员状态转移、人数上限）抽为纯函数，
避免 Redis IO 与判定混写（同 `TurnCredentialCore` / `preflightBaselineCore` 模式）。

## 4. 数据影响

**无表结构变更**。Redis **新增 key 空间**（`call:room:*` / `call:user`），
需在 `AGENTS.md` 与会话前缀约定中登记（现有实现在线状态 `easychat:ws:*`）。

**回退不兼容**：还原为进程内实现后，Redis 里的残留 key 不再被读取，
靠 TTL 自然过期（故 key 必须设 TTL，不得永久）。

## 5. 依赖

**不新增生产依赖**（Redisson 已在用）。

## 6. 风险与回退

| 风险 | 缓解 | 回退 |
|---|---|---|
| Redis 成为通话建立的新故障点 | key 带 TTL；Redis 不可用时**明确抛错**而非悬挂（`sendCallError`）| 还原进程内实现 |
| 广播放大流量 | 仅对非空 targetUserId 广播；实例数有限 | 关闭广播开关（配置项）|
| `callId` 跨实例碰撞 | ADR-003 拒绝 + WARN，不静默合并 | — |
| 成员状态竞态 | 字段级 Hash 写，不用整房 JSON | — |
| 已在进行中的通话受发布影响 | 两层拆分，分两次发布 | 还原 |

## 7. 待确认项（不假装已验证）

1. **多实例实跑无法在本地验证** —— 需两个后端实例 + 负载均衡，本机无 LB、单机资源有限。
   本机只能验证：**单实例下行为不变**（回归）+ 广播 Topic 的**消息确实被本实例消费**。
2. **真实并发下的成员状态一致性未压测** —— 需多客户端压测工具（本仓无）。
3. **`UserContactService.getUserContactByUserIdAndContactId` 等 DB 命中率**在多实例下是否成为瓶颈，
   未评估 —— 但这是既有的，不因本 Change 改变。
