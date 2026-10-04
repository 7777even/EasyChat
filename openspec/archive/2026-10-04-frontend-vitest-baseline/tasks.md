# Tasks — 前端测试基线（vitest）

- 关联 Design: `openspec/changes/2026-10-04-frontend-vitest-baseline/design.md`
- 创建日期: 2026-10-04
- 预估总工时: 5h

## 阶段零：**先验证兼容性再装**（本变更唯一真正的风险）

> 引入测试运行器最典型的失败是「装上了，vite 被顺带升级，electron 构建崩掉」。
> 故把「构建仍然通过」作为**第一个**验收项，而不是最后补。

- [ ] **T0.1** 记录当前基线：`node -v`、`npm ls vite electron-vite`、`npm run build` 的 exit code 与 `Build-Jdk` 无关的**当前产物清单**（记录 `electron-vite build` 输出的 3 个 `built in`） — ≤20min
- [ ] **T0.2** `npm i -D vitest@1.6.0 @vue/test-utils@2.4.6 jsdom@22.1.0 --save-exact`，**不加 `^`** — ≤30min
- [ ] **T0.3** **验证 vite 未被顺带升级**：`npm ls vite` 仍为 `4.4.9`；若被升级到 5+ → **立即回退并改用 vitest 0.34.x** — ≤20min
- [ ] **T0.4** **`npm run build` 仍然 exit 0**（这是阶段零的核心验收项） — ≤10min
- [ ] **T0.5** 确认 `dependencies` 零变更：`git diff package.json` 中 `dependencies` 段为空 — ≤10min

## 阶段一：配置与冒烟

- [ ] **T1.1** `vitest.config.mjs`：`environment: 'jsdom'`、`@` 别名、include 模式、`globals: false` — ≤20min
- [ ] **T1.2** `setupFiles`：`src/renderer/src/__tests__/setup.js`——
      `ResizeObserver` / `IntersectionObserver` / `matchMedia` /
      `window.ipcRenderer` 的 Proxy 桩（ADR-002）/ `crypto.randomUUID` — ≤40min
- [ ] **T1.3** `package.json` 加 `test` / `test:watch` 脚本 — ≤10min
- [ ] **T1.4** **[TDD 冒烟]** 先写一个**故意失败**的 spec（断言 `1 === 2`），实跑确认 `npm run test` **转红并 exit≠0**。
      **不确认这一步就往下写，等于没验证过测试真的能跑** — ≤20min

## 阶段二：首批测试（锁定已知静默失效点）

- [ ] **T2.1** `chat-message-dispatch.spec.js`：`Chat.vue` 的
      **「messageType → 子组件」映射表**（ADR-001）。
      覆盖 2/5→`ChatMessage`+`ChatMessageTime`、1/3/8/9/11/12/26→`ChatMessageSys`、
      14/20/24/25→`ChatMessage`；并断言**未覆盖的类型不渲染任何消息组件** — ≤1h
- [ ] **T2.2** `chat-message-inner-dispatch.spec.js`：`ChatMessage.vue` 内部对
      **24 语音 / 25 位置**的二次分发（2026-10-03「死组件」事故的同一处），
      断言二者都渲染出对应子组件（而非 `undefined`） — ≤40min
- [ ] **T2.3** 与后端 `MessageTypeEnum` **对账**：脚本比对「枚举中有、
      且属落库白名单的类型」与「`Chat.vue` 已覆盖的类型」，差集非空即失败 — ≤40min
      （落库白名单在 `ChatMessageServiceImpl`，需先定位再断言）

## 阶段三：门禁与接入

- [ ] **T3.1** 新增 `scripts/verify/verify_frontend_test_base.mjs`：断言
      ① 三个 devDep **已存在且钉死版本**（无 `^`/`~`）
      ② **`vite` 仍为 4.x**（被顺带升级即 exit 1——这是阶段零要防的复发）
      ③ `dependencies` 未被测试框架污染（不含 vitest/jsdom/test-utils）
      ④ `vitest.config.mjs` 存在且声明 `jsdom` 与 `@` 别名
      ⑤ `@` 别名与 `electron.vite.config.js` **两处一致**
      ⑥ `setupFiles` 指向的文件存在且**含 `ResizeObserver` 桩**
         （缺它则挂载即崩，属高频坑）
      ⑦ `test` 脚本存在；CI 有 `npm run test`
      在**未加配置**时实跑须 exit=1 — ≤1h
- [ ] **T3.2** 接入 `ci.yml` 前端 job — ≤20min
- [ ] **T3.3** 变异检验：改坏版本号 / 改坏别名 / 删掉桩 → 确认转红 — ≤40min

## 阶段四：回归与收尾

- [ ] **T4.1** `npm run test` 全绿；`npm run build` 仍 0；后端 17 门禁 + 7 变异全绿 — ≤40min
- [ ] **T4.2** `engineering/qa/2026-10-04-frontend-vitest-baseline.md` — ≤30min
- [ ] **T4.3** `docs/system-facts.md` §14 #7 标记闭环 + 变更日志；
      `docs/system-facts.md` §2 记前端测试基线 — ≤30min
- [ ] **T4.4** spec-delta 回写 + `git mv` 归档 + 按域拆提交 — ≤30min

## 遗留（本批明确不做）

| 项 | 原因 | 后续 |
|----|------|------|
| 组件快照测试 | 快照易碎，且**快照过不了不等于行为对**；首批只断言行为 | 视需要 |
| 路由 / 守卫测试 | 需 `vue-router` 测试环境与更多桩 | 独立 Change |
| Pinia store 全量测试 | `useCallStore` 编排已由 `callStoreCore` 纯核心覆盖，重复测价值低 | 按需 |
| 修复 `npm run lint` 失效 | **既有技术债**（`eslint .` 报 `No files matching`），与本变更无关；测试命令独立故不叠加 | 既有 retro 已登记 |

## DoD 自检

- [ ] `tasks.md` 全部勾选
- [ ] **T0.4 的 `npm run build` exit 0 已留证**（不能只在最后一轮看构建）
- [ ] **`vite` 版本未被顺带升级**（T0.3 已实证）
- [ ] `dependencies` 零变更
- [ ] `npm run test` 全绿且 `exit=0`
- [ ] 新门禁 + 变异检验全绿
- [ ] `docs/system-facts.md` 已同步
- [ ] 归档闭环完成