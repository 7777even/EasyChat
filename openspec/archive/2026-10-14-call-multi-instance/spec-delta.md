# Spec Delta — 后端通话支持多实例部署

- 关联 Tasks: `2026-10-14-call-multi-instance/tasks.md`
- 创建日期: 2026-10-14

## ADDED Requirements

### Requirement: 通话信令可跨实例送达（call-signaling-cross-instance）— C2

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

---

### Requirement: 通话房间注册表以 Redis 为唯一真源（call-room-registry-redis）— C1

通话房间与「用户 → 房间」映射 SHALL 存于 Redis（带 TTL），
使房间状态不因创建者所在实例重启/漂移而丢失。

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

### Requirement: callId 冲突须被拒绝而非静默合并（call-id-conflict-rejected）— 安全

服务端 SHALL 拒绝与既有房间 `callId` 冲突的新通话，**绝不**把两个不同通话合并进同一房间。

#### Scenario: 创建时检测到 callId 已存在

- **WHEN** 收到 invite，而 Redis 中已存在同 `callId` 的房间
- **THEN** 拒绝该 invite 并向发起方回错误
- **AND** 记 WARN 日志，**不**向既有房间添加成员

#### Scenario: 不改协议

- **WHEN** 评估是否需要让客户端参与 callId 生成
- **THEN** **不需要** —— 客户端用 `crypto.randomUUID()`，碰撞概率可忽略
- **AND** 「拒绝 + 报错」已足够防住最坏情况；为概率 0 事件改协议属用契约变更换零收益

---

### Requirement: 用户断开跨实例收敛（call-disconnect-convergence）

某实例上的用户断开连接后，房间状态 SHALL 经 Redis 收敛，不残留幽灵房间。

> **⚠ 本 Requirement 于 T8 执行时修订**：原设计为「广播 `call:disconnect`，各实例清理本地状态」，
> 实测**不需要**——层二（T6）已删除 `rooms` / `userCall` 两个进程内 Map，`CallService`
> 已无任何本地状态可清理。照原计划实现会为一个不存在的问题增加广播复杂度。
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
- **THEN** **广播层落地后**，跨实例的常规通话链路可用
- **AND** **发起方实例宕机**导致房间丢失仍未由本 Change 完全消除（Redis TTL 只是兜底）
- **故**：不得合并宣称「多实例通话完全支持」

#### Scenario: 本地不可验证项须登记

- **WHEN** 交付本 Change
- **THEN** QA **必须**登记「多实例实跑未验证」（本机无负载均衡器、无双实例）
- **AND** 不得以单实例测试全绿替代该结论

---

### Requirement: 订正错误的系统事实陈述（call-multi-instance-fact-correction）

`docs/system-facts.md` 中关于通话多实例能力的错误陈述 SHALL 被订正。

> **⚠ 引文订正（2026-10-14 回写时修）**：本 delta 初版把「订正前原文」记成
> `信令能跨实例是因为广播走 Redisson RTopic，但通话房间注册表不跨实例`——
> 那句其实写在 `CallService` 的 **javadoc** 里，**不在** `system-facts.md`。
> 按 §2.1 第 13 条「断言必须当场重算」，实际待订正的是 `system-facts.md` 下面三处。

**订正点 ①（§1.1 容器部署注意，原文）**：

> `CallService.rooms` / `USER_CONTEXT_MAP` 为进程内 `ConcurrentHashMap`，
> **后端多实例部署时通话房间路由会失效**（单实例约束，未解决）

**订正后**：通话房间与反查已迁 Redis（`CallRoomStore`），**房间路由可跨实例**；
仍受进程内约束的是 `USER_CONTEXT_MAP`（WS 通道映射本身，属建连层）。

**订正点 ② / ③（§1.4 与语音通话行，原文）**：

> 服务端 `CallRoomRegistry`（内存，线程安全）维护房间

**订正后**：`CallRoomRegistry` **从未作为类存在**（`git log -S "class CallRoomRegistry"` 为 0 条），
真实结构是 `CallService`（编排）+ `CallRoomStore`（Redis 持久化）+ `CallRoomCore`（纯判定）。

---

## 追溯矩阵

| Proposal Capability | Requirement | Task |
|---------------------|-------------|------|
| C1 房间注册表跨实例 | call-room-registry-redis | T1, T5, T6, T7 |
| C2 信令跨实例 | call-signaling-cross-instance | T2, T3, T4 |
| C3 断开收敛 | call-disconnect-convergence | T8, T9 |
| （安全） | call-id-conflict-rejected | T1, T7 |
| （治理） | call-multi-instance-boundary | T10, T13, T15 |
| （订正） | call-multi-instance-fact-correction | T15 |
