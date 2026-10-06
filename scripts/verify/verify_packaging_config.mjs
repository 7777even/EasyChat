#!/usr/bin/env node
/**
 * verify_packaging_config.mjs —— Electron 打包配置唯一真源门禁
 *
 * 背景（2026-10-06，openspec/changes/2026-10-06-electron-packaging-single-config，L4）：
 *   仓库里曾同时存在 `easychat-front/electron-builder.yml` 与 `package.json` 的 `build` 字段，
 *   二者逐项冲突。经实跑与源码双重核实，**yml 自首个 commit 起就从未生效过**：
 *     · electron-builder 依赖 read-config-file，其 `loadConfig` 明确写着
 *       `packageMetadata[packageKey]` 存在即直接返回，`findAndReadConfig` **永不被调用**；
 *     · 构建日志只有一行 `loaded configuration file=package.json ("build" field)`，
 *       且淹没在数十行进度输出里 —— **没有任何警告**。
 *
 *   yml 中两项意图从未生效，已造成两个实际问题：
 *     1. `npmRebuild: false` 失效 → 每次打包从源码重编 sqlite3 + @parcel/watcher，
 *        本机实跑 **1800 秒超时未完成**，安装包根本产不出来；
 *     2. `files` 白名单失效 → `src/`（含 `__tests__/`）、`.eslintrc.cjs`、
 *        `vitest.config.mjs`、`.npmrc` 全部进入 `app.asar`；
 *        `assets/`（188 MB 的 ffmpeg/ffprobe）与 `extraResources` 重复打包。
 *
 * 最危险的不是配置写错，而是**它看起来是活的**：下一个人照着改 yml，改完毫无效果，
 * 却不会想到去查为什么。本仓已在同类「说一套、跑另一套」上连续栽过。
 *
 * 本门禁把「唯一真源」与「关键键必须显式声明」钉成机控断言。
 *
 * ⚠️ 本门禁只做**静态**校验。它**不能**证明打包一定成功 ——
 *   产物正确性由 `openspec/changes/.../tasks.md` 阶段三的**活体打包实跑**兜底。
 *
 * 用法：
 *   node scripts/verify/verify_packaging_config.mjs             常规静态校验
 *   node scripts/verify/verify_packaging_config.mjs --selftest  与 electron-builder 真实
 *                                                             打包器对拍本门禁的 glob 语义
 *
 * 退出码（常规）：0 通过 / 1 有违规
 * 退出码（--selftest）：0 全部一致 / 1 语义漂移 / 2 依赖缺失而 SKIP（**SKIP 不等于通过**）
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join, sep } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const FRONT = join(ROOT, 'easychat-front')
const PKG = join(FRONT, 'package.json')
const YML = join(FRONT, 'electron-builder.yml')

const failures = []
const checks = []
function check (ok, label, detail) {
  checks.push(ok)
  if (!ok) failures.push({ label, detail })
}

// ── glob 匹配（用于判定「某个文件是否被排除」）────────────────────
// 刻意不依赖 easychat-front/node_modules 里的 minimatch：
//   CI 的 gates job 不执行 `npm ci`，依赖该包会让门禁在 CI 上不可运行。
// 语义与 minimatch 对本门禁所需子集一致：支持 `{a,b}`、`**`、`*`、`?`。
function expandBraces (pattern) {
  const m = /\{([^{}]*)\}/.exec(pattern)
  if (!m) return [pattern]
  const head = pattern.slice(0, m.index)
  const tail = pattern.slice(m.index + m[0].length)
  return m[1].split(',').flatMap((part) => expandBraces(head + part + tail))
}

function toRegExp (pattern) {
  let re = ''
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i]
    if (c === '*') {
      if (pattern[i + 1] === '*') {
        // `**/` 匹配零或多层目录；其余 `**` 匹配任意字符
        if (pattern[i + 2] === '/') { re += '(?:.*/)?'; i += 2 } else { re += '.*' }
      } else {
        re += '[^/]*'
      }
    } else if (c === '?') {
      re += '[^/]'
    } else {
      re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&')
    }
  }
  return new RegExp('^' + re + '$')
}

/**
 * 判定文件相对路径 `rel` 是否会被 `files` 排除（即不进 app.asar）。
 *
 * ⚠️ 语义**必须**与 electron-builder 完全一致，否则门禁会给出与真实打包相反的结论。
 *   真实实现在 `app-builder-lib/out/util/filter.js` 的 `minimatchAll`：
 *   它不是「首个命中即决定」，而是**交替状态机** ——
 *     · 仅当 `match !== pattern.negate` 时才评估该模式（正向只在未包含时评估，
 *       否定只在已包含时评估）；`match` 随评估结果在 true/false 间翻转。
 *   故纯排除写法能生效，全靠 `fileMatcher.js:119-121` 自动前置的正向全匹配模式
 *   先把 `match` 置为 true。
 *
 *   2026-10-06 本函数曾两次写反（返回值取反、跳过条件用相等而非不等），
 *   均由「与 electron-builder 真实 FileMatcher 对拍」暴露 —— 见文件末尾 --selftest。
 */
function isExcluded (files, rel) {
  const pats = files.flatMap((p) => expandBraces(String(p)))
  const effective = pats.some((p) => !p.startsWith('!')) ? pats : ['**/*', ...pats]
  let match = false
  for (const p of effective) {
    const neg = p.startsWith('!')
    if (match !== neg) continue
    const hit = expandBraces(neg ? p.slice(1) : p).some((one) => toRegExp(one).test(rel))
    match = neg ? !hit : hit
  }
  return !match
}

// ══════════════════════════════════════════════════════════════
// --selftest：与 electron-builder **真实**的 FileMatcher 对拍
//
// 为什么必须常驻：本门禁自己重写了一套 glob 判定。若它与打包器漂移，
//   门禁会给出与真实产物**相反**的结论 —— 而门禁全绿时没人会怀疑它。
//   2026-10-06 本函数已两次写反（返回值取反、跳过条件用相等而非不等），
//   两次都是靠这个对拍暴露的，纯靠读代码看不出来。
//
// 依赖 easychat-front/node_modules（app-builder-lib + minimatch）。
//   CI 的 gates job 不执行 `npm ci`，故此处缺依赖时**报 SKIP 并以退出码 2 区分**，
//   绝不静默当作通过（SKIP 不等于通过）。
// ══════════════════════════════════════════════════════════════
if (process.argv.includes('--selftest')) {
  console.log('===== 打包配置门禁 · 自检（与 electron-builder 真实语义对拍）=====\n')
  const abDir = join(FRONT, 'node_modules', 'app-builder-lib', 'out')
  const mmDir = join(FRONT, 'node_modules', 'minimatch')
  if (!existsSync(join(abDir, 'fileMatcher.js')) || !existsSync(mmDir)) {
    console.log('   [SKIP ] 缺少 easychat-front/node_modules 下的 app-builder-lib / minimatch')
    console.log('           请先在 easychat-front 执行 `npm ci`，再重跑 --selftest。')
    console.log('           ⚠️ SKIP 不等于通过：本次并未验证门禁语义是否与打包器一致。')
    process.exit(2)
  }

  const { createRequire } = await import('node:module')
  const req = createRequire(join(abDir, 'fileMatcher.js'))
  const { FileMatcher } = req('./fileMatcher.js')

  // 与 fileMatcher.js:18-20 一致（electron-builder 24.13.3）
  const EXCLUDED_EXTS = 'iml,hprof,orig,pyc,pyo,rbc,swp,csproj,sln,suo,xproj,cc,d.ts,mk,a,o,forge-meta'
  const EXCLUDED_NAMES = '.DS_Store,.git,.gitattributes,.gitignore,.gitmodules,' +
    'appveyor.yml,.travis.yml,circle.yml,.nyc_output,.husky,.github,electron-builder.env'

  const pkgForSelf = JSON.parse(readFileSync(PKG, 'utf8'))
  const fm = new FileMatcher(FRONT, join(FRONT, '__out__'), (s) => s, pkgForSelf.build?.files)
  const ps = fm.patterns
  // 复刻 fileMatcher.js:119-143 的装配顺序
  const first = []
  if (!fm.isSpecifiedAsEmptyArray && (fm.isEmpty() || fm.containsOnlyIgnore())) first.push('**/*')
  first.push('!**/node_modules')
  const relOut = fm.normalizePattern('dist')
  if (!relOut.startsWith('.')) first.push(`!${relOut}{,/**/*}`)
  ps.splice(0, 0, ...first)
  ps.push(`!**/*.{${EXCLUDED_EXTS},pdb}`)
  ps.push('!**/._*')
  ps.push('!**/electron-builder.{yaml,yml,json,json5,toml,ts}')
  ps.push(`!**/{${EXCLUDED_NAMES}}`)
  ps.push('!.yarn{,/**/*}')
  ps.push('!.editorconfig')
  ps.push('!.yarnrc.yml')
  const realFilter = fm.createFilter()

  const PROBES = [
    'src/main/index.js', 'src/main/db/ADB.js', 'src/renderer/src/__tests__/setup.js',
    'out/main/index.js', 'out/preload/index.js', 'out/renderer/index.html',
    'resources/icon.png', 'resources/icon.ico',
    'assets/ffmpeg.exe', 'assets/404.png',
    '.eslintrc.cjs', '.npmrc', 'vitest.config.mjs', 'electron.vite.config.js',
    '.editorconfig', '.prettierrc.yaml', 'AGENTS.md',
    'package.json', 'package-lock.json', '.env', '.env.production'
  ]

  const stat = { isDirectory: () => false }
  let mismatch = 0
  for (const rel of PROBES) {
    const abs = FRONT + sep + rel.split('/').join(sep)
    const realIncluded = realFilter(abs, stat)
    const mineExcluded = isExcluded(pkgForSelf.build?.files ?? [], rel)
    const ok = realIncluded === !mineExcluded
    if (!ok) {
      mismatch++
      console.log(`   [FAIL ] ${rel}  打包器=${realIncluded ? '包含' : '排除'} 门禁=${mineExcluded ? '排除' : '包含'}`)
    }
  }
  const total = PROBES.length
  if (mismatch > 0) {
    console.log(`\n===== 结论：${total - mismatch} 一致 / ${mismatch} 不一致 =====`)
    console.log('门禁的 glob 语义已与 electron-builder 漂移，其结论不可信 —— 请修 isExcluded。')
    process.exit(1)
  }
  console.log(`   [PASS ] ${total}/${total} 探针与 electron-builder 真实语义一致`)
  console.log('\n✓ 门禁的排除判定 == 打包器的实际行为')
  process.exit(0)
}

/**
 * 从源码推导「必须存在于 app.asar 内」的文件集合。
 *
 * 为什么必须推导而不能手写清单：2026-10-06 实测中手写清单漏掉了托盘图标
 * `resources/icon.png`，打包后实跑才发现托盘图标加载失败 —— 见调用处注释。
 * 手写清单的覆盖率取决于「写清单的人当时想到了什么」，而运行期引用是代码的事实。
 *
 * 推导依据（两处，均可在 CI 无构建条件下静态得出）：
 *   ① `import x from '<相对路径>?asset'` —— electron-vite 主进程构建会把这类导入
 *      编译成 `path.join(__dirname, '<相对路径>')`，故按**产物所在目录**解析即为 asar 内路径。
 *      产物目录由 `package.json` 的 `main` 字段推导（如 `./out/main/index.js` → `out/main`）。
 *   ② 源码中 `join(__dirname, '<相对路径>')` 的字面量（主进程入口直接写死的运行时引用）。
 *
 * 返回 [[相对路径, 原因说明], ...]
 */
function collectRuntimeRequiredPaths (pkg) {
  const out = []
  const seen = new Set()
  const add = (rel, why) => {
    const norm = posixNormalize(rel)
    if (!norm || seen.has(norm)) return
    seen.add(norm)
    out.push([norm, why])
  }

  // 产物目录：main 字段形如 ./out/main/index.js → 取其所在目录 out/main
  const mainField = String(pkg.main || './out/main/index.js')
  const mainDir = posixNormalize(mainField).replace(/\/[^/]*$/, '') || 'out/main'
  const preloadDir = posixNormalize(mainField).replace(/\/[^/]*\/[^/]*$/, '') || 'out'

  // 需要扫描的源码目录（主进程与 preload —— 渲染进程的静态资源由 Vite 打进 out/renderer，
  // 不经 __dirname 直接读项目目录，故不在此列）
  const srcDirs = [
    { dir: join(FRONT, 'src', 'main'), base: mainDir },
    { dir: join(FRONT, 'src', 'preload'), base: preloadDir }
  ]

  let scanned = 0
  for (const { dir, base } of srcDirs) {
    let entries = []
    try {
      entries = readdirSync(dir).filter((f) => f.endsWith('.js'))
    } catch {
      continue
    }
    for (const f of entries) {
      let src = ''
      try {
        src = readFileSync(join(dir, f), 'utf8')
      } catch {
        continue
      }
      scanned++

      // ① ?asset 导入
      const reAsset = /from\s*["'](\.[^"']+)\?asset["']/g
      let m
      while ((m = reAsset.exec(src))) {
        // 产物中位于 <base>/index.js，故 __dirname = <base>，相对路径据此解析
        add(posixNormalize(posixJoin(base, m[1])),
          `由 ${f} 的 import ... '${m[1]}?asset' 编译而来；` +
          'electron-vite 会把它编成 path.join(__dirname, ...) —— 该文件必须在 asar 内，' +
          '否则运行期读取失败（如托盘图标加载不了）')
      }
      // ② 直接写死的 __dirname 相对路径
      const reDir = /join\(__dirname,\s*["'](\.[^"']+)["']\)/g
      while ((m = reDir.exec(src))) {
        add(posixNormalize(posixJoin(base, m[1])),
          `由 ${f} 中的 join(__dirname, '${m[1]}') 直接引用，必须在 asar 内`)
      }
    }
  }

  // ③ 主进程必然需要的三项（产物入口本身）
  add('package.json', 'app.asar 的根 manifest，Electron 读取 main 字段时需要')
  add(posixNormalize(mainField), 'package.json 的 main 字段指向的主进程入口')
  add(`${mainDir}/../preload/index.js`, '主进程以 join(__dirname, "../preload/index.js") 加载预加载脚本')
  add(`${mainDir}/../renderer/index.html`, '主进程以 loadFile(join(__dirname, "../renderer/index.html")) 加载界面')

  if (scanned === 0) {
    // 一个源文件都没扫到 ⇒ 推导不可信，调用处会把空集合报 FAIL
    return []
  }
  return out
}

function posixNormalize (p) {
  const parts = String(p).split('/')
  const stack = []
  for (const seg of parts) {
    if (seg === '' || seg === '.') continue
    if (seg === '..') { stack.pop(); continue }
    stack.push(seg)
  }
  return stack.join('/')
}
function posixJoin (base, rel) {
  return posixNormalize(base + '/' + rel)
}

console.log('===== Electron 打包配置门禁 =====\n')

// ── 解析器自检（§2.1 第 14 条：断言扫不到东西 ≠ 断言在做事）──────
let pkg = null
let parseErr = null
try {
  pkg = JSON.parse(readFileSync(PKG, 'utf8'))
} catch (e) {
  parseErr = e.message
}
if (!pkg || typeof pkg !== 'object') {
  console.log(`   [FAIL ] 解析器自检：读不到 ${PKG}（${parseErr}）`)
  console.log('   [FAIL ] 门禁无法工作，拒绝以「零违规」通过')
  console.log('\n===== 结论：1 失败 / 0 通过 =====')
  process.exit(1)
}
console.log(`   [CHECK] 已解析 ${PKG}（package name = ${pkg.name}）`)

const build = pkg.build ?? null
const hasYml = existsSync(YML)

console.log(`   [CHECK] package.json.build = ${build ? '存在' : '不存在'}`)
console.log(`   [CHECK] electron-builder.yml = ${hasYml ? '存在' : '不存在'}\n`)

// ── 断言 1：打包配置唯一真源 ──────────────────────────────────
check(
  !(build && hasYml),
  '存在第二份永不生效的打包配置',
  `electron-builder.yml 与 package.json 的 build 字段并存。\n` +
  '    electron-builder 依赖 read-config-file 的 loadConfig 会在 build 字段存在时' +
  '直接返回，\n    根本不会去读 yml —— 即 yml 被**静默忽略**，改它不会有任何效果。\n' +
  '    请二选一：删除 yml，或删除 build 字段（后者需自行确认默认值是否可接受）。'
)
check(
  !!(build || hasYml),
  '未找到任何打包配置',
  'package.json 的 build 字段缺失且无 yml —— 打包将全用默认值：' +
  'productName 退化为包名、appId 退化为占位符 com.electron.app。'
)

// ── 断言 2：npmRebuild 必须显式声明 ───────────────────────────
// 只有 build 存在时才有意义；yml 单独存在的情形由断言 1 报出，此处不重复。
if (build) {
  check(
    Object.prototype.hasOwnProperty.call(build, 'npmRebuild'),
    'build.npmRebuild 未显式声明',
    'electron-builder 的 npmRebuild 默认为 **true**，一旦未声明，每次打包都会' +
    '从源码重编原生依赖。\n    本项目 2026-10-06 实测：未声明时打包 1800 秒超时未完成；' +
    '声明 false 后跳过重编。\n' +
    '    置为 false 的安全性已核实：生产依赖闭包 215 个包中仅 sqlite3 含原生二进制，' +
    '且为 napi-v6\n    （Node-API，跨 Node/Electron ABI 稳定）。'
  )
}

// ── 断言 3/4：files 白名单必须排除开发文件与重复资源 ───────────
/** 必须排除：相对路径 → 为什么 */
const MUST_EXCLUDE = [
  ['src/main/index.js', '源码目录（yml 死配置下连同 __tests__ 一并进了 app.asar）'],
  ['src/renderer/src/__tests__/setup.js', '测试代码'],
  ['.eslintrc.cjs', '开发配置'],
  ['vitest.config.mjs', '测试配置'],
  ['.npmrc', '包源配置（含私有镜像地址）'],
  ['electron.vite.config.js', '构建配置'],
  ['assets/ffmpeg.exe', '与 extraResources 重复打包（同一份资源在产物中存了两遍）']
]
// ⚠️ 本清单里的每一条都必须是**当前真实存在**的文件。
//   `asarmor.js` 曾在此列，但 2026-10-06 已连同其 devDependency 一并删除
//   （afterPack 从未配置 ⇒ 从未执行，见 docs/system-facts.md 变更记录）。
//   为已删除的文件保留断言 = 断言扫不到任何东西却照样通过，正是 AGENTS §2.1 第 14 条
//   点名的「断言通过 ≠ 断言在做事」。故此处不再列它。

if (build) {
  const files = build.files
  if (!Array.isArray(files) || files.length === 0) {
    check(false, 'build.files 未配置', '缺少 files 白名单时 electron-builder 走默认 **/*，' +
      '开发文件会被打进 app.asar。')
  } else {
    // 断言 5：解析器自检 —— files 真的被解析出来了
    const expanded = files.flatMap((p) => expandBraces(String(p)))
    check(expanded.length >= 5, 'files 解析异常',
      `仅解析出 ${expanded.length} 条模式（应 ≥5），疑似解析器与实际配置失配。`)

    for (const [rel, why] of MUST_EXCLUDE) {
      const excluded = isExcluded(files, rel)
      check(excluded, `files 未排除 ${rel}`, `原因：${why}\n    补充：\n    该项会随 app.asar 分发给所有客户端。`)
    }

    // 反向断言：误排除运行期必需文件同样要报红
    // （只报「该排的没排」会让白名单被无脑写成 `!out/**` 而无人察觉）
    //
    // ⚠️ 这里的清单**不能靠手写猜测**。2026-10-06 实测踩中：
    //   我手写了 3 条 out/* 就以为覆盖了运行期依赖，打包后**实跑**才发现托盘图标挂了 ——
    //     Error: Failed to load image from path '...app.asar\resources\icon.png'
    //     at createWindow (...app.asar\out\main\index.js:2260)
    //   原因是 `src/main/index.js` / `ipc.js` 里的 `import icon from '../../resources/icon.png?asset'`
    //   在产物中变成 `path.join(__dirname, '../../resources/icon.png')`（见 out/main/index.js:7），
    //   即**托盘图标必须在 asar 内**。我漏了它，白名单里的 `!resources/**` 直接打断了启动。
    // 故下方改为**从源码推导**：扫主进程/预加载源码的 `?asset` 导入与 `__dirname` 相对路径，
    // 自动得出「必须在 asar 内」的文件集合，再逐个断言未被排除。
    const REQUIRED_IN_ASAR = collectRuntimeRequiredPaths(pkg)
    check(
      REQUIRED_IN_ASAR.length > 0,
      '无法从源码推导出运行期必需文件集合',
      '推导结果为空 —— 可能是 src/main 目录结构变了、或解析器失配。\n' +
      '    此时下方「误排」断言全部失效（会静默通过），必须报出来。'
    )
    for (const [rel, why] of REQUIRED_IN_ASAR) {
      check(!isExcluded(files, rel), `files 误排了运行期必需文件 ${rel}`, why)
    }

    // 提示：asarUnpack 若存在，确认其目标并非本项目实际依赖的 extraResources 产物
    if (build.asarUnpack) {
      console.log('   [INFO ] build.asarUnpack 已配置，请确认其目标不在 extraResources 中（否则重复）')
    }
  }

  // 提示：extraResources 与 files 同时包含同一路径时会重复打包
  const er = Array.isArray(build.extraResources) ? build.extraResources.join(',') : ''
  if (er.includes('assets')) {
    check(
      isExcluded(build.files ?? [], 'assets/ffmpeg.exe'),
      'assets 同时出现在 extraResources 与 files 中',
      '同一份资源会在 resources/ 与 app.asar 里各存一份。'
    )
  }
}

// ── 汇总 ────────────────────────────────────────────────────
const passed = checks.filter(Boolean).length
console.log(`   断言 ${checks.length} 项：通过 ${passed} / 失败 ${failures.length}`)

if (failures.length > 0) {
  console.log('')
  failures.forEach((f, i) => {
    console.log(`   [FAIL ] ${i + 1}. ${f.label}`)
    f.detail.split('\n').forEach((l) => console.log(`           ${l}`))
  })
  console.log('')
  console.log('阻断：请先修好上述打包配置再提交。')
  console.log('  关联规格：openspec/specs/desktop-packaging/spec.md')
  process.exit(1)
}

console.log('')
console.log('✓ 打包配置唯一真源，且关键键均已显式声明')
console.log('  注意：本门禁为静态校验，不证明打包一定成功 ——')
console.log('        产物正确性需实跑 electron-builder 验证。')
process.exit(0)