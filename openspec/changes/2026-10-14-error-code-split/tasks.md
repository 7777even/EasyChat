# Tasks — 拆分被复用的通用错误码

- 关联 Design: `2026-10-14-error-code-split/design.md`
- 创建日期: 2026-10-14
- 预估总工时: **7h**
- 效率等级: **L4**（`ResponseCodeEnum` 错误码语义变更）

## 阶段一：后端（新增码 + 替换调用点）

- [x] **** **[TDD]** `ResponseCodeEnum` 新增 `CODE_1004` / `CODE_1005`；
      先写断言（两码存在、值落在通用段 `1000-1999`、文案非空且互不相同）让测试跑红 — ≤1h
- [x] **** **[TDD]** `GlobalOperationAspect#applyRateLimit` 改抛 `CODE_1004`。
      ⚠ 必须用**行为**判据（打满限流 → 响应 `code == 1004`），
      **不得**退回断言中文文案 —— 那正是本次要消灭的脆弱断言 — ≤2h
- [x] **** **[TDD]** `AccountController#validateCheckCode` 两处改抛 `CODE_1005`（过期/不正确），
      配**制衡用例**：验证码正确时不得抛该码 — ≤1h

## 阶段二：前端（让新码真正被用上）

- [x] **** **[TDD]** `Request.js`：`ErrorCode` 枚举同步 1004/1005；
      拒绝对象带上 `code` 与（限流时）`rateLimitSeconds`；对 1004/1005 做分支。
      ⚠ 必须验证**向后兼容**：老后端只回 1001 时，行为与现在完全一致 — ≤2h
- [x] **** `Login.vue`：收到 1004 → 启动倒计时并禁用「获取验证码」；
      收到 1005 → `clearVerify()` + 聚焦验证码输入框（**不自动重发**，见 ADR-003） — ≤1h

## 阶段三：门禁（锁住分段与同步，防止再漂移）

- [x] **** 新增 `scripts/verify/verify_error_code.mjs`（ADR-004）：
      ① 每个码落在 `ResponseCodeEnum` javadoc 声明的段内；
      ② 前后端枚举**双向对账**；
      ③ 新增码在**前端确实被使用**（防止再现「定义了从未使用」）；
      ④ 客户端 `RATE_LIMIT_SECONDS` 与服务端 `RATE_LIMIT_WINDOW_SECONDS` **相等**（ADR-002）。
      **必须造探针逐条证明有判别力** — ≤1.5h
- [x] **** 接入 CI `gates` job + 登记 `AGENTS.md` §10 + 更新 §3.1 错误码分段表 — ≤30min

## 阶段四：验证与收尾

- [x] **** `mvn test` 全量 0 失败、既有测试零回归；`npm run test` / `npm run lint` 0 error — ≤1h
- [x] **** `engineering/qa/2026-10-14-error-code-split.md`：
      未运行项如实登记（如：浏览器端真实倒计时效果无 GUI 无法实测） — ≤30min
- [ ] **T10** `engineering/retro/2026-10-14-error-code-split.md` — ≤30min
- [ ] **T11** spec-delta 回写 `openspec/specs/error-code/`（新建 capability）
      + `git mv` 归档 — ≤30min

## DoD 自检

- [ ] `tasks.md` 全部勾选
- [ ] `mvn test` / `npm run test` / `npm run lint` 均 0
- [ ] 新增断言**均经探针证明有判别力**（T6）
- [ ] **向后兼容已实测**：老后端回 1001 时前端行为不变（T4）
- [ ] `AGENTS.md` §3.1 与 §10、`docs/system-facts.md` 变更日志已同步
- [ ] 归档闭环完成

## 本 Change 范围外（**不移交，因为有意不做**）

- **不**对其余 90+ 处业务校验做全量归类（ADR-001，已在人工确认关卡获用户采纳）。
- **不**改进限流算法本身（固定窗口 → 滑动窗口/令牌桶），仅让客户端知道「要等」。
