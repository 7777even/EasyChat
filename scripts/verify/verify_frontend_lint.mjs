#!/usr/bin/env node
/**
 * verify_frontend_lint.mjs —— 前端 lint 基线守护
 *
 * 背景（2026-10-04 两次真实事故）：
 *   原 `lint` 脚本自带 `--fix`。任何人（包括 AI）为「看一眼有没有 lint 问题」
 *   而执行 `npm run lint`，都会**重排全部源文件** —— 实测两次分别改动
 *   50 个文件（+4458 / −3524 行）与 45 个文件，含 Emoji.js / Request.js /
 *   cloudBackup.js 等与检查目的完全无关的文件。
 *
 *   第二次事故的性质更糟：**当时正在修改 `lint` 脚本本身**，
 *   改写工具因扩展名不匹配而失败，脚本仍是 `--fix` 版本，
 *   于是「执行以验证改动生效」这一步直接把 45 个文件格式化。
 *   即：**在修改一个有副作用的工具时执行它，等于触发它的副作用**。
 *
 * 本门禁守的：
 *   ① `lint` **不得**带 `--fix`（检查与修复必须分两条命令）
 *   ② `lint:fix` 存在且**带** `--fix`
 *   ③ eslint 配置已声明现代解析目标与运行环境（否则误报一片）
 *   ④ 当前 lint 的错误数不超过基线（防止新增问题被无视）
 *
 * 用法：node scripts/verify/verify_frontend_lint.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { readFileSync, existsSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const FRONT = join(ROOT, 'easychat-front')

let pass = 0
let fail = 0
function check (name, cond, detail = '') {
  if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
    fail++
    console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
  }
}

console.log('===== 前端 lint 基线守护 =====\n')

// ── 1. 脚本：检查与修复必须分离 ──────────────────────────────
console.log('=== 1. lint 脚本不得自带副作用 ===')
const pkg = JSON.parse(readFileSync(join(FRONT, 'package.json'), 'utf8'))
const lint = pkg.scripts?.lint || ''
const lintFix = pkg.scripts?.['lint:fix'] || ''

check('存在 lint 脚本', !!lint, lint)
check('lint 脚本**不带** --fix（--fix 必须移到 lint:fix）',
  !!lint && !/--fix\b/.test(lint),
  lint ? `实际 "${lint}"；带 --fix 时「只看有无问题」也会重排全部源文件` : '')
check('存在 lint:fix 脚本', !!lintFix, lintFix)
check('lint:fix 带 --fix',
  /--fix\b/.test(lintFix) || /npm run lint -- --fix/.test(lintFix), lintFix)
// format 脚本同样会重排，但它是**显式命名**的格式化命令（语义即「改」），
// 与「lint 顺手改」不同，故只记录不阻断。
console.log(`        format = ${pkg.scripts?.format}（显式格式化命令，语义即「改」，不阻断）`)

// ── 2. eslint 配置 ──────────────────────────────────────────
console.log('\n=== 2. eslint 配置 ===')
const cfg = readFileSync(join(FRONT, '.eslintrc.cjs'), 'utf8')
check('声明了 parserOptions.ecmaVersion（现代语法）',
  /ecmaVersion:\s*\d{4,}/.test(cfg))
check("声明了 parserOptions.sourceType: 'module'（否则 import/顶层 await 解析失败）",
  /sourceType:\s*['"]module['"]/.test(cfg),
  '缺它则测试文件的顶层 `await import(...)` 报「Cannot use keyword await outside an async function」，' +
  '而 vitest 能跑 —— 两边解析不一致')
check('声明了 env.es2020（否则 globalThis 等标准全局被判 no-undef）',
  /es2020:\s*true/.test(cfg))
check('声明了 env.browser / env.node（渲染层与主进程混用）',
  /browser:\s*true/.test(cfg) && /node:\s*true/.test(cfg))

// ── 3. 已修复缺陷不得复活 ────────────────────────────────────
console.log('\n=== 3. 已修复缺陷不得复活 ===')
// `new Promise(async (resolve, reject) => {...})` 共 11 处已于 2026-10-04 修完。
// 该写法是真缺陷（executor 内 await 抛错不进 .catch() → 静默失败），
// eslint 规则 `no-async-promise-executor` 已重新启用；此处再加一道
// 「规则确实开着 + 代码里确实没有」的双重断言：
// 只靠 eslint 规则不够 —— 若有人为省事把规则改回 'off'，门禁会跟着一起失效。
const cfgOff = /'no-async-promise-executor':\s*'off'/.test(cfg)
check('no-async-promise-executor 规则未被关掉',
  !cfgOff,
  cfgOff ? '规则被设为 off —— 2026-10-04 已修完 11 处，应保持启用以防复发' : '')

// ⚠ 统计必须**跳过注释**：本仓大量注释里引用该反模式作为说明
//   （如「// 原为 `new Promise(async (resolve, reject) => {`」），
//   子串匹配会把注释算成「未修」，产生「明明改完了却报剩余 N 处」的假警报。
//   2026-10-04 因此误判过一次。
function stripComments (src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((l) => {
      let inS = null
      let out = ''
      for (let i = 0; i < l.length; i++) {
        const c = l[i]
        if (inS) {
          if (c === '\\') { out += l.slice(i, i + 2); i++; continue }
          if (c === inS) inS = null
          out += c
        } else if (c === '"' || c === "'" || c === '`') {
          inS = c; out += c
        } else if (c === '/' && l[i + 1] === '/') {
          break
        } else { out += c }
      }
      return out
    })
    .join('\n')
}

const leftovers = []
for (const rel of ['src/main/file.js', 'src/main/wsClient.js', 'src/main/ipc.js',
  'src/main/index.js', 'src/main/store.js', 'src/main/notification.js',
  'src/main/windowProxy.js', 'src/main/exportChat.js',
  'src/main/db/ADB.js', 'src/main/db/ChatMessageModel.js',
  'src/main/db/ChatSessionUserModel.js', 'src/main/db/UserSetting.js',
  'src/main/db/LaterHandleModel.js']) {
  const p = join(FRONT, rel)
  if (!existsSync(p)) continue
  if (stripComments(readFileSync(p, 'utf8')).includes('new Promise(async')) leftovers.push(rel)
}
check('真实代码中无 `new Promise(async`（注释里的说明不计入）',
  leftovers.length === 0,
  leftovers.length ? `残留于：${leftovers.join(', ')}` : '')

// 「提前返回」类问题 eslint 规则抓不到，靠反向断言守住这两处已修的点
const cmm = readFileSync(join(FRONT, 'src/main/db/ChatMessageModel.js'), 'utf8')
check('saveMessageBatch 不用 forEach(async …)（forEach 不等待异步回调 → 提前返回）',
  !stripComments(cmm).includes('forEach(async'))
const fj = readFileSync(join(FRONT, 'src/main/file.js'), 'utf8')
check('saveFile2Local 的 uploadFile 已 await（原未 await 就返回）',
  /await uploadFile\(/.test(fj))

// ── 4. 当前错误数不超过基线 ─────────────────────────────────
console.log('\n=== 4. 当前 lint 错误数（不超基线）===')
const BASELINE = 20   // 见 QA：2026-10-04 实测基线
let errCount = null
try {
  const out = execFileSync('cmd',
    ['/c', 'npx eslint . --ext .js,.jsx,.cjs,.mjs,.ts,.tsx,.cts,.mts --format json'],
    { cwd: FRONT, encoding: 'utf8', stdio: 'pipe', maxBuffer: 32 * 1024 * 1024 })
  const data = JSON.parse(out)
  errCount = data.reduce((n, f) => n + f.messages.filter((m) => m.severity === 2).length, 0)
} catch (e) {
  // eslint 以非 0 退出表示有错误，但 stdout 仍是有效 JSON
  const raw = (e.stdout || '').trim()
  if (raw) {
    try {
      const data = JSON.parse(raw)
      errCount = data.reduce((n, f) => n + f.messages.filter((m) => m.severity === 2).length, 0)
    } catch (_) { /* 落到下面的 null 判定 */ }
  }
}

if (errCount === null) {
  check('能取得 lint 错误数', false,
    'eslint 输出无法解析（可能存在 Parsing error 导致非 JSON 输出）——此时应先修语法错误')
} else {
  console.log(`        当前 ${errCount} 个 error（基线 ${BASELINE}）`)
  check(`lint 错误数不超过基线 ${BASELINE}`, errCount <= BASELINE,
    errCount > BASELINE ? `新增了 ${errCount - BASELINE} 个` : '')
}

// ── 汇总 ────────────────────────────────────────────────────
console.log(`\n===== 结论：${pass}/${pass + fail} 通过 =====`)
if (fail) {
  console.log(`失败 ${fail} 项`)
  process.exit(1)
}
process.exit(0)