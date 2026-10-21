# QA — 通话结束权原子化：消除「同一通话记两条 call_log」

- 日期: 2026-10-21
- 效率等级: **L2**（局部正确性修正；不改接口契约 / 权限语义 / 数据模型 / WS 帧协议 / 数据库结构）
- 范围: `CallRoomStore#evictRoom` 的结束权抢占方式、`CallService#endCall` 的调用、
  新增端到端竞态冒烟 `scripts/smoke/multi-instance-hangup-race.mjs`。
- 配套: `engineering/qa/2026-10-14-call-multi-instance.md` 未运行项 2（本次闭环）

## 起因

`call-multi-instance` 交付时登记过一条未运行项：

> `evictRoom` 的「先探测存在再删除」在极端并发下可能重复写 `call_log`
> （`RMap#delete` 返回 void，无原子可判），**已知降级，未压测复现**。

本轮用双实例实跑**复现**了它，并修掉。

## 验收口径

1. 双方**同时**挂断（分属两实例）时，**同一通话只能落一条 `call_log`**；
2. 结束权抢占必须是**原子**的（并发只有一方拿到 true）；
3. 「已被结束」的一方必须**不得**再删房间、**不得**再落库；
4. 正常路径不回归（正向通话 / LB 分流 / 并发隔离 / Redis 不可用 四道冒烟全绿）。

## 实际执行与结果

| 项 | 命令 | 结果 |
|---|---|---|
| **复现（修复前）** | 15 轮「双方同时挂断」→ 查 `call_log` | **16 行**（重复率 ~7%），重复行 `start_time` 完全相同、`create_time` 差 1ms |
| TDD 红阶段 | `mvn -Dtest=CallRoomStoreTest test` | **2 处如期转红**（`evictRoomClaimsEndRightAtomically` / `evictRoomAlsoClearsMembers`） |
| TDD 绿阶段 | 同上 | 全绿（EXIT=0） |
| 后端全量 | `mvn test` | **978 全绿**（基线 974 + 本轮新增 **4**），BUILD SUCCESS |
| **端到端复验（修复后）** | `node scripts/smoke/multi-instance-hangup-race.mjs` | **2 PASS / 0 FAIL**：20 轮 → `call_log` **20 行**、`distinct start_time` **20** |
| 加大样本 | 同上（`MI_RACE_ROUNDS=40`） | 40 轮 → **40 行 / 40 个不同 start_time**，**零重复** |
| **变异验证（判别力）** | 把 `evictRoom` 改回「get 再 delete」的缺陷写法后重跑 | **如期报红**：21 行 vs 期望 20、**1 组重复**（并打印重复明细）→ 断言**有判别力**；随后 `git checkout` 精确还原，修复版复跑**绿** |
| 其余冒烟 | 直连 / LB / 并发隔离 / Redis 不可用 | **5/5、6/6、32/32、6/6** 全绿，无回归 |

## 根因

```java
// 修复前（两步非原子）
if (map.get("callerId") == null) { return false; }   // 探测
map.delete();                                          // 删除
return true;                                           // → 上层据此写 call_log
```

`get` 与 `delete` 之间存在窗口（两次 Redis 往返，~1ms）。窗口内另一方也探测到「存在」→
双方都删成功、都拿到 true → **各写一条 `call_log`**。

`RMap#delete` 返回 void，所以旧实现无法用「删了之后看之前有没有值」判结束权
（单实例下靠 `rooms.remove(id) == null` 判，迁 Redis 后失效）。

## 修法

```java
// 修复后：一步完成
if (expectedCallerId == null || !map.remove("callerId", expectedCallerId)) { return false; }
map.delete();
memberMap(callId).delete();
return true;
```

- `RMap#remove(key, value)` 在 Redisson 3.12.3 里是**单条 EVAL**（compare-and-delete，**原子**）——
  已用 `javap -c org/redisson/RedissonMap.class` 核实其走 `RedisCommands.EVAL_BOOLEAN`；
- 于是「房间是否仍属于该通话」与「抢占结束权」**合并为一步**：并发只有一方拿到 true；
- 副作用（刻意）：`callerId` 被取走后，`getRoom` / `exists` 即判定「房间不存在」——
  这正是「已结束」的语义。

**与旧取舍的对比（如实说明）**：旧实现刻意选「重复记录可查、悬挂不可查」。本修法把重复降到 0，
代价是「抢占成功但进程在落库前崩溃」时该通话记录会丢 —— 但该窗口与旧实现的
「delete 之后、落库之前」是**同一个**，不是新增类别。

## 未运行项（如实登记）

1. **崩溃窗口的实测未做**（需要精确地在「抢占成功」与「落库」之间杀进程）——
   结论来自代码路径分析（两个窗口同长），非实测。
2. **端到端脚本不进 CI** —— 它需要「双实例 + 活库 + mysql CLI」，CI 里没有这个形态；
   CI 侧由**单元测试**守住机制（必须用原子 compare-and-delete、不得用 get-then-delete），
   端到端由本脚本人工守。
3. **并发度未压测**：本轮是 20~40 轮「双方同时挂断」的**竞态**复现，不是吞吐压测；
   更大并发下的表现未测。
4. `CallRoomStore#evict(String)`（盲删）**无生产调用方**（仅测试用）—— 疑似死代码，
   本轮未动（清理属独立事项）。

## 证据

- 复现：15 轮 → `call_log` **16 行**，其中 `start_time=1792566377386` 出现两次、`create_time` 差 1ms。
- 修复后：40 轮 → 40 行 / 40 个不同 `start_time`；`GROUP BY start_time HAVING COUNT(*)>1` **无输出**。
- 变异验证：缺陷写法 → 脚本 **exit 1**（`21 行 / 1 组重复`）；还原后 → **exit 0**。
- 单元测试：`CallRoomStoreTest` 新增 4 例（含 2 条反向制衡：不得用 `get` 探测、已被结束者不得删房间）。

## 结论

**通过**。同一通话重复落库已消除（端到端 40 轮零重复，变异验证证明断言有判别力），
后端 978 全绿，四道既有冒烟无回归。
