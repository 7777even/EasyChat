#!/usr/bin/env node
/**
 * verify_frontend_test_base.mjs —— 前端测试基线契约守护
 *
 * 背景（openspec/changes/2026-10-04-frontend-vitest-baseline）：
 *   引入 vitest 时最大的风险不是测试写不出来，而是「装测试框架顺带升级了 vite，
 *   把 Electron 构建拖坏」。本门禁把该风险固化为机控断言：
 *
 *   ① 三个测试依赖**钉死版本**（带 ^ 会某天自动升到 vitest 2/3/5，
 *      连带要求 vite 5+，直接拖坏 electron-vite@1）
 *   ② **vite 仍是 4.x** —— 一旦被顺带升级即阻断
 *   ③ **生产依赖未被测试框架污染**
 *   ④ vitest 配置存在且声明了 jsdom 环境与 @ 别名
 *   ⑤ @ 别名与 electron-vite 配置**两处一致**（两处各写一遍，需同步）
 *   ⑥ 全局桩文件存在且含 ResizeObserver 桩（缺它组件挂载即崩，属高频坑）
 *   ⑦ test 脚本存在，且 CI 会跑它
 *
 * 用法：node scripts/verify/verify_frontend_test_base.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const read = (p) => readFileSync(resolve(ROOT, p), 'utf8')
const has = (p) => existsSync(resolve(ROOT, p))

let pass = 0
let fail = 0
function check (name, cond, detail = '') {
  if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
    fail++
    console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
  }
}

console.log('===== 前端测试基线契约验证 =====\n')

const PKG = 'easychat-front/package.json'
const pkg = JSON.parse(read(PKG))
const dev = pkg.devDependencies || {}
const deps = pkg.dependencies || {}

// ── 1. 测试依赖存在且钉死版本 ────────────────────────────────
console.log('=== 1. 测试依赖（devDependency，钉死版本）===')
const REQUIRED = {
  vitest: /^1\./,
  '@vue/test-utils': /^2\./,
  jsdom: /^2[0-3]\./
}
for (const [name, range] of Object.entries(REQUIRED)) {
  const v = dev[name]
  check(`${name} 已加入 devDependencies`, !!v, v ? v : '缺失')
  if (v) {
    // 范围前缀会在某天自动升级；vitest 2/3/5 的 peer 要求 vite 5+，
    // 会连带升级 vite 并拖坏 electron-vite@1 —— 故必须精确版本。
    check(`${name} 版本已钉死（无 ^ / ~）`, !/^[\^~]/.test(v), `实际 "${v}"`)
    check(`${name} 版本落在预期范围 ${range.source}`, range.test(v), `实际 ${v}`)
  }
  // 绝不能出现在生产依赖里（否则会打进 electron 产物）
  check(`${name} 未混入生产依赖`, !(name in deps))
}

// ── 2. vite 未被顺带升级 ────────────────────────────────────
console.log('\n=== 2. 构建工具链未被顺带升级 ===')
const viteV = dev.vite || ''
const major = parseInt(viteV.replace(/^[^\d]*/, ''), 10)
check('vite 仍为 4.x（vitest 2+/3+/5+ 的 peer 要求 vite 5+，会拖坏 electron-vite@1）',
  major === 4, `当前 ${viteV || '未声明'}；若为 5+ 说明 vitest 升级时连带升了 vite`)
const electronViteV = dev['electron-vite'] || ''
check('electron-vite 仍为 1.x', parseInt(electronViteV.replace(/^[^\d]*/, ''), 10) === 1,
  `当前 ${electronViteV || '未声明'}`)

// ── 3. 生产依赖纯净 ─────────────────────────────────────────
console.log('\n=== 3. 生产依赖纯净 ===')
const FRAMEWORK_IN_DEPS = ['vitest', 'jsdom', '@vue/test-utils', 'jest', 'happy-dom']
const polluted = FRAMEWORK_IN_DEPS.filter((k) => k in deps)
check('生产依赖不含任何测试框架', polluted.length === 0,
  polluted.length ? `污染项：${polluted.join(', ')}` : '')

// ── 4. vitest 配置 ──────────────────────────────────────────
console.log('\n=== 4. vitest 配置 ===')
const VC = 'easychat-front/vitest.config.mjs'
check('vitest.config.mjs 存在', has(VC))
if (has(VC)) {
  const vc = read(VC)
  check("声明了 jsdom 环境（组件依赖 DOM 与浏览器能力）",
    /environment:\s*['"]jsdom['"]/.test(vc))
  // ⚠ 漏掉 @vitejs/plugin-vue 会报
  //   "Failed to parse source for import analysis ... Install @vitejs/plugin-vue"
  //   而 vitest **不会**自动启用它（该插件只对 electron-vite 构建生效）。实测踩过。
  check('挂载了 @vitejs/plugin-vue（否则 .vue 无法解析）',
    /plugin-vue/.test(vc) && /plugins:\s*\[/.test(vc))
  check('声明了 @ 别名', /['"]@['"]:\s*resolve/.test(vc))
  check('setupFiles 指向全局桩文件', /setupFiles:/.test(vc))
  check('include 限定在 __tests__ 下', /__tests__/.test(vc))
}

// ── 5. 别名两处一致 ─────────────────────────────────────────
console.log('\n=== 5. 别名两处一致（各写一遍，需同步）===')
const EV = 'easychat-front/electron.vite.config.js'
if (has(EV) && has(VC)) {
  // ⚠ 别名在两处的写法不同：electron-vite 用 `resolve('src/renderer/src')`，
  //   vitest 用 `resolve(__dirname, 'src/renderer/src')`。正则必须同时覆盖
  //   单参 resolve 与双参 resolve，否则会误报「不一致」（实测踩过）。
  const grab = (src) => {
    const m = src.match(/['"]@['"]:\s*resolve\(\s*(?:__dirname\s*,\s*)?['"]([^'"]+)['"]\s*\)/)
      || src.match(/['"]@['"]:\s*resolve\(\s*['"]([^'"]+)['"]\s*\)/)
    return m ? m[1].replace(/\\/g, '/') : null
  }
  const a = grab(read(EV))
  const b = grab(read(VC))
  check('两处 @ 别名目标一致', !!a && !!b && a === b,
    `electron-vite: ${a}；vitest: ${b}；两者须指向同一目录`)
} else {
  check('两处配置文件都存在', false, '缺 electron.vite.config.js 或 vitest.config.mjs')
}

// ── 6. 全局桩 ───────────────────────────────────────────────
console.log('\n=== 6. 全局环境桩（缺任一则组件挂载即崩）===')
const SETUP = 'easychat-front/src/renderer/src/__tests__/setup.js'
check('setup.js 存在', has(SETUP))
if (has(SETUP)) {
  const s = read(SETUP)
  // MessageVirtualList 用 ResizeObserver 驱动虚拟滚动，无桩则 mount 即抛
  check('含 ResizeObserver 桩', /ResizeObserver/.test(s))
  check('含 IntersectionObserver 桩', /IntersectionObserver/.test(s))
  check('含 matchMedia 桩（Element Plus 断点依赖）', /matchMedia/.test(s))
  // 主进程桥接：组件若真的去调会抛错；Proxy 桩保证「调到不存在的通道」立刻暴露
  check('含主进程桥接桩（不得让测试触达真实 IPC）', /ipcRenderer|Proxy/.test(s))
  check('含 crypto.randomUUID 桩（useCallStore 依赖）', /randomUUID/.test(s))
}

// ── 7. test 脚本与 CI ───────────────────────────────────────
console.log('\n=== 7. test 脚本与 CI 接入 ===')
const scripts = pkg.scripts || {}
check('存在 test 脚本', !!scripts.test, scripts.test ? `= ${scripts.test}` : '缺失')
check('test 脚本使用 vitest run（非 watch）',
  /vitest\s+run/.test(scripts.test || ''), scripts.test)
check('存在 test:watch 脚本', !!scripts['test:watch'])
const CI = '.github/workflows/ci.yml'
check('ci.yml 存在', has(CI))
if (has(CI)) {
  const ci = read(CI)
  check('CI 已执行 npm run test', /npm run test/.test(ci))
}

// ── 8. 挂载能力被真正使用（2026-10-06 新增）──────────────────
//
// 为什么需要这一节：前面 1~7 节全部在断言「**具备**挂载能力」
// （装了 @vue/test-utils / jsdom、挂了 plugin-vue、桩齐全）。
// 但 2026-10-06 之前的实测是：这些依赖**全部就绪、门禁全绿**，
// 而全仓 **0 处 `mount(`、0 处 import @vue/test-utils** ——
// 即「没人挂载」。门禁锁住了工具，却没锁住**使用**。
// 这正是 2026-10-03「死组件静默不渲染」能长期存活的环境条件：
// 源码文本断言能发现「分支缺失」，发现不了「分支在、组件却没渲染」。
console.log('\n=== 8. 挂载能力被真正使用（否则 1~7 节只是在锁工具箱）===')
const SPEC_DIR = 'easychat-front/src/renderer/src/__tests__'
const specs = has(SPEC_DIR)
  ? readdirSync(resolve(ROOT, SPEC_DIR)).filter((f) => f.endsWith('.spec.js'))
  : []
console.log(`   （实扫 ${specs.length} 个 spec）`)

let mountSpecs = []
for (const f of specs) {
  const src = readFileSync(resolve(ROOT, SPEC_DIR, f), 'utf8')
  // 剥注释后判定 —— 注释里写「mount」的说明不应算作「有挂载测试」
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
  // ⚠️ 判据是 **import 了 @vue/test-utils**，而不是「文本里出现 mount(」——
  //   我第一版用 `/\bmount\s*\(/g` 计数，结果只匹配到 `function mountMsg(){ return mount(...) }`
  //   里的那一次，把两个实际调用 20+ 次 mount() 的 spec 误判成「只挂载了 3 处」。
  //   教训同 AGENTS §2.1 第 14 条：**计数前先确定「应有多少」**，
  //   靠正则数调用点会被封装形式骗到。
  const importsTU = /from\s*['"]@vue\/test-utils['"]/.test(code)
  const importsPinia = /from\s*['"]pinia['"]/.test(code)
  if (importsTU) mountSpecs.push(`${f}${importsPinia ? '(+pinia)' : ''}`)
}
check('至少存在 1 个 import @vue/test-utils 的 spec（真正挂载组件）', mountSpecs.length > 0,
  mountSpecs.length === 0
    ? '全仓无 spec import @vue/test-utils —— 依赖已安装并被本门禁锁定，'
      + '但没有任何测试在挂载组件（「具备能力」≠「有人在用」）'
    : '')
check('至少 2 个 spec 挂载组件（单一挂载点无法覆盖不同退化形态）', mountSpecs.length >= 2,
  `实扫 ${mountSpecs.length} 个：${mountSpecs.join(', ') || '无'}`)

// ── 汇总 ────────────────────────────────────────────────────
console.log(`\n===== 结论：${pass}/${pass + fail} 通过 =====`)
if (fail) {
  console.log(`失败 ${fail} 项`)
  process.exit(1)
}
process.exit(0)