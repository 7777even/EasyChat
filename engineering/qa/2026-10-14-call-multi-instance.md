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

1. **多实例实跑未验证**——本机无负载均衡器、无第二个后端实例、Docker daemon 未运行。
   「A 实例发起 → B 实例收到」这条**真正的验收路径没有跑过**。
   上表全部是**单实例 + 单元/接线测试**，它只能证明「不回退」，**不能证明跨实例可用**。
2. **并发一致性未压测**——字段级写的设计意图是避免 lost update，
   但未做多实例并发接听 / 挂断的实测；`evictRoom` 的「先探测存在再删除」在极端并发下
   可能重复写 `call_log`（`RMap#delete` 返回 void，无原子可判），**已知降级，未压测复现**。
3. **外部 LB 分流行为未验证**——README 提供的是配置示例，
   Nginx/HAProxy 的实际会话粘性与 WebSocket upgrade 均未在本机验证。
4. **Redis 不可用路径仅做静态断言**——门禁验证的是「代码里存在 `sendCallError` 分支」，
   未在运行时真停 Redis 观察客户端收到的错误帧（§2.1 第 3 条：静态断言有固有上限）。
5. **真实 WebRTC 媒体链路**沿用既有结论：需 GUI，沙箱无法覆盖。

## 证据

- 全量输出：`C:\Users\7even\AppData\Local\Temp\opencode\mvn_fix.txt`
  （`Tests run: 968, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`）
- 探针输出：`C:\Users\7even\AppData\Local\Temp\opencode\probe_cmi_out5.txt`
  （7 捕获 + `还原确认：字节一致 = true`）
- 门禁输出：`C:\Users\7even\AppData\Local\Temp\opencode\cmi3.txt`（exit 0）
- 提交：`a0b216f` → `61e59b4`

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
**多实例能力：未验证**（未运行项 1–3）。
两者在本文件中分列，禁止合并表述为「多实例通话已支持」。
交付范围内的代码与门禁完成；能力的最终确认需外部 LB + 双实例环境。
