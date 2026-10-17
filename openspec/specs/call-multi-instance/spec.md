# Spec — 通话多实例部署（call-multi-instance）

> 本 spec 记录**后端通话在多实例部署下的跨实例能力与边界**，通话协议本身见 `specs/voice-call`，
> TURN 部署与凭据见 `specs/turn-deployment`。
> 来源：`openspec/archive/2026-10-14-call-multi-instance/spec-delta.md`

## ADDED Requirements

### Requirement: 通话信令可跨实例送达（call-signaling-cross-instance）

通话信令帧 SHALL 经广播 Topic 分发，由**持有目标用户连接的那个实例**本地投递，
不依赖「目标用户与我在同一实例」。

#### Scenario: invite 跨实例送达

- **WHEN** 实例 A 的某用户发起通话，目标用户连接在实例 B
- **THEN** 信令帧经广播 Topic 发出，**B** 投递给目标用户
- **AND** A 侧的调用方**不得**因「本地查不到该用户的 ChannelGroup」而静默丢弃

#### Scenario: 应答与 candidate 跨实例回传

- **WHEN** 被叫在实例 B 应答，发起方在实例 A
- **THEN** 应答帧同样经广播回传，由 **A** 投递给发起方
- **AND** A 侧房间状态被更新（A 是该 callId 的创建者）

#### Scenario: 不广播无效目标

- **WHEN** 信令帧的 `targetUserId` 为空
- **THEN** **不**发广播（避免放大流量）
- **AND** 记 WARN 而非静默

#### Scenario: 广播可被紧急关闭且关闭时有告警

- **WHEN** `easychat.call.broadcast-signaling` 置为 `false`
- **THEN** 退化为本地直投（旧行为）
- **AND** 启动时打显著 WARN：该配置下**多实例不可用**

#### Scenario: 广播失败退回本地直投

- **WHEN** `CallFrameTopic.broadcast()` 返回 `false`（发布失败）
- **THEN** `sendTo` 退回 `channelContextUtils.sendRawToUser(...)`
- **AND** **不得**因改造让单实例部署的既有行为变差

---

### Requirement: 通话房间注册表以 Redis 为唯一真源（call-room-registry-redis）

通话房间与「用户 → 房间」映射 SHALL 存于 Redis（带 TTL），
使房间状态不因创建者所在实例重启/漂移而丢失；**不得**再有进程内房间表与之并存。

#### Scenario: 不得存在进程内房间表

- **WHEN** 检视 `CallService`
- **THEN** **不得**声明 `rooms` / `userCall` 等进程内 Map
- **AND** 房间读写全部经 `CallRoomStore`
- 说明：两份真源会把「Redis 故障」变成**静默降级**（本地那份继续工作，故障不可见）

#### Scenario: 房间键带 TTL

- **WHEN** 创建房间
- **THEN** Redis key **必须**设置 TTL（异常残留最终自动回收）
- **AND** 无 TTL 的永久 key 属缺陷

#### Scenario: 成员状态变更为字段级写

- **WHEN** 成员接听 / 挂断 / 中途加入
- **THEN** 只写该成员对应字段，**不**整房 read-modify-write
- **AND** 避免并发下 lost update

#### Scenario: Redis 不可用时明确失败

- **WHEN** 房间读写过程中 Redis 不可用
- **THEN** 向发起方 `sendCallError` 并记 ERROR
- **AND** **不得**静默返回成功（那会让用户以为通话建立、随后无声）

---

### Requirement: callId 冲突须被拒绝而非静默合并（call-id-conflict-rejected）

服务端 SHALL 拒绝与既有房间 `callId` 冲突的新通话，**绝不**把两个不同通话合并进同一房间。

#### Scenario: 创建时检测到 callId 已存在

- **WHEN** 收到 invite，而 Redis 中已存在同 `callId` 的房间
- **THEN** 拒绝该 invite 并向发起方回 `CODE_CALL_ID_CONFLICT`
- **AND** 记 WARN 日志，**不**向既有房间添加成员

#### Scenario: 不改协议

- **WHEN** 评估是否需要让客户端参与 callId 生成
- **THEN** **不需要** —— 客户端用 `crypto.randomUUID()`，碰撞概率可忽略
- **AND** 「拒绝 + 报错」已足够防住最坏情况；为概率 0 事件改协议属用契约变更换零收益

---

### Requirement: 用户断开跨实例收敛（call-disconnect-convergence）

某实例上的用户断开连接后，房间状态 SHALL 经 Redis 收敛，不残留幽灵房间。

> **⚠ 本 Requirement 于实施时修订**：原设计为「广播 `call:disconnect`，各实例清理本地状态」，
> 实测**不需要**——删除 `rooms` / `userCall` 后 `CallService` 已无任何本地状态可清理。
> 照原计划实现会为一个不存在的问题增加广播复杂度。
> 依据 AGENTS §2.1 第 12 条（先穷举路径再执行计划），此处改计划而非照做。

#### Scenario: 断开无本地状态可清理（原「断开事件被广播」已撤除）

- **WHEN** 实例 A 上某用户 WS 断开
- **THEN** `onUserDisconnect` 经 `callRoomStore.callIdOf()`（Redis）定位其通话
- **AND** 成员更新 / 解绑 / 结束房全部读写 Redis，**与房间创建于哪个实例无关**
- **AND** **不**广播 `call:disconnect` —— 无本地状态可清理

#### Scenario: TTL 为兜底而非主路径

- **WHEN** 创建房间的实例在 Redis key 写入前宕机（房间即不存在）
- **THEN** 已写入的房间由 Redis TTL 最终回收
- **AND** 该路径为**兜底**，不得被当作常规清理手段依赖

---

### Requirement: 多实例能力边界须被如实声明（call-multi-instance-boundary）

规格 SHALL 如实声明本能力的边界，禁止把「单实例回归通过」表述为「多实例已验证」。

#### Scenario: 分层能力须分别声明

- **WHEN** 描述本能力的完成度
- **THEN** **广播层 + Redis 房间落地后**，跨实例的常规通话链路**具备**可用前提
- **AND** 该前提**未经多实例实跑确认**（本仓无 LB、无双实例环境）
- **AND** **发起方实例在 key 写入前宕机**导致房间丢失仍未完全消除（Redis TTL 只是兜底）
- **故**：不得合并宣称「多实例通话完全支持」

#### Scenario: 本地不可验证项须登记

- **WHEN** 交付本能力
- **THEN** QA **必须**登记「多实例实跑未验证」「并发一致性未压测」「外部 LB 分流未验证」
- **AND** 不得以单实例测试全绿替代该结论

---

### Requirement: 订正错误的系统事实陈述（call-multi-instance-fact-correction）

`docs/system-facts.md` 中关于通话多实例能力的错误陈述 SHALL 被订正。

#### Scenario: §1.1 单实例约束的订正

- **WHEN** 检视 `docs/system-facts.md` §1.1「容器部署注意」
- **THEN** 原文「`CallService.rooms` / `USER_CONTEXT_MAP` 为进程内 `ConcurrentHashMap`，
      后端多实例部署时通话房间路由会失效（单实例约束，未解决）」被订正为：
      房间与反查已迁 Redis，**房间路由可跨实例**
- **AND** 明确保留：仍受进程内约束的是 `USER_CONTEXT_MAP`（WS 通道映射本身，属建连层）

#### Scenario: 引用不存在的类须订正

- **WHEN** 检视 `docs/system-facts.md` 中「服务端 `CallRoomRegistry`（内存，线程安全）维护房间」
- **THEN** 订正为真实结构：`CallService`（编排）+ `CallRoomStore`（Redis）+ `CallRoomCore`（纯判定）
- **AND** 依据：`git log -S "class CallRoomRegistry"` 全历史 **0 条** —— 该类从未存在

---

## 追溯矩阵

| Proposal Capability | Requirement | Task |
|---------------------|-------------|------|
| C1 房间注册表跨实例 | call-room-registry-redis | T1, T5, T6, T7 |
| C2 信令跨实例 | call-signaling-cross-instance | T2, T3, T4 |
| C3 断开收敛 | call-disconnect-convergence | T8（改计划不实现）, T9 |
| （安全） | call-id-conflict-rejected | T1, T7 |
| （治理） | call-multi-instance-boundary | T10, T13, T15 |
| （订正） | call-multi-instance-fact-correction | T15 |

## 门禁

`node scripts/verify/verify_call_multi_instance.mjs`（CI）——7 类不变量，探针 **7/7 捕获**。
⚠ 它**不能**证明多实例真的能跑（那需 LB + 双实例），只锁住「不回退」。
