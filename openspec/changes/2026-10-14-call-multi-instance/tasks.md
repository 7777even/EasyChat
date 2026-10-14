# Tasks — 后端通话支持多实例部署

- 关联 Design: `2026-10-14-call-multi-instance/design.md`
- 创建日期: 2026-10-14
- 预估总工时: **21h**
- 效率等级: **L4**

## 分层一：通话信令广播（C2，独立可验证）

- [ ] **T1** **[TDD]** 抽纯核心 `CallRoomCore`（创建时冲突检测 / 成员状态转移 / 人数上限），
      先写失败测试再实现；判定**不得**碰 Redis — ≤2h
- [ ] **T2** **[TDD]** 实现 `CallFrameTopic`：`{targetUserId, frameJson}` 载荷 + `@PostConstruct` 监听，
      各实例经 `sendRawToUser` 本地投递。测试须证明「**广播出去的消息本实例消费到了**」 — ≤3h
- [ ] **T3** `CallService.sendTo` 改走广播（**保留 `sendRawToUser` 作为本地降级路径**）。 — ≤1h
- [ ] **T4** 配置开关 `easychat.call.broadcast-signaling`（默认开启，便于紧急回退） — ≤1h

## 分层二：房间注册表迁 Redis（C1）

- [ ] **T5** **[TDD]** 房间 Hash / 成员子 Map / `userCall` 反查的读写封装（含 **TTL**）— ≤3h
- [ ] **T6** `CallService` 改为「Redis 为唯一真源、进程内仅作写路径缓冲」；
      关键路径全部走 T1 的纯核心判定 — ≤3h
- [ ] **T7** `callId` 冲突检测：已存在则**拒绝 + WARN**，绝不静默合并（ADR-003） — ≤1h

## 分层三：跨实例收敛与可观测（C3）

- [ ] **T8** `onUserDisconnect` 广播断开事件，各实例清理本地状态 — ≤2h
- [ ] **T9** 启动自检：广播开关关闭时打显著 WARN（多实例将不可用）— ≤1h
- [ ] **T10** 新增门禁 `verify_call_multi_instance.mjs`：断言 `sendTo` 不再直连本地投递、
      房间 key 带 TTL、`callId` 冲突路径有覆盖、广播开关存在 — ≤2h

## 阶段四：验证与收尾

- [ ] **T11** `mvn test` 全量 0 失败、既有 929 零回归 — ≤1h
- [ ] **T12** 探针逐条证明新门禁有判别力（至少 6 条） — ≤1h
- [ ] **T13** `engineering/qa/2026-10-14-call-multi-instance.md`：
      ⚠ **未运行项必须如实登记** —— 多实例实跑（无 LB）、并发一致性未压测 — ≤1h
- [ ] **T14** `engineering/retro/2026-10-14-call-multi-instance.md` — ≤30min
- [ ] **T15** spec-delta 回写 `openspec/specs/call-multi-instance/`（新建 capability）
      + **订正 `docs/system-facts.md` §1.1 那句错误判断** + `git mv` 归档 — ≤1h
- [ ] **T16** compose 注释与 README 补充「多实例需外部 LB」的部署说明 — ≤1h

## DoD 自检

- [ ] `tasks.md` 全部勾选
- [ ] `mvn test` 0 失败、既有 929 零回归
- [ ] 新门禁探针**均证明有判别力**（T12）
- [ ] **未运行项如实登记**：不得把「单实例回归通过」写成「多实例已验证」
- [ ] `docs/system-facts.md` §1.1 错误判断已订正
- [ ] 归档闭环完成

## 本 Change 范围外（明确不移交）

- **负载均衡器本身不在仓库交付**（需外部 Nginx/HAProxy，仅提供配置示例与验收步骤）。
- **不改 `call_log` 表**。
- **不做多实例下的 iceServers 一致性**（当前各实例各自签发，凭据均为同一共享密钥，
  故实际一致；若将来改为「按实例轮换密钥」再议）。
