# Tasks — 接通认证入口的 DTO 字段校验

- 关联 Design: 2026-10-12-account-dto-validation/design.md
- 创建日期: 2026-10-12
- 预估总工时: 3h

## 阶段一：DTO 与签名收敛

- [x] **[TDD]** 1.1 新增 `UserResetPasswordDTO`（`email`/`code`/`newPassword` + `@NotEmpty` + `@Pattern`），
      并加一个「DTO 自身约束有效」的用例（先红）— ≤40min
- [x] **[TDD]** 1.2 改 `AccountController#resetPassword` 签名为接收该 DTO —
      **刻意让现有调用点逐一编译失败**并全部修复（`GlobalOperationAspectTest` 的
      `getMethod(...)`、MockMvc 测试）— ≤40min

## 阶段二：接线 @Valid（红→绿）

- [x] **[TDD]** 2.1 login 非法邮箱 → 1001 + 「邮箱格式不正确」，且不调 Service — ≤30min
- [x] **[TDD]** 2.2 register 空昵称 → 1001 + 「昵称不能为空」，且不调 Service — ≤20min
- [x] **[TDD]** 2.3 login 空邮箱 → 1001 + 「邮箱不能为空」 — ≤20min
- [x] **[TDD]** 2.4 resetPassword 弱密码 → 1001 + 「密码格式不正确」；空 code → 「验证码不能为空」 — ≤30min
- [x] **[TDD]** 2.5 多字段同时非法 → 仍返回 1001 且不调 Service — ≤20min
- [x] **[TDD]** 2.6 给 login/register 参数补 `@Valid` — ≤10min

## 阶段三：契约不变断言

- [x] **[TDD]** 3.1 断言请求参数名不变（`email`/`code`/`newPassword` 仍能正确绑定）— ≤20min
- [x] **[TDD]** 3.2 断言类级 `@Validated` 仍让其它 `@NotNull` 参数继续生效（不得误伤）— ≤20min
- [x] 3.3 确认 `check-api-contract.mjs` 仍 exit 0（路由无变化）— ≤10min

## 阶段四：验证与收尾

- [x] 4.1 变异验证：去掉 `@Valid` → 新增用例转红；`@Pattern` 正则改松 → 弱密码用例转红 — ≤30min
- [x] 4.2 后端 `mvn test` 全量绿 + 全部 verify 门禁 exit 0 — ≤30min
- [x] 4.3 回写 `docs/system-facts.md`（登记 §3.6 偏差：靓号端点仍收 PO）— ≤10min
- [x] 4.4 spec-delta 回写 `openspec/specs/` + 归档 Change — ≤20min
- [x] 4.5 写 `engineering/qa/` 与 `engineering/retro/` — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `tasks.md` 全部勾选
- [x] 后端 `mvn test` 全量 0 失败
- [x] 全部 `scripts/verify/*.mjs` exit 0（含 `check-api-contract.mjs`）
- [x] 变异验证已实跑并记录转红的**具体用例名**
- [x] 归档闭环完成（spec-delta 回写 `specs/` + `git mv` 到 `archive/`）