# Proposal — 前端测试基线（vitest）

- 创建日期: 2026-10-04
- 效率等级: **L3**（新增 devDependency）

## Why

`docs/system-facts.md` §14 遗留 #7 的**最后一段**：渲染层 Vue 组件测试缺失。

现状是**三级测试覆盖**：

| 层 | 覆盖方式 | 状态 |
|---|---------|------|
| 后端 Java | `mvn test` | 270/270 |
| 前端纯逻辑 | node 校验脚本（`verify_*_core.mjs`） | 5 个核心全覆盖 |
| **渲染层 Vue 组件** | **无** | ❌ |

纯逻辑之所以能测，是因为这三个模块原本把计算抽成了**不依赖框架的 `.mjs` 核心**
（`exportChatCore` / `callFrameCore` / `callStoreCore` / `virtualListCore`）。
但组件层没有对应物——`Chat.vue` 的消息分发条件、`MessageVirtualList.vue` 的滚动定位、
`CallWindow.vue` 的网格渲染，全部只能靠人肉点。

**这不是「补齐覆盖率」的洁癖问题**。本仓反复出现的缺陷模式是
「字段/参数早就备好、行为却从未接通」或「条件漏了一项 → 静默失效」，
而这类缺陷**恰恰在组件层最常见**，且**全部不抛异常、不打日志**：

- 历史实例：`Chat.vue:136` 分发条件不含 24/25 → 消息拉到也不渲染，
  而 `ChatMessageVoice.vue` 是**从未被 import 的死组件**（2026-10-03 走查发现）
- 历史实例：`FileManage.vue` 用了 `window.require` 风格调用 → 主进程侧静默失败
- 历史实例：`useCallStore.endCallLocal` 的 1800ms 守卫无通话身份校验
  → 陈旧定时器抹掉新通话结束态（2026-10-04 抽离时才发现）

前两例都曾被既有 spec 验收与常规冒烟漏掉。

## What Changes

- `easychat-front/package.json`：新增 3 个 **devDependency**
  （`vitest@1.6.0` / `@vue/test-utils@2.4.6` / `jsdom@22.1.0`）+
  `test` 脚本。**零生产依赖变更**，`dependencies` 不动。
- `easychat-front/vitest.config.mjs`：新增测试配置（**独立文件**，
  不塞进 `electron.vite.config.js` —— 那个是构建配置，测试不需要
  `externalizeDepsPlugin`，混在一起会让 vitest 继承 electron 的外置策略）。
- `easychat-front/src/renderer/src/**/__tests__/*.spec.js`：首批组件测试。
- `.github/workflows/ci.yml`：前端 job 增加 `npm run test`。
- 文档：`docs/system-facts.md` §14 #7、变更日志；本仓 `easychat-front/AGENTS.md` 无需改
  （其 §3.3-4 已声明「`package.json` 依赖变更属 L3」，本次正是按此执行）。

## Capabilities

- **C1**: 渲染层组件具备可运行的自动化测试，且命令可复现（`npm run test`）。
- **C2**: 测试基建**零生产依赖**，不改动 `dependencies`，不影响打包产物。
- **C3**: 测试纳入 CI，前端 job 门禁化（测试红则流水线红）。
- **C4**: 测试目录约定明确，**不被 `eslint .` 的既有失效拖累**
  （`npm run lint` 长期 `exit 2`，见遗留技术债；新测试若挂在同一条命令上
  会一起恒红，故测试命令独立）。
- **C5**: 首批测试覆盖**已知的静默失效高发点**（消息分发条件、死组件引用、
  主进程通道调用形态），而非只测「渲染出一个元素」。

## Impact

- **对外接口**: 无。
- **生产行为**: 无。`dependencies` 零变更，Electron 打包产物不变。
- **开发者体验**: 新增 `npm run test`。`npm ci` 变慢（3 个包 + 传递依赖）。
- **CI 时长**: 前端 job 增加数秒量级（jsdom 首次启动较慢，控制在 60s 内）。
- **风险点（已在 tasks 阶段零逐条实测）**:
  ① vitest 2/3 强依赖 vite 5/6，会与 `electron-vite@1` + `vite@4.4.9` 冲突 → 钉 1.6.0；
  ② 本机 Node v24 而 CI Node 20，jsdom 需选双端兼容版本 → 钉 22.1.0（`node>=16`）。

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 <YYYY-MM-DD> 确认，允许进入 design 阶段。
>
> - [ ] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估

### 已获人工决策记录（2026-10-04）

| 决策点 | 选定方案 |
|--------|----------|
| 是否引入 vitest | **引入**（用户 2026-10-04 明确指示） |