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
| 新增 | `scripts/verify/verify_packaging_config.mjs`；CI `desktop-package` job |
| 接入 | `.github/workflows/ci.yml` 的 `gates` job（静态门禁）+ `frontend` job（`npm run build`） |
| 文档 | `AGENTS.md` §10、`scripts/README.md`、`docs/system-facts.md` §3.1 |
| 删除 | `electron-builder.yml`（死配置）；`asarmor.js` + devDep `asarmor`（第三份死配置，经人工拍板） |
| **未改** | `src/main` / `src/preload` / `src/renderer` **零改动** |

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
  `electron.vite.config.js`、`.editorconfig`、`.prettierrc.yaml`、`AGENTS.md`、
  `assets/ffmpeg.exe`、`electron-builder.yml`
  （`asarmor.js` 原在此列，该文件已于拍板后删除，故不再列为断言项 ——
  **为已删除的文件保留断言 = 断言扫不到东西却照样通过**，即 §2.1 第 14 条所警示的空转）
- `app.asar` 内**保留**：`package.json`、`out/main/index.js`、`out/preload/index.js`、
  `out/renderer/index.html`、**`resources/icon.png`**（托盘图标，见 §2.5）

> 读的是 `app.asar` 的**头部 JSON 索引**（非目录列表、非字符串匹配），
> 故「排除」判定不依赖 electron-builder 的日志声称。

### 2.5 实跑启动验证（补做，发现并修复了一个真实回归）

首轮交付时**只验证了「文件能组装成产物」，未验证「打包后的应用能启动」**。
补做该项后**发现首轮引入了一个真实回归**：

```
Error: Failed to load image from path 'D:\...\resources\app.asar\resources\icon.png'
    at createWindow (D:\...\resources\app.asar\out\main\index.js:2260:16)
(node:15816) UnhandledPromiseRejectionWarning: ...
```

- **表现**：应用能启动、SQLite 正常，但**托盘图标加载失败**。
  该错误是 `unhandled rejection`，**不会导致进程退出**，因此只看「进程是否存活」查不出来。
- **根因**：首轮的 `files` 白名单含 `!resources/**`，而
  `src/main/index.js:5` 与 `ipc.js:20` 有 `import icon from '../../resources/icon.png?asset'`，
  该导入在产物（`out/main/index.js:7`）中编译为
  `path.join(__dirname, "../../resources/icon.png")`
  ⇒ `resources/icon.png` **必须在 asar 内**。首轮的手写「运行期必需」清单只列了
  `out/` 三项，漏掉了它。
- **修复**：
  1. 移除 `files` 中的 `!resources/**`（两个图标合计 < 21 KB，无排除价值）；
  2. 门禁改为**从源码推导**「必须在 asar 内」的集合 ——
     扫 `src/main` / `src/preload` 的 `?asset` 导入与 `join(__dirname, ...)` 字面量，
     解析基准目录由 `package.json` 的 `main` 字段推导。**禁止手写该清单**，
     且推导结果为空时必须报 FAIL（否则该检查静默失效）。
- **回归证据（R4 反例）**：把 `!resources/**` 加回去 ⇒ 门禁 exit=1，
  且**精确点名** `files 误排了运行期必需文件 resources/icon.png`。

修复后重打包（`exit=0`，100.2s）并实跑 25 秒：

| 判据 | 修复前 | 修复后 |
|---|---|---|
| 主进程存活 | 是 | 是 |
| **stderr 行数** | **6（含 Tray 报错）** | **0** |
| 报错行数 | 3 | **0** |
| SQLite 建表已执行 | 是 | 是（证明主进程 + asar + sqlite3 原生模块均正常） |
| `app.asar` 内 `resources/icon.png` | 缺失 | **存在** |

最终产物断言 **23/23 通过**，`app.asar` 84.7 MB（vs 改造前 274.7 MB，↓69.1%），
安装包 138.1 MB。

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
| **CI 打包 job 的可运行性** | **未经本机验证**（人工拍板后接入） | 本机无符号链接权限，无法用默认参数完成打包；`windows-latest` runner 以管理员运行故预期可行，但**首次运行可能需按实况微调** |
| **exe 图标 / 版本元数据是否正确写入** | **未验证** | 本机 `AllowDevelopmentWithoutDevLicense = 0`、非管理员、`symlinkSync` 返回 `EPERM` ⇒ 7-Zip 无法解压 `winCodeSign`（需创建符号链接），故本机打包必须叠加 `-c.win.signAndEditExecutable=false`，**该参数跳过 rcedit**。基线 1800s 超时同样死于此（但死因不同，见 §5） |
| **macOS 打包** | **未验证，且未补配置** | 无 mac 环境。yml 中的 `mac.entitlementsInherit` 指向不存在的 `build/entitlements.mac.plist`，但因 yml 从未生效，**该问题在本 Change 之前并不存在**；生效配置里的 `mac.icon: icons/icon.icns` 缺文件，但 electron-builder 对缺 mac 图标通常降级而非报错 —— **无环境，不下结论** |
| **自动更新** | 不在范围 | 源码中 `electron-updater` / `autoUpdater` / `checkForUpdates` **0 处引用**，从未接线。yml 的 `publish.url` 是死配置里的死配置 |
| **改动能否启动运行** | **已验证**（见 §2.5）：实跑 25s，stderr 0 行，SQLite 正常 |
| **运行期文件引用是否有推导覆盖** | 已由门禁断言，且有 R4 反例实证 |

### 4.1 人工拍板后的补充变更

两项经用户明确拍板后执行：

**① 删除第三份死配置 `asarmor.js` + devDependency `asarmor`**
- 事实：`asarmor@^2.0.0` 已安装、`asarmor.js` 存在，但 `build.afterPack` **未配置**
  ⇒ 从未执行；源码中 0 处引用它。
- 删除理由：① 从未运行故从未被验证；② 它会改写 asar 内文件名，
  而托盘图标靠 `path.join(__dirname, '../../resources/icon.png')` 按路径读取
  （§2.5 已实证该路径），接线很可能破坏它；③ Electron 防护主要来自代码签名，
  而 `win.sign: null`（未签名）。
- 改动范围核对：`package.json` −1 行、`package-lock.json` −25 行，**纯删除，无其他变动**。

**② CI 构建覆盖（`frontend` job + 新增 `desktop-package` job）**
- `frontend` job 补 `npm run build` + 产物存在性断言
  （此前 CI **从不执行** `electron-vite build`，`out/` 从未在 CI 产生过）。
- 新增 `desktop-package`（`windows-latest`，timeout 30min）实跑 `npx electron-builder --win`，
  并 `upload-artifact` 上传安装包。
- 刻意**不传** `-c.npmRebuild`：该值必须由 `package.json` 的 `build` 提供并由静态门禁断言，
  传参数就等于掩盖了配置缺失。
- 刻意**不传** `-c.win.signAndEditExecutable`：只有以管理员运行的 windows runner
  才能解压 `winCodeSign` 并真正执行 rcedit。
- ⚠️ **该 job 的可运行性未经本机验证**（本机不具备符号链接权限）。
  这是**人工拍板后**才接入的，与 §2.1 第 1 条存在张力，**首次 CI 运行可能仍需按实况微调**
  （常见原因：GitHub Releases 下载超时）。已在此明确登记，不作「已验证」表述。

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