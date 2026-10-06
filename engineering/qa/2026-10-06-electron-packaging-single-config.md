# QA — 消除 Electron 打包配置双份

- Change: `openspec/changes/2026-10-06-electron-packaging-single-config/`
- 等级: L4
- 日期: 2026-10-06
- 平台: win32 / node v24.0.2 / electron-builder 24.13.3

## 1. 范围

| 项 | 内容 |
|---|---|
| 删除 | `easychat-front/electron-builder.yml`（自首个 commit 起从未生效的死配置） |
| 修改 | `easychat-front/package.json` 的 `build`：新增 `npmRebuild: false` 与 `files` 白名单 |
| 新增 | `scripts/verify/verify_packaging_config.mjs` |
| 接入 | `.github/workflows/ci.yml` 的 `gates` job（**仅静态门禁**） |
| 文档 | `AGENTS.md` §10、`scripts/README.md`、`docs/system-facts.md` §3.1 与 §14 #28 |
| **未改** | `src/main` / `src/preload` / `src/renderer` **零改动**；`asarmor.js` 仅登记未修 |

## 2. 验收口径与实测

### 2.1 核心指标（改造前 → 改造后）

| 指标 | 改造前 | 改造后 | 结论 |
|---|---|---|---|
| 打包耗时 | **1800s 超时被杀，exit≠0** | **223.3s，exit=0** | ✓ |
| `app.asar` | 274.7 MB | **84.7 MB**（↓69.2%） | ✓ |
| 安装包 `EasyChatSetup.1.0.0.exe` | 197.0 MB | **138.1 MB**（↓29.9%） | ✓ |

> 改造前的 1800s 超时是**实测**的：日志停在
> `rebuilding native dependencies dependencies=@parcel/watcher@2.5.1, sqlite3@5.1.6`。

### 2.2 逐条命令与退出码

| 命令 | 退出码 |
|---|---|
| `node scripts/verify/verify_packaging_config.mjs`（未修复仓库，红阶段） | **1** |
| `node scripts/verify/verify_packaging_config.mjs`（修复后，绿阶段） | **0** |
| `node scripts/verify/verify_packaging_config.mjs --selftest` | **0**（21/21 一致） |
| 阶段零反例脚本（三条反例） | **0**（R1/R2/R3 各自 exit=1，还原后 exit=0） |
| `npx electron-vite build` | **0**（82.7s） |
| `npx electron-builder --win -c.win.signAndEditExecutable=false` | **0**（223.3s） |
| 阶段三产物断言脚本 | **0**（22/22 通过） |
| `node scripts/check-openspec-hygiene.mjs` | **0** |

> 改造后的打包**未传任何 `npmRebuild` 覆盖参数**，日志为
> `skipped dependencies rebuild  reason=npmRebuild is set to false`
> —— 证明生效的是**配置本身**，而非命令行。

### 2.3 阶段零反例（§2.1 第 14 条）

| 反例 | 造的坏配置 | 门禁结果 |
|---|---|---|
| R1 | 让 `electron-builder.yml` 与 `package.json.build` 并存 | exit=1，`[FAIL] 存在第二份永不生效的打包配置` |
| R2 | 删掉 `build.npmRebuild` | exit=1，`[FAIL] build.npmRebuild 未显式声明` |
| R3 | 从 `files` 移除 `!src/**` | exit=1，`[FAIL] files 未排除 src/main/index.js` |

三条**全部报红**，且脚本在 `finally` 中还原、还原后基线复跑 exit=0。
（刻意**不用** `git checkout` 还原 —— 那会连带回滚未提交的正式改动。）

### 2.4 阶段三产物断言（22/22）

- 安装包：`EasyChatSetup.1.0.0.exe` 存在、**MZ 头**（真 PE）、138.1 MB、`.blockmap` 已生成
- 运行期必需文件经 `extraResources` 全部在位：
  `resources/assets/ffmpeg.exe`(62.6MB)、`ffprobe.exe`(125.8MB)、`404.png`、`user.png`
- `app.asar` 内**已排除**：`src/main/index.js`、`src/renderer/src/__tests__/setup.js`、
  `src/renderer/src/views/chat/Chat.vue`、`.eslintrc.cjs`、`.npmrc`、`vitest.config.mjs`、
  `electron.vite.config.js`、`.editorconfig`、`.prettierrc.yaml`、`asarmor.js`、`AGENTS.md`、
  `assets/ffmpeg.exe`、`resources/icon.ico`、`electron-builder.yml`
- `app.asar` 内**保留**：`package.json`、`out/main/index.js`、`out/preload/index.js`、
  `out/renderer/index.html`

> 读的是 `app.asar` 的**头部 JSON 索引**（非目录列表、非字符串匹配），
> 故「排除」判定不依赖 electron-builder 的日志声称。

## 3. 关键论证链（每条均有实证，非推断）

| 断言 | 证据 |
|---|---|
| `electron-builder.yml` 从未生效 | `read-config-file/out/main.js:71-72`：`packageMetadata[packageKey]` 存在即返回，`findAndReadConfig` 永不被调用；构建日志 `loaded configuration file=package.json ("build" field)`；产物名 `EasyChatSetup.1.0.0.exe` 用的是 package.json 的 `productName` |
| 该 yml 自首个 commit 起就无效 | `git log --diff-filter=A` 显示 yml 与 package.json 的 `build` 同在 `a102bd9 first commit` |
| `npmRebuild` 默认 `true` | `app-builder-lib/scheme.json`：`"npmRebuild": {"default": true}` |
| `npmRebuild: false` **安全** | 生产依赖闭包 **215 个包**中含 `.node` 的**仅 `sqlite3`**，且为 `napi-v6`（Node-API，跨 ABI 稳定） |
| `files` 纯排除写法合法 | `fileMatcher.js:119-121`：`containsOnlyIgnore()` 时自动前置正向全匹配模式 |
| 门禁的 glob 语义 == 打包器语义 | `--selftest` 与真实 `FileMatcher` 对拍 **21/21 一致** |
| `asarUnpack` 无需搬入 | `file.js:27-28,40-46`：`ffmpegPath="/assets/ffmpeg.exe"`，生产环境 `getResourcesPath()` = `<exe目录>/resources`，即资源经 `extraResources` 在 asar **之外** |

## 4. 未运行 / 未验证项（如实登记）

| 项 | 状态 | 原因 |
|---|---|---|
| **CI 上的打包 job** | **刻意未加** | 本机无法验证 CI 环境行为；§2.1 第 1 条要求先跑通才允许接入。本 Change 只交付**已在 main 上跑通**的静态门禁 |
| **exe 图标 / 版本元数据是否正确写入** | **未验证** | 本机 `AllowDevelopmentWithoutDevLicense = 0`、非管理员、`symlinkSync` 返回 `EPERM` ⇒ 7-Zip 无法解压 `winCodeSign`（需创建符号链接），故本机打包必须叠加 `-c.win.signAndEditExecutable=false`，**该参数跳过 rcedit**。基线 1800s 超时同样死于此（但死因不同，见 §5） |
| **macOS 打包** | **未验证，且未补配置** | 无 mac 环境。yml 中的 `mac.entitlementsInherit` 指向不存在的 `build/entitlements.mac.plist`，但因 yml 从未生效，**该问题在本 Change 之前并不存在**；生效配置里的 `mac.icon: icons/icon.icns` 缺文件，但 electron-builder 对缺 mac 图标通常降级而非报错 —— **无环境，不下结论** |
| **自动更新** | 不在范围 | 源码中 `electron-updater` / `autoUpdater` / `checkForUpdates` **0 处引用**，从未接线。yml 的 `publish.url` 是死配置里的死配置 |
| **改动能否启动运行** | 未验证 | 只验证了「文件能组装成产物」，**未启动过打包后的应用** |

## 5. 一处需要澄清的失败归因

改造前 1800s 超时的直接原因是**重编原生依赖**（日志明确）。
中间一次 `--dir` 实跑 exit=1 是**另一个原因**：
`ERROR: Cannot create symbolic link : 客户端没有所需的特权` —— 本机未启用开发者模式导致。
二者**不可混为一谈**，前者由本 Change 修复，后者是本机环境限制、**本 Change 未修复也不应修复**。

## 6. 结论

**通过。** 阶段零/一/二/三全部达成，验收口径逐条有实跑证据，退出码已贴出。

遗留与后续见 `engineering/retro/2026-10-06-electron-packaging-single-config.md`。

**证据文件**：`engineering/qa/evidence/2026-10-06-electron-packaging-single-config.txt`
（含改造后打包原始输出、三条反例记录、`--selftest` 输出、改造前超时基线）。