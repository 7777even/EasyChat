# Tasks — 后端通话支持多实例部署

- 关联 Design: `2026-10-14-call-multi-instance/design.md`
- 创建日期: 2026-10-14
- 预估总工时: **21h**
- 效率等级: **L4**

## 分层一：通话信令广播（C2，独立可验证）

- [x] **T1** **[TDD]** 抽纯核心 `CallRoomCore`（创建时冲突检测 / 成员状态转移 / 人数上限），
      先写失败测试再实现；判定**不得**碰 Redis — ≤2h
      → 13 用例（冲突 / TTL / 终态 / 上限）
- [x] **T2** **[TDD]** 实现 `CallFrameTopic`：`{targetUserId, frameJson}` 载荷 + `@PostConstruct` 监听，
      各实例经 `sendRawToUser` 本地投递。测试须证明「**广播出去的消息本实例消费到了**」 — ≤3h
      → 6 纯逻辑用例 + 4 接线用例（`CallFrameTopicWiringTest`）
      ⚠ 途中两处适配：fastjson 1.x 不支持 Java `record`（改 POJO）；
      `subscribe()` 对 `getTopic()==null` 容错（集成测试的 redisson 是 mock）
- [x] **T3** `CallService.sendTo` 改走广播（**保留 `sendRawToUser` 作为本地降级路径**）。 — ≤1h
      → `broadcast()` 返回 false 或开关关闭时退回本地直投
- [x] **T4** 配置开关 `easychat.call.broadcast-signaling`（默认开启，便于紧急回退） — ≤1h
      → 关闭时启动打 WARN

## 分层二：房间注册表迁 Redis（C1）

- [x] **T5** **[TDD]** 房间 Hash / 成员子 Map / `userCall` 反查的读写封装（含 **TTL**）— ≤3h
      → `CallRoomStore` 14 用例（HSETNX / TTL / 字段级 HSET）
- [x] **T6** `CallService` 改为「Redis 为唯一真源、进程内仅作写路径缓冲」；
      关键路径全部走 T1 的纯核心判定 — ≤3h
      → `rooms` / `userCall` 两个进程内 Map **已删除**
- [x] **T7** `callId` 冲突检测：已存在则**拒绝 + WARN**，绝不静默合并（ADR-003） — ≤1h
      → `CallIdConflictTest` 2 用例，含 `verify(store, never()).putMember`

## 分层三：跨实例收敛与可观测（C3）

- [x] ~~**T8** `onUserDisconnect` 广播断开事件，各实例清理本地状态 — ≤2h~~
      **→ 计划变更：不实现（2026-10-14 执行时判定）**
      T6 删掉 `rooms` / `userCall` 后 `CallService` **已无任何本地状态可清理**，
      跨实例断开经 `callRoomStore.callIdOf()`（Redis）→ `roomOf()` → `endCall()` 自然收敛。
      照原计划实现 = 为不存在的问题加广播复杂度。依据 §2.1 第 12 条改计划。
      `spec-delta` 的 `call-disconnect-convergence` 已同步修订，本条视为**已闭环**。
- [x] **T9** 启动自检：广播开关关闭时打显著 WARN（多实例将不可用）— ≤1h
      → 另按 ADR-002 补强：**Redis 不可用**时 `sendCallError(CODE_SYSTEM=2803)`，不再静默
- [x] **T10** 新增门禁 `verify_call_multi_instance.mjs`：断言 `sendTo` 不再直连本地投递、
      房间 key 带 TTL、`callId` 冲突路径有覆盖、广播开关存在 — ≤2h
      → 7 类不变量 + 防空转自检；已入 CI + `AGENTS.md` §10

## 阶段四：验证与收尾

- [x] **T11** `mvn test` 全量 0 失败、既有 929 零回归 — ≤1h
      → **968 全绿**（929 + 本 Change 新增 39），BUILD SUCCESS
- [x] **T12** 探针逐条证明新门禁有判别力（至少 6 条） — ≤1h
      → **7/7 捕获**，0 漏网 0 无效；源码字节一致还原已确认
- [x] **T13** `engineering/qa/2026-10-14-call-multi-instance.md`：
      ⚠ **未运行项必须如实登记** —— 多实例实跑（无 LB）、并发一致性未压测 — ≤1h
- [x] **T14** `engineering/retro/2026-10-14-call-multi-instance.md` — ≤30min
- [x] **T15** spec-delta 回写 `openspec/specs/call-multi-instance/`（新建 capability）
      + **订正 `docs/system-facts.md` §1.1 那句错误判断** + `git mv` 归档 — ≤1h
      → 另订正两处 `CallRoomRegistry`（该类从未存在，`git log -S` 0 条）
- [x] **T16** compose 注释与 README 补充「多实例需外部 LB」的部署说明 — ≤1h

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] `mvn test` 0 失败、既有 929 零回归（**968 全绿**）
- [x] 新门禁探针**均证明有判别力**（T12：7/7 捕获）
- [x] **未运行项如实登记**：不得把「单实例回归通过」写成「多实例已验证」
      → QA §未运行项：多实例实跑、并发一致性压测、LB 分流
- [x] `docs/system-facts.md` §1.1 错误判断已订正
- [x] 归档闭环完成

## 本 Change 范围外（明确不移交）

- **负载均衡器本身不在仓库交付**（需外部 Nginx/HAProxy，仅提供配置示例与验收步骤）。
- **不改 `call_log` 表**。
- **不做多实例下的 iceServers 一致性**（当前各实例各自签发，凭据均为同一共享密钥，
  故实际一致；若将来改为「按实例轮换密钥」再议）。
- **不改 `ChannelContextUtils.USER_CONTEXT_MAP`**（WS 建连层的进程内映射，
  决定「某用户连接挂在哪个实例」，非本 Change 可解）。
