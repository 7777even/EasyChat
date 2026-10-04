# Design — 前端测试基线（vitest）

- 关联 Proposal: `openspec/changes/2026-10-04-frontend-vitest-baseline/proposal.md`
- 创建日期: 2026-10-04

## 1. 架构设计

```
easychat-front/
├─ package.json          +3 devDep（vitest / @vue/test-utils / jsdom）+ test 脚本
├─ vitest.config.mjs     ← 新增，独立于 electron.vite.config.js
├─ electron.vite.config.js  （**不动**）
└─ src/renderer/src/
   └─ __tests__/
      ├─ setup.js                   全局 stub（window.ipcRenderer / matchMedia / ResizeObserver）
      └─ chat-message-dispatch.spec.js   Chat.vue 分发条件
      └─ chat-message-inner-dispatch.spec.js  ChatMessage.vue 内部分发（24/25）
```

### 为什么 vitest 而不是 node 校验脚本

前三个核心（`exportChatCore` / `callStoreCore` / `virtualListCore` / `callFrameCore`）
之所以能用 node 脚本测，是因为它们**没有框架依赖**。
组件依赖 Vue 运行时 + DOM，`node <file>` 无法 mount，**必须**有测试运行器。

### 为什么需要 `@vue/test-utils` + `jsdom`

- `@vue/test-utils` 提供 `mount()`：不依赖它就只能手工构造 vnode，无法验证「条件分支选了哪个子组件」
- `jsdom` 提供 DOM：组件里有 `ResizeObserver`、`matchMedia`、`IntersectionObserver`
  （虚拟列表与响应式布局必需），node 环境一个都没有

### 为什么选 vitest 1.6.0 而非最新

| 版本 | vite 依赖 | 与本项目（`vite@4.4.9` + `electron-vite@1`） |
|---|---|---|
| vitest 5.x | vite 6/7 | ❌ peer 要求 vite 5+，会强制升级 vite → **拖坏 electron-vite** |
| vitest 2.x | vite 5 | ❌ 同上 |
| **vitest 1.6.0** | vite 4/5（peer 可选） | ✅ 与 vite 4 同代 |

**版本必须钉死**，否则哪天有人 `npm update` 就会连带升级 vite。

### 为什么 jsdom 22 而不是最新

本机 Node **v24.0.2**，CI Node **20**。jsdom 24+ 要求 node 更高版本，
jsdom 22 声明 `node>=16`，**双端兼容**。

### 为什么配置文件独立

`electron.vite.config.js` 带 `externalizeDepsPlugin()` —— 它把
`dependencies` 全部**外置**（运行时从 node_modules 加载）。测试进程里不需要这层，
且 vitest 的 transform 管线与 electron 构建不同。混在一起会让
vitest 继承 electron 的外置策略而找不到模块。故新建 `vitest.config.mjs`。

### 测试目录约定

`src/renderer/src/__tests__/**.spec.js`。

⚠ **不放 `tests/` 顶层目录**：贴近被测代码，避免「找不到哪个测试测什么」。
`@` 别名已在 `vitest.config.mjs` 里重新声明（vitest 不读 electron-vite 的别名）。

## 2. 接口设计

无对外接口变更。新增**开发者命令**：

```
npm run test           # 单次运行
npm run test:watch     # watch 模式
```

## 3. 数据模型

无。测试不引入持久化。

## 4. 安全设计

- **零生产依赖**：三个包全在 `devDependencies`，`dependencies` **一字未改**。
  打包产物（electron-builder）不含测试框架。
- **测试不触网、不写文件**：`environment: 'jsdom'` 默认无网络；
  stub 掉 `window.ipcRenderer` 使测试**无法**误调主进程。
- **stub 必须覆盖 IPC 桥**：`src/preload/index.js` 用 `contextBridge`
  暴露了一组 `window.*` 方法。组件在测试里若真的去调它们会抛错。
  故 `setup.js` 用 `Proxy` 给一个**永不真正执行**的桩——这样
  「组件调了不存在的通道」会立刻暴露，而不是静默 undefined。

## 5. ADR

### ADR-001: 首批测试锁定「消息分发条件」，而非「渲染出一个元素」

- 状态: 已接受
- 上下文: 「组件能 mount 成功」几乎测不出真问题。本仓已发生两起
  「服务端/历史消息都正常拿到，但客户端条件漏了一项 → 不渲染、不报错」：
  ① `Chat.vue:136` 分发条件不含 24/25，而 `ChatMessageVoice.vue`
     是**从未被 import 的死组件**（2026-10-03 走查才发现）
  ② `useCallStore` 的复位守卫无身份校验（2026-10-04 抽离时才发现）
- 决策: 首批测试断言的是**「给定 messageType，渲染哪个子组件」**这张映射表，
  且该表与后端 `MessageTypeEnum` 对账——**任何一侧新增类型而另一侧漏了，测试转红**。
- 后果: 正面——把「两端帧/类型对齐」这件事从前端的隐式约定变成**显式断言**。
  负面——后端加枚举时前端须同步改测试，**这是有意的摩擦**（否则就是静默失效）。

### ADR-002: 全局 stub 用 Proxy 而非逐个列举

- 状态: 已接受
- 上下文: preload 暴露面有几十个方法，逐个列举必然遗漏，且遗漏后
  「组件调了未列举的通道」会得到 `undefined` → 后续调用才炸，错误信息失真。
- 决策: `window.*` 桥接方法用一个返回**记录调用的桩函数**的 Proxy。
- 后果: 正面——新增 preload 方法无需改测试；且能断言「组件到底调了哪个通道」。
  负面——若桩返回 undefined，依赖返回值的逻辑会静默走到 undefined 分支。
  故首批测试避免断言依赖 preload 返回值的分支。

### ADR-003: 不复用 electron-vite 的 `@` 别名，vitest 独立声明

- 状态: 已接受
- 上下文: vitest 不读 `electron.vite.config.js`（除非显式 mergeConfig）。
- 决策: `vitest.config.mjs` 里重新写一遍 `@` → `src/renderer/src`。
- 后果: 负面——**两处配置需同步**。故门禁断言两处别名字符串一致。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|----------|
| **vitest 装上后构建失败**（vite 版本冲突） | 中 | 高 | 阶段零**先跑 `npm run build`** 验证；钉死 1.6.0；门禁断言版本号 |
| `npm ci` 在 CI 变慢或超时 | 低 | 中 | 只加 3 个 devDep；jsdom 是其中最重的一个（约 10MB），CI 缓存已配 `cache: npm` |
| 测试里 `ResizeObserver` 未定义导致挂载即崩 | **高** | 中 | 阶段零就把它列进 `setup.js` 必备桩；门禁断言桩存在 |
| 引入 vitest 后 `npm run lint` 更糟 | 中 | 低 | 测试命令**独立**（`npm run test`），不挂 lint；既有 lint 失效是**已登记技术债**，不叠加 |
| 测试写多了拖慢 CI | 低 | 低 | 首批只做 2 个 spec；后续按需增量 |
| jsdom 与本机 Node 24 不兼容 | 低 | 中 | jsdom 22 声明 `node>=16`，双端兼容；阶段零实跑验证 |

## 7. 依赖与前提

- **新增 3 个 devDependency**（§3.3-4 明确：前端 `package.json` 依赖变更属 L3）：
  `vitest@1.6.0`、`@vue/test-utils@2.4.6`、`jsdom@22.1.0`。
  全部**钉死版本**（不带 `^`），理由见 §1 版本表——`^1.6.0` 会在某天
  自动升到 2.x 并连带升级 vite。
- **零生产依赖变更**：`dependencies` 不动。
- **前置**: 遗留 #7 前两段（纯核心抽离）已闭环，组件层是最后一段。