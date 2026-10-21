# QA — 后端通话支持多实例部署（call-multi-instance）

- 日期: 2026-10-14
- 效率等级: **L4**
- 范围: 通话信令跨实例送达（层一）、房间注册表迁 Redis（层二）、断开收敛判定与故障显式化（层三）、
  新门禁 `verify_call_multi_instance.mjs`。**不改** `call_log` 表、不改 WS 帧协议、不改前端。

## 验收口径

- 层一：通话信令经 `CallFrameTopic` 广播，目标用户挂在**别的实例**时仍能收到；
  广播失败或开关关闭时**退回本地直投**（不因改造让单实例变差）。
- 层二：`rooms` / `userCall` 两个进程内 Map 删除，Redis 为唯一真源；房间 key 带 TTL；
  成员状态用字段级写；`callId` 冲突**拒绝**不合并。
- 层三：Redis 不可用时向发起方 `sendCallError` 并记 ERROR，**不得静默**；
  广播开关关闭时启动打 WARN。
- 门禁有判别力（探针证明），且**不把单实例全绿表述为多实例已验证**。

## 实际执行与结果

| 项 | 命令 | 结果 |
|---|---|---|
| 后端全量 | `mvn -B test` | **968 全绿**，Failures 0 / Errors 0，BUILD SUCCESS |
| 前端全量 | `npm run test` | **786 全绿**（82 文件），exit 0 |
| 基线核对 | 968 − 本 Change 新增 39 | = **929**，与既有基线吻合（零回归） |
| 本地门禁批量 | 31 道 `verify_*.mjs` + `check-*.mjs` | 失败 **0**（CI 专属的 `verify_mutation_scripts.mjs` 另计） |
| 新门禁 | `node scripts/verify/verify_call_multi_instance.mjs` | exit 0 |
| 门禁批量 | 6 道（cmi / turn / error_code / call_ice_fail / call_core / openspec-hygiene） | 失败 **0** |
| 探针判别力 | `probe_cmi.mjs`（7 条变异） | **7/7 捕获**，0 漏网 0 无效，源码字节一致还原 |
| 表格列数 | `tabaudit.mjs` | AGENTS §10 / facts §14 / facts 变更日志 均 0 错位 |

### 新增测试明细（39 例）

| 测试类 | 例数 | 覆盖 |
|---|---|---|
| `CallRoomCoreTest` | 13 | callId 冲突、TTL 判定、终态、人数上限（纯判定，不碰 Redis） |
| `CallFrameTopicTest` | 6 | 载荷编解码、空目标不广播、开关 |
| `CallRoomStoreTest` | 14 | HSETNX、TTL、字段级 HSET、反查解绑 |
| `CallIdConflictTest` | 2 | 冲突拒绝 + `verify(store, never()).putMember` |
| `CallFrameTopicWiringTest` | 4 | **广播出去 → 本实例消费到**（接线层） |

### 门禁 7 类不变量（`verify_call_multi_instance.mjs`）

1. `CallService` 不得再声明 `rooms` / `userCall`（判据**先剥注释**）
2. 必须注入 `CallRoomStore`
3. `sendTo` 必须走 `callFrameTopic.broadcast`，且**保留** `sendRawToUser` 兜底
4. Topic 名独立于 `message.topic`
5. 房间 / 反查 key 必须 `expire(TTL_SECONDS…)`（无永久 key）
6. `callId` 冲突回 `CODE_CALL_ID_CONFLICT`；Redis 不可用 `sendCallError(CODE_SYSTEM)`（判定**只看 `handleInvite` 函数体**）
7. `broadcast-signaling` 默认 `true` 且关闭时有 WARN

**探针 7/7 捕获**，逐条如下：

| 探针（变异） | 结果 |
|---|---|
| 退回进程内 `rooms` 表 | 捕获 exit=1 |
| `sendTo` 退回只写本地 ChannelGroup | 捕获 exit=1 |
| 删掉全部 TTL 设置 | 捕获 exit=1 |
| Topic 名退回 `message.topic` | 捕获 exit=1 |
| 删掉 ID 冲突常量及其引用 | 捕获 exit=1 |
| 删掉 Redis 不可用告警分支 | 捕获 exit=1 |
| 广播开关默认关 | 捕获 exit=1 |

## 未运行项（⚠ 不得以上表全绿替代）

> **2026-10-21 状态更新**：下列 **1 / 3 / 4 已在本机实跑补验**（见下节「补验」），
> **2 部分补验**（隔离性冒烟 ≠ 压测），**5 仍为环境所限**。原记录保留以便对照当时的能力边界。

1. ~~**多实例实跑未验证**~~（本机无负载均衡器、无第二个后端实例、Docker daemon 未运行）
   → **2026-10-21 已补验**：直连跨实例 **5/5** + **反例对照 2/2**。
2. **并发一致性未压测**——字段级写的设计意图是避免 lost update，
   但未做多实例并发接听 / 挂断的实测；`evictRoom` 的「先探测存在再删除」在极端并发下
   可能重复写 `call_log`（`RMap#delete` 返回 void，无原子可判），**已知降级，未压测复现**。
   → **2026-10-21 部分补验**：4 路并发**隔离性**冒烟 32/32（不串房 / 不丢帧 / 不乱序），
   但**仍是隔离性冒烟而非压测**，`evictRoom` 的重复写路径**既未复现也未排除**。
3. ~~**外部 LB 分流行为未验证**~~（README 提供的是配置示例）
   → **2026-10-21 已补验**：**6/6**，用**连接级 round-robin TCP 代理**等价模拟；
   ⚠ **真实 Nginx/HAProxy 的粘性与 upgrade 行为仍未验证**。
4. ~~**Redis 不可用路径仅做静态断言**~~（§2.1 第 3 条：静态断言有固有上限）
   → **2026-10-21 已实跑**：**6/6**（`2803` 错误帧 + 到期自动恢复）。
5. **真实 WebRTC 媒体链路**沿用既有结论：需 GUI，沙箱无法覆盖。**仍未覆盖**。

## 补验（2026-10-21）：双实例实跑

> **为什么现在能做**：2026-10-14 交付时本机「无 LB / 无双实例 / Docker daemon 未运行」；
> 本轮 Docker daemon 已可用、MySQL/Redis 在跑，于是**起了两个后端实例**
> （HTTP 5050/5051 与 5055/5056，**共用同一 MySQL + 同一 Redis**），
> 把上述未运行项 1/3/4 真正跑掉，并把工具沉淀进仓库。

### 形态

| 角色 | 落点 |
|---|---|
| 实例 A | `java -jar easychat-1.0.jar --server.port=5050 --ws.port=5051` |
| 实例 B | `java -jar easychat-1.0.jar --server.port=5055 --ws.port=5056` |
| 共享 | 同一 MySQL `easychat` 库 + 同一 Redis（房间唯一真源） |
| 客户端 A | 连实例 A 的 WS 5051 |
| 客户端 B | 连实例 B 的 WS 5056 |
| LB | 脚本自带**连接级 round-robin TCP 代理**（HTTP 8088 → 5050\|5055，WS 8099 → 5051\|5056） |

### 实际执行与结果

| 项 | 命令 | 结果 |
|---|---|---|
| 直连跨实例 | `node scripts/smoke/multi-instance-smoke.mjs` | **5 PASS / 0 FAIL**（INVITE / JOIN / SIGNAL×2 / HANGUP 全部跨实例送达） |
| **反例对照** | 实例 A 加 `--easychat.call.broadcast-signaling=false` 后同上并带 `--control` | **2 PASS / 0 FAIL**（INVITE **送不到**，且房间**仍已建在 Redis**：`EXISTS → :1`） |
| LB 分流 | `node scripts/smoke/multi-instance-lb.mjs` | **6 PASS / 0 FAIL**（分发记录 `[5051, 5056]`，两客户端**确在不同实例**） |
| Redis 不可用 | `node scripts/smoke/multi-instance-redis-down.mjs` | **6 PASS / 0 FAIL**（`2803`「通话服务暂时不可用，请稍后重试」；实测延迟 **28319ms**；到期自动恢复 45017ms） |
| 4 路并发隔离 | `node scripts/smoke/multi-instance-concurrency.mjs` | **32 PASS / 0 FAIL**（不串房 / 50 帧全到 / seq 完整 / 顺序不乱） |
| 落库核对 | `SELECT * FROM call_log ORDER BY id DESC` | 4 路并发各 1 行 `status=1(已接)`、`participant_count=2` |

### 判别力（为什么这些「通过」不是摆设）

- **正例 + 反例成对**：正例「INVITE 送达」通过、且反例「INVITE 送不到」也通过
  → 该断言**真的在测跨实例投递**，不是恒真。
- **反例里额外断言「房间已建在 Redis」**：否则「送不到」也可能是「服务端压根没处理这次呼叫」，
  那样反例就没有区分力。
- **LB 用例先断言「两客户端落在不同实例」**：不断言这条，「五步全通过」可能只是两个客户端
  挤在同一个实例上（**假阳性**）。
- **Redis 用例先断言「Redis 已被真正阻塞」**（PING 1.2s 内无响应）：不断言这条，
  「收到 2803」可能根本没经历故障。
- **退出码三态**：`0` 符合预期 / `1` 断言失败 / `2` 环境前提不成立 —— **`2` 不得被当成通过**。

### 顺带查出并已登记的问题

| 现象 | 根因 | 落点 |
|---|---|---|
| Redis 不可用时 `2803` **约 28s** 才到（实测 28034 / 28311 / 28319ms 三次） | `CallRoomStore` 走 Redisson，而 `RedisConfig#redissonClient` **未显式配 `timeout`**（走默认约 30s）；`spring.redis.timeout=2000` 只管 Jedis/`RedisTemplate`，**管不到 Redisson** | 遗留 **#35**（`docs/system-facts.md` §14），本批**只登记不改代码**（取值需人工决策：设太小会误杀正常的高延迟 Redis 往返，且 Redisson 是全局客户端） |
| 文档中「未实跑验证」的陈述已过期 | README §1/§4.7、compose 注释、`system-facts` §1.1 三处均按「未验证」措辞 | 已随本次补验同步订正（2026-10-21） |

## 证据

- 全量输出：`C:\Users\7even\AppData\Local\Temp\opencode\mvn_fix.txt`
  （`Tests run: 968, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`）
- 探针输出：`C:\Users\7even\AppData\Local\Temp\opencode\probe_cmi_out5.txt`
  （7 捕获 + `还原确认：字节一致 = true`）
- 门禁输出：`C:\Users\7even\AppData\Local\Temp\opencode\cmi3.txt`（exit 0）
- 提交：`a0b216f` → `61e59b4`
- **补验（2026-10-21）**：四个脚本已**入库**（`scripts/smoke/`），**可重跑复现** ——
  唯一前置是「起两个实例 + 执行种子 SQL」，命令见 `scripts/smoke/README.md`；
  各次实测的关键数字已誊写进上表（5/5、2/2、6/6、6/6、32/32；延迟 28319ms；恢复 45017ms）。
- **补验的落库证据**：`call_log` 中 4 路并发各 1 行 `caller_id=U9000000000X / peer_id=…`、
  `status=1(已接)`、`participant_count=2`；另有 1 行 `status=2(未接)`、`peer_id=NULL`、
  `participant_count=1` —— 那是**反例对照**里被遗弃的房间经 **Redis TTL 回收**后补记的未接，
  与「房间残留由 TTL 最终回收」的设计一致（非缺陷）。

## 过程事故（已修复，登记以免再次发生）

跑门禁批量时 `mutation_chat_dispatch.cjs` 把 `ChatMessage.vue` 的
`v-if="data.status == 0"` 改写为 `v-if="false"` 后**未还原**，导致前端全量测试
一度显示 785/786（**不是**产品缺陷，是变异残留）。该脚本的缺陷已修两处：

1. 基线自检失败时**立即中止**（原代码只设 `exitCode=1` 就继续跑探针，
   并把污染后的文件当作 pristine 写回 —— 等于固化污染）；
2. `finally` 中**校验还原后的字节**与基线是否一致，不一致即重写并报 `[致命]`。

反例验证：人为污染基线后跑脚本 → 输出 `[致命] 基线测试就没通过` 且**不再跑任何探针**。
修复后前端全量 **786/786 全绿**，工作区干净。

## 结论

**单实例回归 + 门禁判别力：通过**（后端 968、前端 786、31 道本地门禁 0 失败）。
**多实例能力：2026-10-21 已实跑验证**（直连 5/5、反例对照 2/2、LB 分流 6/6、
Redis 不可用 6/6、4 路并发隔离 32/32；工具见 `scripts/smoke/`）。

两者仍**分列**，但分列的理由已变：单实例全绿证明「**不回退**」，
双实例实跑证明「**跨实例可用**」—— 后者**不覆盖**真实 Nginx/HAProxy 形态、
WebRTC 媒体链路与容量上限（未运行项 2 的极端并发重复写、5 的媒体链路）。

**遗留**：Redis 不可用时失败反馈约 28s（`docs/system-facts.md` §14 **#35**），
本批**只登记不改代码**（Redisson `timeout` 取值需人工决策）。
