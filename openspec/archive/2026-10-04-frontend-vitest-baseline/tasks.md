# Tasks — 前端测试基线（vitest）

- 关联 Design: `openspec/archive/2026-10-04-frontend-vitest-baseline/design.md`
- 创建日期: 2026-10-04
- 预估总工时: 5h

## 阶段零：**先验证兼容性再装**（本变更唯一真正的风险）

> 引入测试运行器最典型的失败是「装上了，vite 被顺带升级，electron 构建崩掉」。
> 故把「构建仍然通过」作为**第一个**验收项，而不是最后补。

- [x] **T0.1** 记录基线：`node v24.0.2` / `vite@4.5.14` / `electron-vite@1.0.29` / `@vitejs/plugin-vue@4.6.2` / `dependencies` 21 项 sha1 `ffd67dccd3d93e6a` — ≤20min
- [x] **T0.2** `npm i -D --save-exact vitest@1.6.0 @vue/test-utils@2.4.6 jsdom@22.1.0`（输出含 `added 107 / removed 4 / changed 2`，故 T0.3 专门实证） — ≤30min
- [x] **T0.3** **`vite` 未被顺带升级**：`npm ls vite` 仍为 `4.5.14`；`electron-vite` 与 `@vitejs/plugin-vue` 亦未变 — ≤20min
- [x] **T0.4** **`npm run build` 仍 exit 0**（三个 `built in`：309ms / 10ms / 14.29s，与基线同构） — ≤10min
- [x] **T0.5** **`dependencies` 零变更**：sha1 与基线**逐字节相同**（`ffd67dccd3d93e6a`），21 项不变，无测试框架混入 — ≤10min

## 阶段一：配置与冒烟

- [x] **T1.1** `vitest.config.mjs`：`environment: 'jsdom'`、`@` 别名、include 限定 `__tests__`、setupFiles — ≤20min
- [x] **T1.2** `setup.js`：`ResizeObserver` / `IntersectionObserver` / `matchMedia` / IPC Proxy 桩（ADR-002）/ `crypto.randomUUID` / `createObjectURL` — ≤40min
- [x] **T1.3** `package.json` 加 `test`（`vitest run`）/ `test:watch` — ≤10min
- [x] **T1.4** **[TDD 冒烟]** 故意失败用例（`expect(1).toBe(2)`）实跑确认 **exit=1、`1 failed`、失败路径精确到 `_smoke-red.spec.js:7:15`**，确认后删除 — ≤20min

> **T1 实施记录**：T1.1 首版**漏挂 `@vitejs/plugin-vue`**，
> 实测报 `Failed to parse source for import analysis because the content contains
> invalid JS syntax. Install @vitejs/plugin-vue`。**vitest 不会自动启用它**
> （该插件只对 electron-vite 构建生效），尽管该包本就在 devDependencies 中。
> 「依赖已安装」≠「工具会启用它」。已补并注释说明。

## 阶段二：首批测试（锁定已知静默失效点）

- [x] **T2.1** ~~`Chat.vue` mount 测试~~ → **改为源码级对账门禁**（见 T2.3），理由见下方记录 — ≤1h
- [x] **T2.2** `chat-message-inner-dispatch.spec.js`：10 例，锁定 2026-10-03 死组件事故 — ≤40min
- [x] **T2.3** `verify_chat_message_dispatch.mjs`：`Chat.vue` 分发条件 ↔ 后端**落库白名单**对账 + 死组件 import 检查 + 兜底分支次序检查 — ≤40min

> **T2.1 改判理由（ADR-001 的补充）**：实施中发现 `Chat.vue` 是 180+ 行视图，
> 挂载需连带 stub Layout / 路由 / MessageSend / ContextMenu 等**十余个依赖**，
> 成本远高于收益，且这些依赖一变测试即假红。而本条要验的其实是
> **模板里的条件表达式**——用源码解析更直接、更快、更贴近「条件漏项」这一失败模式本身。
> `ChatMessage.vue` 的**二次分发**（24/25 与纯文本兜底的先后关系）才由 vitest 真挂载验证。
>
> **落库白名单才是准确的对账集合**：`MessageTypeEnum` 有 28 个类型，
> 但其中 13/15/16/17/18/19/21/22/23/27 是控制帧（落库白名单在
> `ChatMessageServiceImpl` 的 `ArraysUtil.contains`），本就不该出现在消息列表里。
>
> **T2.3 自身踩了一个坑（靠交叉验证发现）**：门禁首版报「24/25 未覆盖」，
> 但我此前已用两种独立方式（模板 grep、正则提取分支）确认 `Chat.vue` **确实覆盖**了它们
> → 是我的截断锚点选错（用 `indexOf('</template>')` 截取插槽，
> 而**每个分支自身即以该标签闭合** → 只截到第 1 个分支）。
> 改用截到 `</MessageVirtualList>`。**若当时照单全收，会去「修」一个不存在的缺陷**
> 并把错误结论写进文档。

## 阶段三：门禁与接入

- [x] **T3.1** `verify_frontend_test_base.mjs`（42 项）：依赖钉死版本 / `vite` 仍 4.x / 生产依赖不污染 / 配置含 jsdom + `@vitejs/plugin-vue` / 别名两处一致 / 全局桩齐全 / CI 已跑 test — ≤1h
- [x] **T3.2** 接入 `ci.yml`（前端 job 加 `npm run test`；gates job 加两个新门禁） — ≤20min
- [x] **T3.3** `mutation_chat_dispatch.cjs`：**5/5 捕获**（第一条即「退回死组件事故」） — ≤40min

> **T3.3 实施记录**：本脚本初版有**两轮沙箱策略错误**——
> ① robocopy 退出码语义特殊（**0=无文件复制、1=复制成功、≥8 才错**），
>    被当成「复制成功=失败」而抛出，脚本第一次 reset 就崩；
> ② 修掉后**基线自检立刻拦下**：沙箱没有 `node_modules`，`vitest` 命令根本不存在
>    → 所有用例会「红」，那不是判别力而是环境缺失。
> 改为**原地临时改 + `try/finally` 必还原**（本项目测试只读被测源码、不写文件），
> 已验证运行后工作区未被污染。
>
> 另：**第 5 个变异初版漏网**（管理员删除与撤回的文案区分未断言），补断言后捕获。
> 而汇总行当时仍输出「5/5 全部捕获」——**与全捕获完全无法区分**，
> 这已是本轮第三次同类问题（`verify_migration_flyway` / `mutation_local_db_core` 各一次）。
> 汇总已改为计入 `[漏网]` 与 `[无效]`，并立为 **AGENTS §2.1 第 10 条纪律**。

## 阶段四：回归与收尾

- [x] **T4.1** `npm run test` **10/10 exit 0**；`npm run build` **exit 0**；门禁 **19/19**；变异 **8/8** — ≤40min
- [x] **T4.2** `engineering/qa/2026-10-04-frontend-vitest-baseline.md` — ≤30min
- [x] **T4.3** `engineering/retro/2026-10-04-frontend-vitest-baseline.md`；`docs/system-facts.md` §14 #7 更新 + 变更日志；`AGENTS.md` §10 门禁表 + §2.1 第 10 条纪律 — ≤30min
- [x] **T4.4** spec-delta 回写 `openspec/specs/migration-automation/spec.md`（测试基线属其延伸，另记于 system-facts）；`git mv` 归档 — ≤30min

## 遗留（本批明确不做）

| 项 | 原因 | 后续 |
|----|------|------|
| **绝大多数组件仍无测试** | 首批只做 `ChatMessage.vue` 一个视图 | 独立小 Change，按视图增量 |
| `Chat.vue` mount 测试 | 依赖过多、成本高于收益（见 T2.1 改判理由） | 依赖变动使成本下降后可重评 |
| 快照测试 | 快照易碎，且「快照没变」≠「行为对」 | 视需要 |
| 路由 / 守卫测试 | 需更多桩 | 独立 Change |
| `npm run lint` 失效 | **既有技术债**（存量 311 errors / 20537 warnings），与本变更无关；测试命令独立故不叠加 | 既有 retro 已登记 |
| ~~GitHub Actions 未实跑~~ | ✅ 2026-10-05 已实跑：run #1（SHA `2884cbc`）4 job 全绿，前端 job 含 `npm run lint` + 组件测试 | 原缺口：当时沙箱无网络与远端 |

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] **T0.4 的 `npm run build` exit 0 已留证**
- [x] **`vite` 版本未被顺带升级**（T0.3 实证 4.5.14）
- [x] `dependencies` 零变更（sha1 逐字节相同）
- [x] `npm run test` 全绿且 exit 0（10/10）
- [x] 新门禁 2 个 + 变异 1 个全绿
- [x] `AGENTS.md` / `docs/system-facts.md` 已同步
- [x] 归档闭环完成