# Tasks — 靓号保存端点改收 DTO

- 关联 Design: 2026-10-13-beauty-account-dto/design.md
- 创建日期: 2026-10-13
- 预估总工时: 1.5h

## 阶段一：四件套

- [x] 1.1 proposal / design / spec-delta / tasks + `.openspec.yaml` — ≤30min

## 阶段二：TDD

- [x] **[TDD]** 2.1 建 `AdminUserInfoBeautyControllerMockMvcTest` 骨架（`standaloneSetup`），
      **先写红**：`saveBeautAccount` 空邮箱 / 非法邮箱格式 → 应返回 `1001` — ≤25min
- [x] **[TDD]** 2.2 `email` 为空、`userId` 为空、邮箱格式非法 三条约束逐条转绿 — ≤25min
- [x] **[TDD]** 2.3 合法请求 → 落库对象四字段**逐字段**与入参一致 — ≤20min
- [x] 2.4 `id` 为空走新建分支、非空走编辑分支（DTO 不改变分流语义）— ≤15min
- [x] 2.5 变异：去掉 DTO 上的 `@Valid` → 对应用例必须转红 — ≤15min
- [x] 2.6 变异：把 `@Email` 注解去掉 → 「邮箱格式非法」用例必须转红 — ≤15min

## 阶段三：实现

- [x] 3.1 建 `UserInfoBeautySaveDTO`（4 字段 + 中文注释 + `@Schema`）— ≤20min
- [x] 3.2 Controller 入参改 DTO + `@Valid`，方法体内转 PO（ADR-001）— ≤15min

## 阶段四：验证

- [x] 4.1 `mvn test` 全绿（新增用例全过，`UserInfoBeautyServiceImplTest` 13 例零改动）— ≤20min
- [x] 4.2 `node scripts/check-api-contract.mjs --strict` exit 0（前端零改动的证据）— ≤10min
- [x] 4.3 前端 `npx vitest run src/renderer/src/__tests__/beauty-account-edit-mount.spec.js` 仍绿 — ≤10min
- [x] 4.4 全量 `scripts/verify/*.mjs` exit 0 — ≤20min

## 阶段五：收尾

- [x] 5.1 回写台账 #31（落点列的偏差登记改为已闭环）— ≤10min
- [x] 5.2 spec 回写 — ≤15min
- [x] 5.3 归档 + QA / Retro — ≤30min

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] **Service 层零改动**（`UserInfoBeautyServiceImpl` 与其 13 例测试 diff 为空）
- [x] **前端零改动**（`easychat-front/` 下 diff 为空）
- [x] **数据库零改动**（`easychat.sql` 与迁移脚本 diff 为空）
- [x] `mvn test` 全绿
- [x] `check-api-contract.mjs --strict` exit 0
- [x] 两处变异均转红，且记录转红的具体用例名
- [x] 归档闭环完成