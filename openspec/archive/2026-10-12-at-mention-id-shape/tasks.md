# Tasks — @ 提及 ID 形态收紧

- 关联 Design: 2026-10-12-at-mention-id-shape/design.md
- 创建日期: 2026-10-12
- 预估总工时: 4h

## 阶段一：前端正则收紧（先红）

- [x] **[TDD]** 1.1 在 `at-mention-core.spec.js` 增加反例用例：`@Ubuntu`/`@User`/`@Usernames` 提取为空、
      `@U01234567890abc` 提取为空 — ≤30min
- [x] **[TDD]** 1.2 增加守护用例：`@Urobot` **仍能**提取（防 ADR-001 回归）、
      「选中文本后接 @」场景不漏掉（钉住 ADR-002 取舍）— ≤30min
- [x] 1.3 跑红确认（应恰好是 1.1 的用例转绿前为红）— ≤10min

## 阶段二：后端靓号字符集收口

- [x] **[TDD]** 2.1 `UserInfoBeautyServiceImplTest` 增加用例：含字母/空/超长靓号 → `CODE_1001` 且不落库 — ≤1h
- [x] **[TDD]** 2.2 `saveAccount` 实现校验（放在唯一性查询之前）— ≤30min
- [x] 2.3 后端 `mvn test` 全量绿 — ≤30min

## 阶段三：前端实现

- [x] **[TDD]** 3.1 收紧 `AT_USER_ID_PATTERN` 为 `@(U\d+|Urobot)(?![\w])` — ≤20min
- [x] 3.2 前端 `npm run test` 全量绿 + 变异验证（放宽回 `[A-Za-z0-9]+` 应转红）— ≤30min

## 阶段四：文档订正

- [x] 4.1 订正 `atMentionCore.mjs` 注释（删「跨端契约」、删错误的 `buntu`、补真实字符集与取舍理由）— ≤20min
- [x] 4.2 订正 `MessageSend.vue:346/480` 的不实描述（服务端不解析正文、无红点提醒）— ≤10min

## 阶段五：门禁与验证

- [x] 5.1 `verify_at_mention_core.mjs` 增加反例断言（`@Ubuntu`/`@User`/`@Urobot`），并含空转自检 — ≤30min
- [x] 5.2 全量验证：前端 vitest + build + eslint；后端 mvn test；全部 verify 门禁 — ≤40min
- [x] 5.3 变异验证：`AT_USER_ID_PATTERN` 放宽 → 门禁与 vitest 均须转红 — ≤20min

## 阶段六：收尾

- [x] 6.1 回写 `docs/system-facts.md` §14 #19（删行，按纪律「修复后必须删行」）— ≤10min
- [x] 6.2 spec-delta 回写 `openspec/specs/` + 归档 Change — ≤20min
- [x] 6.3 写 `engineering/qa/` 与 `engineering/retro/` — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `tasks.md` 全部勾选
- [x] 前端 vitest 全绿 + `npm run build` exit 0 + eslint 0 error
- [x] 后端 `mvn test` 全绿
- [x] 全部 `scripts/verify/*.mjs` exit 0
- [x] 变异验证已实跑并记录转红的**具体用例名**
- [x] 归档闭环完成（spec-delta 回写 `specs/` + `git mv` 到 `archive/`）