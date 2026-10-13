# Tasks — 邮箱验证码失败次数限制（遗留 #1 根治）

- 关联 Design: 2026-10-13-email-code-failure-limit/design.md
- 创建日期: 2026-10-13
- 预估总工时: 2h

## 阶段一：四件套

- [x] 1.1 proposal / design / spec-delta / tasks + `.openspec.yaml` — ≤30min

## 阶段二：TDD

- [x] **[TDD]** 2.1 在 `UserInfoServiceImplTest` 补 `resetPasswordByEmail` 的失败计数用例，
      **先写红**（生产代码尚无计数逻辑）— ≤25min
- [x] **[TDD]** 2.2 猜错 → `redisUtils.incr` 被调用且带正确键前缀 — ≤15min
- [x] **[TDD]** 2.3 第 5 次猜错后 → 第 6 次请求**在查库之前**即被拒（`selectList` never）— ≤20min
- [x] **[TDD]** 2.4 成功验证 → `redisUtils.delete` 被调用（清零）— ≤15min
- [x] **[TDD]** 2.5 重发新码不重置计数（断言 `sendEmailCode` **不**调 delete）— ≤15min
- [x] **[TDD]** 2.6 码**已过期** → **不**计数（`incr` never）— ≤15min
- [x] **[TDD]** 2.7 用户不存在 → **不**计数（`incr` never）— ≤15min
- [x] 2.8 Redis 返回 `null` → fail-open 放行 — ≤10min
- [x] 2.9 阈值 / 窗口从 `Constants` 取，测试**不写**魔法数字 — ≤10min

## 阶段三：实现

- [x] 3.1 `Constants` 加 `REDIS_KEY_EMAIL_CODE_FAIL` / `EMAIL_CODE_FAIL_MAX`(=5)
      / `EMAIL_CODE_FAIL_WINDOW_SECONDS`(=600) — ≤10min
- [x] 3.2 `UserInfoServiceImpl` 注入 `RedisUtils` — ≤5min
- [x] 3.3 实现 `isEmailCodeLocked` / `recordEmailCodeFailure` / `clearEmailCodeFailure`
      三个私有方法（**每次只加签名 + 抛桩**，让测试编译通过并跑红，再填实现 ——
      AGENTS §2.1 第 5 条）— ≤25min
- [x] 3.4 接线到 `resetPasswordByEmail` 三处（锁定检查在前、猜错处计数、成功处清零）— ≤20min

## 阶段四：变异验证

- [x] 4.1 变异：把 `EMAIL_CODE_FAIL_MAX` 由 5 改成 50 → 「第 6 次被拒」用例必须转红 — ≤15min
- [x] 4.2 变异：去掉「过期不计数」的判断 → 「码过期不计数」用例必须转红 — ≤15min
- [x] 4.3 变异：把锁定检查移到 DB 查询**之后** → 「查库前拒绝」用例必须转红 — ≤15min
- [x] 4.4 变异：去掉成功后的 `clearEmailCodeFailure` → 「成功清零」用例必须转红 — ≤15min

## 阶段五：验证

- [x] 5.1 `mvn test` 全绿（后端用例数应 > 873）— ≤20min
- [x] 5.2 全量 `scripts/verify/*.mjs` exit 0 — ≤20min
- [x] 5.3 `check-api-contract.mjs --strict` exit 0（契约未变的证据）— ≤10min

## 阶段六：收尾

- [x] 6.1 回写台账 #1（残余风险 → 已闭环；**同时修正「可换邮箱」的措辞**）— ≤15min
- [x] 6.2 spec 回写（新建 capability 或并入 `password-bcrypt` / `operation-log`）— ≤20min
- [x] 6.3 归档 + QA / Retro — ≤30min

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] **数据库零改动**：`easychat.sql` 与 `easychat-migration-*.sql` 的 diff 为空
- [x] **前端零改动**
- [x] **接口契约零变化**：路径 / 方法 / 参数名不变；三条既有错误文案未被改写
- [x] **零新依赖**：只注入既有 `RedisUtils` Bean，`pom.xml` diff 为空
- [x] `mvn test` 全绿
- [x] 四处变异均转红，且记录转红的具体用例名
- [x] **spec 的「已知边界」两条已写明**（可锁死受害者 / Redis 丢失清零）
- [x] 归档闭环完成