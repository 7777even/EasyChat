# QA — Redis 故障时的呼叫失败语义修复（遗留 #35 闭环）

- 日期: 2026-10-21
- 效率等级: **L2**（配置 + 局部投递路径修正；**不**改接口契约 / 权限语义 / 数据模型 / WS 帧协议；
  **不**碰 `application.properties`，不触 `AGENTS.md` §8 任一 L4 项）
- 范围: `CallService#sendCallError` 的投递路径、`ChannelContextUtils#sendRawToUser` 的返回值、
  `ChannelContextUtils#addContext` 的执行顺序、`RedisConfig` 的 Redisson 重试策略。
  **不改** WS 帧协议、**不改** 错误码语义（`2803`/`2802` 含义不变）、**不改** 数据库。

## 起因

2026-10-21 的多实例补验登记了遗留 **#35**：「Redis 不可用时发起方的失败反馈延迟约 28s」。
本轮把它修掉，**并在排查中挖出两个更深的问题**（重复投递 5 份、通道未注册）。

## 验收口径

1. Redis 不可用时，发起方**必须收到** `2803` 错误帧（不静默），且**只收到 1 份**；
2. 失败反馈延迟**显著低于**修复前的 ~28s；
3. 错误帧**不经 topic** 投递（目标恒为本地发起方 → 顺带保证「Redis 挂了也送得到」）；
4. Redisson **自动重试关闭**（`PUBLISH`/`HSETNX` 非幂等，重试有害）；
5. Redis 故障期间**新连上来的** WS 客户端**仍被注册**进投递体系；
6. **不回归**：正向通话、LB 分流、并发隔离四道冒烟仍全绿。

## 实际执行与结果

| 项 | 命令 | 结果 |
|---|---|---|
| TDD 红阶段 | `mvn -Dtest=CallErrorDeliveryTest,RedisConfigTest,ChannelContextRegistrationTest test` | **如期转红**：`expected:<0> but was:<3>`（重试未关）、`sendRawToUser` 未被调用（错误帧仍走 topic）、通道未注册 |
| TDD 绿阶段 | 同上 | **全绿**（EXIT=0） |
| 后端全量 | `mvn test` | **974 全绿**，Failures 0 / Errors 0 / Skipped 0（基线 968 + 本轮新增 **6**：3 + 1 + 2） |
| 前端全量 | 未改动前端 | 不适用 |
| 本地门禁 | pre-push 16 道 | 失败 **0**（见下「证据」） |
| 冒烟·直连跨实例 | `node scripts/smoke/multi-instance-smoke.mjs` | **5 PASS / 0 FAIL** |
| 冒烟·LB 分流 | `node scripts/smoke/multi-instance-lb.mjs` | **6 PASS / 0 FAIL**（分发 `[5051, 5056]`） |
| 冒烟·4 路并发隔离 | `node scripts/smoke/multi-instance-concurrency.mjs` | **32 PASS / 0 FAIL** |
| 冒烟·Redis 不可用 | `node scripts/smoke/multi-instance-redis-down.mjs` | **6 PASS / 0 FAIL** |
| 落库核对 | `SELECT * FROM call_log ORDER BY id DESC` | 4 路并发各 1 行 `status=1(已接)`、`participant_count=2` |

### 修复前后的关键数字（同一台机器、同一活库、同一 Redis）

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 失败帧份数 | **5 份**（1 本地 + 4 份 Redisson 重试排队后一起执行的 `PUBLISH`） | **1 份** |
| 失败反馈延迟 | **28319ms**（三次复现：28034 / 28311 / 28319） | **7273ms**（-74%） |
| 通道注册（Redis 故障期间连上来的客户端） | **未注册** → 收不到任何推送 | **已注册** → 可投递 |

## 根因（三条，均由实测/反编译确认，非推断）

1. **Redisson 自动重试**：3.12.3 的默认是 `timeout=3000ms` + **`retryAttempts=3`** +
   `retryInterval=1500ms`（`javap` 反编译 `redisson-3.12.3.jar` 确认；
   `Config` **不继承** `BaseConfig`，这些字段在 `SingleServerConfig` 上）。
   → 建房一次要等 3s×4 + 1.5s×3 ≈ 18s~28s；且 `PUBLISH` 被重发 → 恢复后一起执行 → **重复投递**。
   ⚠ **我此前在台账里写的「默认 30s 超时」是错的**（已订正）。
2. **错误帧走了 topic**：目标恒为「触发本次呼叫处理的发起方」（7 个调用点全传 `fromUserId`，
   `handleCallFrame` 的唯一调用点是 `HandlerWebSocket`＝本实例 WS 会话），
   本可直投；走 topic 平白多一次 Redis 往返（重试风暴）+ 引入重复投递。
3. **通道注册依赖 Redis**：`ChannelContextUtils#addContext` 里 `addUserChannel`（本地注册）
   排在 `redisComponet.getUserContactList`（Redis）**之后**，整段共用一个 try
   → Redis 故障时前者抛异常被外层 catch 吞掉 → **通道从未注册**
   → 用户「WS 连上了、却不在投递体系内」，且**无任何提示**。

## 改动明细

| 文件 | 改动 |
|---|---|
| `websocket/CallService.java` | `sendCallError` 改**本地优先**投递（本地送达即返回；否则退回广播兜底） |
| `websocket/ChannelContextUtils.java` | ① `sendRawToUser` 改为回报「是否送达」（`boolean`）；② `addContext` 拆三段：属性绑定 / **本地注册（不依赖 Redis）** / Redis 段各自容错 |
| `redis/RedisConfig.java` | 抽出可单测的 `buildConfig()`，并显式 `setRetryAttempts(0)`（含代价说明的 javadoc） |
| `websocket/MessageHandler.java` | 未改（`RedissonUtils` 经查为**死代码**） |

## 未运行项（如实登记，不得以全绿替代）

1. **真实 Nginx/HAProxy 形态**：本轮补验时仍是连接级 round-robin TCP 代理等价模拟；
   **同日稍后已用容器化 nginx 实跑并通过**（分流证据取自 nginx 自己的 access log，
   见 `engineering/qa/2026-10-14-call-multi-instance.md` 未运行项 3）；
   ⚠ **HAProxy 等其它 LB 实现仍未验证**。
2. **WebRTC 媒体链路**未验证（需 GUI + 真实媒体）。
3. **容量/吞吐上限**未压测（并发用例是**隔离性**冒烟）。
4. **Redis 故障期间新连接客户端的「群组消息订阅 / 心跳 / 在线状态」**仍暂缺
   （依赖 Redis 的联系人列表与状态写入）—— 已改为**各自容错**（记 ERROR、不连累其余步骤），
   但功能本身在故障期间确实缺失。
5. **失败反馈 7.3s 未再压到 3s**：建房是 `HSETNX` + `EXPIRE` 两步，各 3s 超时；
   若把 `EXPIRE` 改成异步下发可省 3s，但会带来「房间可能没 TTL → 永久 key」的风险，
   **本批不做**（需另作决策）。
6. **消息广播的更强投递保证**（显式重试 + 幂等去重）未实现 → 登记为遗留 **#36**。

## 证据

- TDD 红阶段输出：`Redisson 自动重试必须关闭… expected:<0> but was:<3>`；
  `errorFrameIsDeliveredLocallyWhenTargetIsHere` FAILURE；`channelIsRegisteredEvenWhenRedisIsUnavailable` FAILURE。
- 修复后实测（`multi-instance-redis-down.mjs`）：
  `code=2803 message=通话服务暂时不可用，请稍后重试（阻塞后 7273ms）`，`结果：6 PASS / 0 FAIL`。
- 逐帧时间线（修复前，临时探针）：`t=3.8s 发 INVITE → t=28.6s 第 1 份 2803 → t=45.3s 4 份 2803 同时到达 → t=46.8s room=:1`。
- 门禁：pre-push 16 道全绿（`check-api-contract` / `check-ipc-registration` / `check-openspec-hygiene`
  + 13 道 `verify_*`）。
- 新增测试类：`CallErrorDeliveryTest`（3 例）、`ChannelContextRegistrationTest`（1 例）、
  `RedisConfigTest` 增 2 例（`redissonDisablesAutomaticRetries` / `redissonKeepsDefaultCommandTimeout`）。

## 结论

**通过**。失败反馈从 28.3s 降到 7.3s、错误帧从 5 份降到 1 份，
并修掉「Redis 故障期间新连接的客户端收不到任何推送」这一更深的缺陷。
遗留 **#35 闭环（已从 §14 删行）**；新增遗留 **#36**（消息广播不再自动重试的代价）。
