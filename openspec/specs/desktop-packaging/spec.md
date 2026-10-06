# desktop-packaging

> Electron 桌面端打包契约：配置唯一真源、产物内容边界、验证方式。
>
> 首次建立于 2026-10-06（`openspec/changes/2026-10-06-electron-packaging-single-config`，
> L4）。起因是一次只读盘点发现仓库并存两份互相冲突的打包配置，
> 而其中 `electron-builder.yml` **自首个 commit 起从未被读取过**。

## ADDED Requirements

### Requirement: 打包配置唯一真源

仓库中**有且仅有一份**生效的 Electron 打包配置。
`electron-builder.yml` 与 `package.json` 的 `build` 字段**不得并存** ——
并存时后者胜出、前者**静默失效**，仅有一行不起眼的 `loaded configuration` 日志，**无任何警告**。

当前唯一真源为 **`easychat-front/package.json` 的 `build` 字段**。

#### Scenario: 两份打包配置并存

- **WHEN** `easychat-front/electron-builder.yml` 存在，且 `package.json` 的 `build` 字段也存在
- **THEN** `verify_packaging_config.mjs` 报 FAIL，指明 `electron-builder.yml` 不会被读取
- **AND** 退出码为 1，阻断提交 / 推送
- **AND** 报错信息不得断言「yml 会生效」——必须陈述「yml 被忽略」这一**已核实**事实

#### Scenario: 仅存在一份配置

- **WHEN** 只存在 `package.json` 的 `build` 字段，且 `electron-builder.yml` 不存在
- **THEN** 门禁在该项上通过

#### Scenario: 两份配置都不存在

- **WHEN** `package.json` 的 `build` 字段缺失
- **THEN** 报 FAIL（打包将完全使用默认值，`productName` 退化为包名、`appId` 退化为占位符）
- **AND** 与「解析器失配」区分开：**解析器读不到 `build` 字段**时须报解析器自检失败，
  不得与「配置不存在」混为一谈（AGENTS §2.1 第 14 条）

---

### Requirement: 打包不重编原生依赖

`build.npmRebuild` 必须**显式声明**，不得依赖默认值。
该字段**默认为 `true`**，一旦未声明，每次打包都会从源码重编原生依赖。

#### Scenario: `npmRebuild` 未声明

- **WHEN** `package.json` 的 `build` 中不存在 `npmRebuild` 键
- **THEN** 门禁报 FAIL，并指出其默认值 `true` 会导致重编原生依赖
- **AND** 退出码为 1

#### Scenario: `npmRebuild` 显式声明为 `false`

- **WHEN** `build.npmRebuild === false`
- **THEN** 门禁通过
- **AND** 实跑打包日志出现 `skipped dependencies rebuild  reason=npmRebuild is set to false`
- **AND** 该结论须由**不带任何命令行覆盖参数**的实跑得出 —— 否则证明的是参数而非配置

#### Scenario: 关闭重建的安全前提

- **WHEN** `npmRebuild` 被置为 `false`
- **THEN** 必须能证明生产依赖闭包内不存在 Node-ABI 敏感（非 Node-API）的原生二进制
- **AND** 该证明以「**生产依赖闭包**」为口径，而非 `node_modules` 全量
  （构建期依赖如 `@parcel/watcher` / `@rollup/*` 不进包，不构成风险）
- **AND** 仅凭「该模块用了 Node-API」不足以免除进一步核实：
  平台分发子包（如 `@parcel/watcher-win32-x64`）自身不重复声明 `napi_versions`，
  须回溯其父包

---

### Requirement: 产物不含开发文件

`app.asar` 内不得出现源码、测试代码、开发配置，以及与 `extraResources` 重复打包的资源。

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
- **AND** 依据是 electron-builder 在这种情况下会自动前置正向全匹配模式
      （`fileMatcher.js:119-121`），而非对「纯排除即为空」的误判

#### Scenario: 误排除运行期必需文件

- **WHEN** `build.files` 排除了任何**必须在 `app.asar` 内**的文件
- **THEN** **判定为打包失败**，该配置不得合入
- **AND** 该检查必须由**活体打包产物的存在性断言**兜底，**不接受静态推断**

#### Scenario: 运行期必需文件清单必须从源码推导

- **WHEN** 门禁检查「哪些文件必须在 asar 内」
- **THEN** 该清单必须**从源码推导**，依据至少包括：
  ① 主进程 / preload 源码中的 `import x from '<相对路径>?asset'`；
  ② 源码中写死的 `join(__dirname, '<相对路径>')`
- **AND** 解析基准目录由 `package.json` 的 `main` 字段推导（如 `./out/main/index.js` → `out/main`）
- **AND** **禁止手写该清单** —— 手写清单的覆盖率取决于「写清单的人当时想到了什么」，
  而运行期引用是代码的事实
- **AND** 若推导结果为空（源目录结构变化、解析器失配等）必须报 FAIL，
      不得因「无违规」而通过（否则该检查静默失效）

> **来源**：2026-10-06 实测。手写清单只列了 `out/` 三项就以为覆盖了运行期依赖，
> 打包后**实跑**才发现托盘图标加载失败：
> `Error: Failed to load image from path '...app.asar\resources\icon.png'`。
> 根因是 `src/main/index.js` / `ipc.js` 的 `?asset` 导入被编译成
> `path.join(__dirname, '../../resources/icon.png')`，即该文件必须在 asar 内。

#### Scenario: 配置改动后必须实跑启动

- **WHEN** 改动了 `build.files`（哪怕只是增删一条排除项）
- **THEN** 必须**打包后实跑启动应用**并确认无运行期错误，
      而**不得**以「产物断言全通过」替代
- **AND** 需明确说明该实跑覆盖了什么、未覆盖什么
  （如托盘图标、SQLite 初始化、WS 连接这类**只有运行期才暴露**的路径）

---

### Requirement: 门禁的排除判定必须与打包器语义等价

静态门禁自建 glob 判定时，其结论必须与 electron-builder 的实际行为一致；
不一致的门禁会给出与真实产物**相反**的结论，而门禁全绿时无人会怀疑它。

#### Scenario: 与真实打包器对拍

- **WHEN** 运行 `verify_packaging_config.mjs --selftest`
- **THEN** 用 electron-builder **真实的** `FileMatcher` 对拍至少 20 个探针路径
      （须同时覆盖「该排除的」与「该保留的」）
- **AND** 任一探针结论不一致即 exit 1，并打印两条结论供对照

#### Scenario: 对拍依赖缺失

- **WHEN** `easychat-front/node_modules` 下缺少 `app-builder-lib` 或 `minimatch`
- **THEN** 报 **SKIP 并以退出码 2 区分**，说明需先执行 `npm ci`
- **AND** **不得**以 exit 0 表示通过（SKIP ≠ 通过）

#### Scenario: 判定语义的关键性质

- **WHEN** 实现排除判定
- **THEN** 必须复刻 `minimatchAll` 的**交替状态机**语义：
      仅当 `match !== pattern.negate` 时评估该模式，`match` 在包含 / 排除间翻转
- **AND** **不得**简化为「首个命中即决定」（该简化会让前置的正向全匹配模式
      匹配一切，从而任何排除都不生效）
- **AND** 反向断言（误排运行期必需文件）必须存在 —— 缺了它，
      判定逻辑写反时**所有正向断言会同时误报或同时静默通过**，无从发现

---

### Requirement: 打包配置变更的验证方式

任何写入打包配置或打包门禁的变更，交付前必须**实跑一次并贴出退出码**；
不得以「配置文件看起来正确」替代实证。

#### Scenario: 本机无法完成默认参数的完整打包

- **WHEN** 因环境限制（如未启用开发者模式导致无法创建符号链接、
      7-Zip 解压 `winCodeSign` 失败）无法以默认参数完成打包
- **THEN** 必须**如实登记该限制及其绕过参数**，并声明该次实跑**证明了什么、未证明什么**
- **AND** **不得**据此声称完整打包链路已验证
- **AND** **不得**把该环境失败与「配置缺陷导致的失败」混为一谈 ——
      二者日志措辞不同，必须分别归因

#### Scenario: 为规避环境限制而缩小验证范围

- **WHEN** 使用跳过某一步骤的参数（如 `signAndEditExecutable=false`）完成打包
- **THEN** 交付物中必须写明该参数跳过了什么（如 exe 图标 / 版本元数据改写）
- **AND** 该未验证部分须登记为已知缺口

#### Scenario: 门禁接入 CI 前须先跑通

- **WHEN** 某门禁将被写入 CI
- **THEN** 必须先在当前 main 上实跑并得到 exit 0（AGENTS §2.1 第 1 条）
- **AND** 无法在本机验证运行性的门禁（如打包类）**不得**接入 CI，
      须在设计文档中写明理由并另开任务

---

## 未接线项（登记于 `docs/system-facts.md` §14）

- 自动更新从未接线：源码中 `electron-updater` / `autoUpdater` / `checkForUpdates` 零引用，
  `build.publish` 亦未配置。
- macOS 打包未验证，且无 `.icns` 文件（`build.mac.icon` 指向 `icons/icon.icns`）。