# Tasks — 群聊 @ 提及子系统抽纯决策核心

- 关联 Design: `openspec/changes/2026-10-04-at-mention-pure-core/design.md`
- 创建日期: 2026-10-04
- 预估总工时: 4h

## 阶段零：基线固化

- [x] **T0.1** 基线：`npm run lint` / `test` / `build` 全 `exit=0`；
      `vite ^4.4.9`、`electron-vite ^1.0.27`、`dependencies` 21 项 sha1 `ffd67dccd3d93e6a` — ≤20min
- [x] **T0.2** 记录三处缺陷的当前可观测行为（作为 T2 等价性对照） — ≤20min

## 阶段一：[TDD] 先写失败测试与门禁

- [x] **T1.1** `at-mention-core.spec.js` 按「等价于现状」写 27 例 + 核心桩（只有签名 + `throw`） — ≤50min
- [x] **T1.2** 红阶段确认：`exit=1`、**26 失败**、失败点全部指向桩的
      `UnsupportedOperationException`（78 处）；唯一通过的 constants 用例证明模块**确实被解析到**
      （若模块不存在会报 `Failed to resolve`） — ≤10min
- [x] **T1.3** `verify_at_mention_core.mjs` 骨架 + ADR-004「组件确实复用纯核心」断言（**先剥注释**） — ≤30min
- [x] **T1.4** 门禁红阶段：核心不存在时门禁必须红 — ≤10min

> **T1.4 实际做法**：门禁写成**调用核心并断言行为**（`await import` + 实际调用），
> 而非读源码找字样。故核心不存在时门禁在 import 处即失败 —— 天然 fail-closed，
> 无需额外构造「核心不存在」的场景。

## 阶段二：抽纯核心（与修缺陷分开提交）

- [x] **T2.1** 常量 + `canAtAll` / `roleText` — ≤15min
- [x] **T2.2** `spliceAtText`：输入旧内容 + 光标区间 → `{content, cursor}`；`nextTick` 留组件 — ≤25min
- [x] **T2.3** `extractAtUserIds`：正则本轮不动，只此一份 — ≤15min
- [x] **T2.4** `buildExtraData` / `buildAtUserIdsField`：委托 `extractAtUserIds` — ≤25min
- [x] **T2.5** `filterMembers` 按**现状**（仅 `contactName`）实现，使等价性可被证明 — ≤15min
- [x] **T2.6** `MessageSend.vue` 委托纯核心，删除组件内判定
      （含清理已成死变量的 `AT_ALL_ROLE` / `AT_ADMIN_ROLE`） — ≤40min
- [x] **T2.7** `npm run test` 除三处缺陷用例外**全绿**（等价性已证）；`lint` / `build` exit 0；
      `dependencies` sha1 未变 — ≤20min
- [x] **T2.8** **提交抽离** `refactor(renderer): 抽取群聊提及判定纯核心并补等价性测试` — ≤10min

> **T2.7 踩坑（测试写错，非实现错）**：「单聊不写 atUserIds」写成
> `JSON.parse(buildExtraData(...)).atUserIds` → TypeError。
> 根因：`buildExtraData` 在**无任何附加信息**时返回 `null` 而非 `'{}'`，
> 而 `JSON.parse(null)` 得 `null`。**这个坑本轮踩了两次**（第二次在 T3 的权限剥离用例）。
> 已加 `extraOf()` 助手并把成因写在文件顶部。

## 阶段三：修三处缺陷（独立提交）

- [x] **T3.1** [TDD] 先把目标行为写成失败用例 → `exit=1`、**9 失败**，
      精确命中三处缺陷；「群主/管理员写 atAll」等**本就正确**的用例未红（无误伤） — ≤20min
- [x] **T3.2** `filterMembers` 增 userId 匹配（ADR-002）→ 转绿 — ≤15min
- [x] **T3.3** `buildExtraData` 的 `atAll` 叠加 `canAtAll(role)`（ADR-001）→ 转绿 — ≤20min
- [x] **T3.4** `extraData.atUserIds` 改用去重结果（ADR-003）→ 转绿 — ≤10min
- [x] **T3.5** 覆盖「管理员草稿重发仍带 atAll」与「普通成员手打 @所有人 不带 atAll」 — ≤20min
- [x] **T3.6** `MessageSend.vue` 把 `myGroupRole` 传入 `buildExtraData` — ≤10min
- [x] **T3.7** `npm run test` **58/58** / `lint` / `build` 全绿；
      **提交缺陷修复** `fix(renderer): 修复提及搜索口径不一致与所有人标记绕过权限` — ≤20min

> **T3 的 ⚠ 用例处置**：T2 组里锁定缺陷行为的 3 条 ⚠ 用例在修复后必然转红。
> 已**删除**它们并在原位留下「修复前行为是什么」的注释块 —— 留着相反期望的断言
> 会让后来者无法判断哪组才是当前契约。目标行为集中在 T3 组。

## 阶段四：门禁与变异

- [x] **T4.1** `verify_at_mention_core.mjs` 补齐 —— **19 项通过 / 0 失败** — ≤40min
- [x] **T4.2** `mutation_at_mention_core.mjs`：基线自检 + `[捕获]`/`[漏网]`/`[无效]` 三态
      —— **9/9 捕获，漏网 0，无效 0**，`exit=0`，工作区已还原 — ≤50min
- [x] **T4.3** 接入 `ci.yml` `gates` job（门禁调用 19 → **21**），本地复跑 5 个相关门禁全 `exit=0` — ≤15min

> **T4.1 踩坑（门禁踩了我自己写的纪律）**：B6 断言「核心不得含 `axios`」，
> 而核心 JSDoc 里正写着「本模块不 import electron / window / **axios** / store」——
> **把自己的注释判成了违规**，正是 AGENTS §2.1 第 7 条描述的场景。
> 已改为对 `stripComments()` 后的源码做「不得包含」类断言。
> 另加 **B8 自检**：断言剥注释**没剥过头**（`buildExtraData` 仍在），否则 B2~B4 会假绿。

## 阶段五：收尾

- [x] **T5.1** `engineering/qa/2026-10-04-at-mention-pure-core.md` — ≤30min
- [x] **T5.2** `engineering/retro/2026-10-04-at-mention-pure-core.md` — ≤20min
- [x] **T5.3** `AGENTS.md` §10 门禁表 + `docs/system-facts.md` §14（新增遗留 #19）与变更日志 — ≤20min
- [x] **T5.4** spec-delta 回写 `openspec/specs/at-all/spec.md`；`Move-Item` 归档 — ≤20min

## 遗留（本批明确不做）

| 项 | 原因 | 落点 |
|---|------|------|
| 正则 `/@(U[A-Za-z0-9]+)/g` 误命中（`@Ubuntu` → `buntu`） | **跨端契约**，服务端消费同一格式，收紧需前后端协同 + 存量评估 | `docs/system-facts.md` §14 **#19** + spec-delta「已知边界」；**不做假覆盖** |
| 草稿持久化 `atAllEnabled` | 属草稿功能；当前靠「正文含 @所有人」兜底，功能不受影响 | 独立 Change |
| `Chat.vue`（79 条件 / 45KB）抽核心 | 其分派已有源码级门禁 `verify_chat_message_dispatch.mjs` 覆盖 | 待逻辑复杂度上升后再评估 |
| **补录 UI 截图** | 需 GUI + 多人群聊环境，沙箱不可达 | QA「未运行项」已如实登记，**未按通过计** |

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] **T1.2 红阶段已实跑并留证**（红因指向桩的 `UnsupportedOperationException`，非模块解析失败）
- [x] **T4.2 九个变异全部被捕获**（含三条 ADR-004 结构用例）
- [x] **抽离与修缺陷分属两个 commit**（`refactor(renderer)` / `fix(renderer)`），等价性可归因
- [x] `npm run test` **58/58** / `npm run lint` exit 0 / `npm run build` exit 0
- [x] `dependencies` sha1 `ffd67dccd3d93e6a` 与基线逐字节相同（零生产依赖变更）
- [x] `vite` 仍为 `^4.4.9`，未被顶上去
- [ ] **UI 证据（页面截图）** — ⚠️ **未完成**。需启动 Electron 并登录多人群聊才能触发 @ 面板，
      沙箱无 GUI 与后端活库。已在 QA「未运行项」登记，**不计入通过**。
      建议补录三条：① 未设昵称成员按 userId 搜索命中 ② 普通成员手打 `@所有人` 消息**发出成功**而非报 2305 ③ 管理员草稿重发仍带 @所有人
- [x] 归档闭环完成（spec-delta 已回写 `specs/at-all/spec.md`）
