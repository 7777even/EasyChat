# Spec Delta — 消除 Electron 打包配置双份

- 关联 Tasks: 2026-10-06-electron-packaging-single-config/tasks.md
- 创建日期: 2026-10-06

> 格式对齐 `openspec/specs/<capability>/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 打包配置唯一真源（对齐 C1）

仓库中**有且仅有一份**生效的 Electron 打包配置。
`electron-builder.yml` 与 `package.json` 的 `build` 字段**不得并存** ——
并存时后者胜出、前者**静默失效**（仅有一行 `loaded configuration` 日志，无任何警告）。

#### Scenario: 两份打包配置并存

- **WHEN** `easychat-front/electron-builder.yml` 存在，且 `package.json` 的 `build` 字段也存在
- **THEN** `verify_packaging_config.mjs` 报 FAIL，指明 `electron-builder.yml` 不会被读取
- **AND** 退出码为 1，阻断提交 / 推送
- **AND** 报错信息中不得断言「yml 会生效」——必须陈述「yml 被忽略」这一**已核实**事实

#### Scenario: 仅存在一份配置

- **WHEN** 只存在 `package.json` 的 `build` 字段，且 `electron-builder.yml` 不存在
- **THEN** 门禁在该项上通过

#### Scenario: 两份配置都不存在

- **WHEN** `package.json` 的 `build` 字段缺失
- **THEN** 报 FAIL（打包将完全使用默认值，`productName` 退化为包名、`appId` 退化为占位符）
- **AND** 与「解析器失配」区分开：**解析器读不到 `build` 字段**时须报解析器自检失败，
  不得与「配置不存在」混为一谈（§2.1 第 14 条）

---

### Requirement: 打包不重编原生依赖（对齐 C2）

`build.npmRebuild` 必须**显式声明**，不得依赖默认值。
electron-builder 的该字段**默认为 `true`**，一旦未声明，每次打包都会从源码重编原生依赖。

#### Scenario: `npmRebuild` 未声明

- **WHEN** `package.json` 的 `build` 中不存在 `npmRebuild` 键
- **THEN** 门禁报 FAIL，并指出其默认值 `true` 会导致重编原生依赖
- **AND** 退出码为 1

#### Scenario: `npmRebuild` 显式声明为 `false`

- **WHEN** `build.npmRebuild === false`
- **THEN** 门禁通过
- **AND** 实跑打包日志出现 `skipped dependencies rebuild  reason=npmRebuild is set to false`

#### Scenario: 关闭重建的安全前提

- **WHEN** `npmRebuild` 被置为 `false`
- **THEN** 系统必须能证明生产依赖闭包内不存在 Node-ABI 敏感（非 Node-API）的原生二进制
- **AND** 该证明以「生产依赖闭包」为口径，而非 `node_modules` 全量
  （构建期依赖如 `@parcel/watcher` / `@rollup/*` 不进包，不构成风险）

---

### Requirement: 产物不含开发文件（对齐 C3）

`app.asar` 内不得出现源码、测试代码、开发配置，
以及与 `extraResources` 重复打包的资源。

#### Scenario: 开发文件被打进产物

- **WHEN** `build.files` 缺少对 `src/**`、`.eslintrc.cjs`、`vitest.config.mjs`、`.npmrc`、
      `electron.vite.config.js` 中任意一项的排除
- **THEN** 门禁报 FAIL，逐项列出缺失的排除项
- **AND** 退出码为 1

#### Scenario: 与 `extraResources` 重复的资源

- **WHEN** `build.files` 未排除 `assets/**`，而 `build.extraResources` 已包含 `./assets/**`
- **THEN** 门禁报 FAIL
- **AND** 理由必须陈述「同一份资源在产物中存了两遍」，而非泛泛的「体积问题」

#### Scenario: `files` 为纯排除写法

- **WHEN** `build.files` 仅由 `!` 开头的排除项组成
- **THEN** **合法**，不得报 FAIL
- **AND** 依据是 electron-builder 在这种情况下会自动前置 `**/*`
      （`fileMatcher.js:119-121`），而非对「纯排除即为空」的误判

#### Scenario: 误排除运行期必需文件

- **WHEN** 打包完成后 `out/main/index.js`、`out/preload/index.js`、`out/renderer/index.html`
      或 `dist/win-unpacked/resources/assets/ffmpeg.exe` 任一缺失
- **THEN** **判定为打包失败**，该配置不得合入
- **AND** 由实跑产物的存在性断言兜底，**不接受静态推断**

---

### Requirement: 打包配置变更的验证方式（对齐 §2.1 第 1 条）

任何写入打包配置或打包门禁的变更，交付前必须**实跑一次并贴出退出码**；
不得以「配置文件看起来正确」替代实证。

#### Scenario: 本机无法完成完整打包

- **WHEN** 因环境限制（如未启用开发者模式导致无法创建符号链接、7-Zip 解压 `winCodeSign` 失败）
      无法以默认参数完成打包
- **THEN** 必须**如实登记该限制及其绕过参数**，并声明该次实跑**证明了什么、未证明什么**
- **AND** **不得**据此声称完整打包链路已验证

#### Scenario: 为规避超时而缩小验证范围

- **WHEN** 使用跳过某一步骤的参数（如 `signAndEditExecutable=false`）完成打包
- **THEN** 交付物中必须写明该参数跳过了什么（如 exe 图标 / 版本元数据改写）
- **AND** 该未验证部分须登记为已知缺口

---

## MODIFIED Requirements

无。（本次不修改任何已有 capability 的规格。）

## REMOVED Requirements

无规格被移除，但**移除一份配置文件**：

### Requirement: `easychat-front/electron-builder.yml` 作为配置载体

- 该文件被删除。

**移除原因**: 经 `read-config-file` 源码（`main.js:71-72`）与实跑日志双重核实，
它自首个 commit 起**从未被读取**；其中 `npmRebuild: false` 与 `files` 白名单两项意图
从未生效，已分别造成「打包 1800s 超时」与「开发文件进产物」两个实际问题。

**替代方案**: 上述两项意图并入 `package.json` 的 `build` 字段（唯一真源），
并由 `verify_packaging_config.mjs` 钉死「不得有第二份配置」。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 打包配置唯一真源 | 阶段零 R1；阶段一 断言 1、5；阶段二 1-3 |
| C2 | ADDED: 打包不重编原生依赖 | 阶段零 R2；阶段一 断言 2；阶段二 2；阶段三 2 |
| C3 | ADDED: 产物不含开发文件 | 阶段零 R3；阶段一 断言 3、4；阶段二 3；阶段三 5、6 |
| —（流程约束） | ADDED: 打包配置变更的验证方式 | 阶段三 全部 |