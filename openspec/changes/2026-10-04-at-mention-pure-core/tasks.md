# Tasks — 群聊 @ 提及子系统抽纯决策核心

- 关联 Design: `openspec/changes/2026-10-04-at-mention-pure-core/design.md`
- 创建日期: 2026-10-04
- 预估总工时: 4h

## 阶段零：基线固化

- [ ] **T0.1** 记录基线：`npm run lint` / `npm run test` / `npm run build` exit 0；
      `npm ls vite` 为 `4.5.14`；`dependencies` sha1 — ≤20min
- [ ] **T0.2** 记录 `MessageSend.vue` 三处缺陷的**当前可观测行为**（作为回归对照基线） — ≤20min

## 阶段一：[TDD] 先写失败测试与门禁

- [ ] **T1.1** `at-mention-core.spec.js`：**先按「等价于现状」写**——
      对抽离前的 6 处逻辑逐条断言（含三处缺陷的现状行为），
      此时核心尚不存在 → 必须先补一个**只有签名 + `throw new UnsupportedOperationException()` 的桩**，
      编译通过并跑红 — ≤50min
      > 纪律（AGENTS §2.1 第 5 条）：Java 里「测试引用不存在的方法 → 编译失败」
      > 容易让人顺手把实现一起写掉从而跳过红阶段；JS 同理。

- [ ] **T1.2** 红阶段确认：`exit≠0` 且失败点指向**桩的 `UnsupportedOperationException`**，
      而非「模块不存在」— ≤10min
- [ ] **T1.3** `verify_at_mention_core.mjs` 骨架 + 「组件确实复用纯核心」断言（ADR-004，**先剥注释**） — ≤30min
- [ ] **T1.4** 门禁红阶段：核心不存在时门禁**必须红**（否则门禁无判别力） — ≤10min

## 阶段二：抽纯核心（与修缺陷分开提交）

- [ ] **T2.1** `atMentionCore.mjs` 常量与 `canAtAll` / `roleText` — ≤15min
- [ ] **T2.2** `spliceAtText`：输入旧内容 + 光标区间 → 返回 `{content, cursor}`；
      `nextTick` 光标复位**留在组件** — ≤25min
- [ ] **T2.3** `extractAtUserIds`：正则**本轮不动**，只此一份 — ≤15min
- [ ] **T2.4** `buildExtraData` / `buildAtUserIdsField`：委托 `extractAtUserIds` — ≤25min
- [ ] **T2.5** `filterMembers`：先按**现状**（仅 `contactName`）实现，
      使「除三处缺陷外行为等价」可被测试证明 — ≤15min
- [ ] **T2.6** `MessageSend.vue` 改为委托纯核心，**删除组件内判定** — ≤40min
- [ ] **T2.7** `npm run test` 除三处缺陷用例外**全绿**（等价性已证）；`lint` / `build` exit 0 — ≤20min
- [ ] **T2.8** **提交抽离**（不含缺陷修复）— ≤10min

## 阶段三：修三处缺陷（独立提交）

- [ ] **T3.1** [TDD] 先把三处缺陷的**目标行为**写成失败用例（此时会红）— ≤20min
- [ ] **T3.2** `filterMembers` 增 userId 匹配（ADR-002）→ 转绿 — ≤15min
- [ ] **T3.3** `buildExtraData` 的 `atAll` 叠加 `canAtAll(role)`（ADR-001）→ 转绿 — ≤20min
- [ ] **T3.4** `extraData.atUserIds` 改用去重结果（ADR-003）→ 转绿 — ≤10min
- [ ] **T3.5** 覆盖「管理员草稿重发仍带 atAll」与「普通成员手打 @所有人 不带 atAll」— ≤20min
- [ ] **T3.6** `MessageSend.vue` 把 `myGroupRole` 传入 `buildExtraData` — ≤10min
- [ ] **T3.7** `npm run test` / `lint` / `build` 全绿；**提交缺陷修复** — ≤20min

## 阶段四：门禁与变异

- [ ] **T4.1** `verify_at_mention_core.mjs` 补齐全部行为断言 — ≤40min
- [ ] **T4.2** `mutation_at_mention_core.cjs`：基线自检 + `[捕获]`/`[漏网]`/`[无效]` 三态 —
      变异 ① 去掉 userId 匹配 ② 去掉 `canAtAll` 条件 ③ 去掉去重
      ④ 组件内联判定 ⑤ 正则改回复制两份 — **全部捕获** — ≤50min
- [ ] **T4.3** 两个新门禁接入 `ci.yml` 的 `gates` job — ≤15min

## 阶段五：收尾

- [ ] **T5.1** `engineering/qa/2026-10-04-at-mention-pure-core.md`（**UI 改动须附页面截图**） — ≤30min
- [ ] **T5.2** `engineering/retro/2026-10-04-at-mention-pure-core.md` — ≤20min
- [ ] **T5.3** `AGENTS.md` §10 门禁表 + `docs/system-facts.md` §7/§14 与变更日志 — ≤20min
- [ ] **T5.4** spec-delta 回写 `openspec/specs/at-all/spec.md`；`Move-Item` 归档 — ≤20min

## 遗留（本批明确不做）

| 项 | 原因 | 后续 |
|----|------|------|
| 正则 `/@(U[A-Za-z0-9]+)/g` 误命中（`@Ubuntu` → `buntu`） | **跨端契约**，服务端消费同一格式，收紧需前后端协同 + 存量评估 | 独立 Change，须含服务端 `atUserIds` 解析口径对齐 |
| 草稿持久化 `atAllEnabled` | 属草稿功能 | 独立 Change |
| `Chat.vue`（79 条件 / 45KB）抽核心 | 其分派已有源码级门禁 `verify_chat_message_dispatch.mjs` 覆盖 | 待其逻辑复杂度上升后再评估 |

## DoD 自检

- [ ] `tasks.md` 全部勾选
- [ ] **T1.2 / T1.4 红阶段已实跑并留证**
- [ ] **T4.2 五个变异全部被捕获**
- [ ] **抽离与修缺陷分属两个 commit**（否则无法证明等价性）
- [ ] `npm run test` / `npm run lint` / `npm run build` 全绿，`vite` 仍 4.5.14
- [ ] UI 证据（页面截图）已随 QA 归档
- [ ] 归档闭环完成
