# Design — 消除 Electron 打包配置双份

- 关联 Proposal: 2026-10-06-electron-packaging-single-config/proposal.md
- 创建日期: 2026-10-06

## 1. 架构设计

本次不改运行时架构，只收敛**构建期配置的唯一真源**。

```
改动前                                    改动后
─────────────────────────────            ─────────────────────────────
easychat-front/                          easychat-front/
├─ package.json                          ├─ package.json
│   └─ build  ← ✅ 唯一生效的真源          │   └─ build  ← ✅ 唯一生效的真源
│       (productName/appId/nsis/win)      │       + npmRebuild: false  ← 新增
│                                         │       + files 白名单        ← 新增
└─ electron-builder.yml  ← ❌ 永不生效     └─ (无第二份配置)

electron-builder 解析链（源码实证）：
  read-config-file/out/main.js:71-72
    const data = packageMetadata[request.packageKey]      // package.json.build
    return data == null ? findAndReadConfig(request) : { result: data, ... }
  ⇒ build 字段存在即直接返回，findAndReadConfig 永不被调用
```

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `package.json` (`build`) | 新增 `npmRebuild` / `files` 两个键 | 仅构建期，不影响运行时 |
| `electron-builder.yml` | **删除** | —— |
| `scripts/verify/verify_packaging_config.mjs` | 新增 | 纯静态断言，不改任何产物 |

> 本次**不触碰** `src/main` / `preload` / `renderer` 任何一行 —— 三层边界零影响。

## 2. 接口设计

无对外接口变更。

## 3. 数据模型

无表结构 / 字段变更。

## 4. 安全设计

- 本次**不构成**任何凭据泄露的修复：`easychat-front/` 下经核实**不存在 `.env*` 文件**，
  故「凭据被打包」是**假设性风险**而非已发生事实。交付物中不得表述为「修复了泄露」。
- 白名单化后 `.env*` 被硬排除，属**预防性**收敛。
- 门禁断言将「`.env*` 不得进入 asar」固定下来（静态层面）。

## 5. ADR（架构决策记录）

### ADR-001: 删除 `electron-builder.yml`，而非保留并标注「废弃」

- 状态: 已接受
- 上下文: 两份配置并存时，`package.json.build` 胜出、yml **静默失效**（无任何警告）。
  实跑日志仅有一行 `loaded configuration file=package.json ("build" field)`，
  且该行淹没在数十行进度输出里。
- 决策: **删除** yml。
- 后果: 正面 —— 消除「改错文件却以为生效了」这一最隐蔽的失败模式，并用门禁钉死。
  负面 —— 删除不可逆；若日后想改回 yml 形式，需重新引入并同步门禁的断言。
  缓解 —— `git revert` 可完整恢复。

### ADR-002: 不把 yml 中的键「一股脑搬进」package.json

- 状态: 已接受
- 上下文: yml 内容与 `electron-vite` **脚手架模板**高度一致（`linux.target: [AppImage, snap, deb]`、
  `maintainer: electronjs.org`、`appId: com.electron.app`、`asarUnpack: resources/**`）。
  模板值未针对本项目核实过。
- 决策: **只搬经核实确有必要的两项**（`npmRebuild`、`files`），其余逐条处置如下。
- 后果: 避免把「从未生效的猜测」固化成「已生效的事实」。

#### 逐条处置表

| yml 中的键 | 处置 | 理由（证据） |
|---|---|---|
| `npmRebuild: false` | **搬入** | 实跑：未搬入时 1800s 超时；且经核实安全（ADR-003） |
| `files`（白名单） | **搬入并改写** | 实跑：未生效时 `src/__tests__/`、`.eslintrc.cjs`、`vitest.config.mjs`、`.npmrc` 全部进入 `app.asar` |
| `productName` / `appId` | **不搬** | package.json 已有**正确值**（`EasyChat` / `com.easychat`）；yml 里是占位符，搬入会**倒退** |
| `asarUnpack: resources/**` | **不搬** | `file.js:27-28,40-46` 实证：ffmpeg/ffprobe 经 `extraResources` 落在 `resources/assets/`，**本就在 asar 之外**，无需解包 |
| `publish` | **不搬** | 源码中 `electron-updater` / `autoUpdater` / `checkForUpdates` **0 处引用** —— 自动更新从未接线，配了也是空文（属另一独立议题） |
| `linux.target` / `maintainer` / `category` | **不搬** | `maintainer: electronjs.org` 对本项目是**事实错误**；无任何验证过的多平台发布需求 |
| `mac.entitlementsInherit` | **不搬** | 指向不存在的 `build/entitlements.mac.plist`。**本项目从未验证过 mac 打包**（无 mac 环境），不在本次凭空补配置 |

### ADR-003: `npmRebuild: false` 对本项目安全（已证明，非假设）

- 状态: 已接受
- 上下文: 全局关闭原生依赖重建，理论上可能让 Node ABI 的二进制进 Electron 包而运行期崩溃。
- 决策: 按**生产依赖闭包**而非 `node_modules` 全量来判定。
- 证据（实跑脚本产出）:
  - 生产依赖闭包 = **215 个包**
  - 其中含 `.node` 二进制的 = **仅 1 个**：`sqlite3`
  - `sqlite3@5.1.6` 的 `binary.napi_versions = [3,6]`，产物为
    `lib/binding/napi-v6-win32-unknown-x64/node_sqlite3.node`
    ⇒ **Node-API，跨 Node/Electron ABI 稳定**
  - 曾被误判为风险的 `@parcel/watcher-win32-x64`、`@rollup/rollup-win32-x64-*`
    经闭包核实**不在生产依赖内**（构建期依赖，不进包），已从风险清单剔除
- 后果: 打包不再重编原生依赖。副作用是 `@parcel/watcher`（构建期）也不再重编 ——
  它不进包，故无运行期影响。

### ADR-004: `files` 用 `!src/**` 而非模板的 `!src/*`

- 状态: 已接受
- 上下文: `src/*` 只匹配 `src` 的直接子项，深度依赖 minimatch 对「排除目录后是否遍历其内容」的实现细节。
- 决策: 用 `!src/**`，语义与深度无关，门禁断言也可按字面量校验。
- 后果: 排除范围更明确；若日后新增更深层源码目录，不会漏网。

### ADR-005: 额外排除 `assets/**`（超出 yml 原意）

- 状态: 已接受
- 上下文: 实跑发现 `app.asar` 274.7 MB。`assets/` 含 `ffmpeg.exe`(62.6 MB) +
  `ffprobe.exe`(125.8 MB)，这些**已由 `extraResources: ["./assets/**"]` 复制到
  `dist/win-unpacked/resources/assets/`**（`file.js:27-28` 正是从该路径解析），
  同时又被 `files` 默认的 `**/*` 打进 asar ⇒ **同一份资源在产物里存了两遍**。
- 决策: `files` 中显式 `!assets/**`。
- 后果: 安装包体积显著下降。**风险**：`file.js` 的 ffmpeg 路径解析依赖 extraResources 产物，
  若该机制失效则视频转码会失败 → 缓解见 §6，实跑后必须断言
  `dist/win-unpacked/resources/assets/ffmpeg.exe` 存在。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| `files` 白名单写过头，把**运行期必需**文件排掉（应用打出来但起不来） | 中 | 高 | 实跑打包后逐项断言 `out/main/index.js`、`out/preload/index.js`、`out/renderer/index.html`、`resources/assets/ffmpeg.exe` 均存在；门禁断言 `files` 为**纯排除**写法（`containsOnlyIgnore` ⇒ electron-builder 自动前置 `**/*`，`fileMatcher.js:119-121` 源码实证） |
| 删除 yml 后有人重新引入第二份配置 | 中 | 高 | 新门禁 `verify_packaging_config.mjs` 断言「`electron-builder.yml` 与 `package.json.build` 不得并存」 |
| 未来有人删除 `npmRebuild` 又退回 30 分钟超时 | 中 | 中 | 门禁断言 `npmRebuild` 必须**显式声明**（未声明 = 默认 `true` = 陷阱） |
| CI 上打包行为与本机不同 | 中 | 中 | 本次**不接 CI 打包 job**（见 §7）；仅接静态门禁（可在 CI 稳定运行） |
| 门禁本身空转（扫不到任何违规） | 低 | 高 | 按 AGENTS §2.1 第 14 条，交付前必须先造「它本该抓住」的反例并确认报红 |

## 7. 依赖与前提

- 前提：`easychat-front/node_modules` 已安装（`sqlite3` 等生产依赖齐全）。
- **本次刻意不新增 CI 打包 job**：`electron-builder.yml` 属 L4，而 CI 打包的可运行性
  在本机**无法完全验证** —— 本机因未启用开发者模式（`AllowDevelopmentWithoutDevLicense = 0`、
  非管理员、`symlinkSync` 返回 `EPERM`）无法解压 `winCodeSign`，
  实跑打包必须叠加 `-c.win.signAndEditExecutable=false` 才能通过。
  按 AGENTS §2.1 第 1 条「门禁必须先在当前 main 上跑通才允许接入」，
  该项**另开任务**并需人工确认，本 Change 只交付已实跑通过的静态门禁。
- 本机实跑基线（`-c.npmRebuild=false -c.win.signAndEditExecutable=false`）：
  `exit=0`，产出 `EasyChatSetup.1.0.0.exe` 197.0 MB、`app.asar` 274.7 MB。
  **注意该基线含 `signAndEditExecutable=false`（跳过 rcedit 图标/元数据改写）**，
  故它只证明「文件能组装成安装包」，**不证明**图标与版本元数据能正确写入 exe。