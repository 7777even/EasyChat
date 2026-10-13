#!/usr/bin/env node
// 变异检验：故意破坏「本地文件 URL 必须经 withFileToken 封装」这条约定或让扫描器失效，
// 验证 verify_local_file_token_usage.mjs 是否真的会失败。
//
// 目的（AGENTS §2.1 第 1 条 / 第 14 条）：证明这条门禁有判别力。
//   被测的是**安全约定的落地方式**：本地文件服务器自遗留 #29 起对 `GET /file`
//   强制校验 fileToken，渲染层任何一处绕过封装 = 该处图片/语音/视频**静默全裂**
//   （不抛异常，肉眼极难定位）。若门禁恒绿，新增调用点绕过封装将无人察觉。
//
// 沙箱而非改真文件：门禁的 ROOT 由脚本自身位置解析，它只 readFileSync（不 import
//   任何被测模块、无 node_modules 依赖），故整目录复制到临时目录即可 ——
//   不碰工作区、无需 git 干净。⚠ 若将来门禁改为 import 被测模块，
//   沙箱缺 node_modules 会导致「恒红」，届时须改回「原地临时改 + try/finally 必还原」。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内目录/文件，必须同步补进 DIRS / FILES，
//   否则沙箱缺文件 → 门禁在沙箱里恒红或恒「0 命中」→ 后续「全部捕获」全是假通过。
//
// ⚠ 本脚本**必须**区分「门禁判定失败」与「门禁自己崩了」：
//   非 0 退出但没有任何 [FAIL] 行 = 门禁崩溃，不算捕获。
//
// 用法：node scripts/verify/mutation_local_file_token_usage.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_local_file_token_usage.mjs'
const RENDERER_REL = 'easychat-front/src/renderer/src'
const UTIL_REL = RENDERER_REL + '/utils/localFileServer.js'
const SAMPLE_REL = RENDERER_REL + '/components/ShowLocalImage.vue'

const DIRS = [RENDERER_REL]
const FILES = [GATE_REL]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-filetoken-'))
let allCaught = true

function resetSandbox () {
  fs.rmSync(sandbox, { recursive: true, force: true })
  fs.mkdirSync(sandbox, { recursive: true })
  for (const rel of DIRS) {
    fs.cpSync(path.join(ROOT, rel), path.join(sandbox, rel), { recursive: true })
  }
  for (const rel of FILES) {
    const dst = path.join(sandbox, rel)
    fs.mkdirSync(path.dirname(dst), { recursive: true })
    fs.copyFileSync(path.join(ROOT, rel), dst)
  }
}

function runGate () {
  let res
  try {
    const out = execFileSync('node', [path.join(sandbox, GATE_REL)], {
      cwd: sandbox, encoding: 'utf8', stdio: 'pipe', maxBuffer: 16 * 1024 * 1024
    })
    res = { code: 0, out }
  } catch (e) {
    res = { code: e.status === undefined ? null : e.status, out: (e.stdout || '') + (e.stderr || '') }
  }
  const sawFail = /\[FAIL\]/.test(res.out)
  res.crashed = res.code !== 0 && !sawFail
  return res
}

/**
 * 对沙箱副本做替换；锚点未命中则抛错（避免「变异没生效」被误判成「门禁抓到了」）。
 * ⚠ 锚点匹配**必须对换行不敏感**：先归一化 LF 再匹配，写回时按原文风格还原。
 */
function mutate (rel, find, repl) {
  const p = path.join(sandbox, rel)
  const src = fs.readFileSync(p, 'utf8')
  const eol = src.includes('\r\n') ? '\r\n' : '\n'
  const srcLf = src.replace(/\r\n/g, '\n')
  const findLf = find.replace(/\r\n/g, '\n')
  const replLf = repl.replace(/\r\n/g, '\n')
  if (!srcLf.includes(findLf)) {
    throw new Error('锚点未命中：' + rel + ' ← ' + JSON.stringify(findLf.slice(0, 90)))
  }
  const mutated = srcLf.replace(findLf, replLf)
  fs.writeFileSync(p, eol === '\r\n' ? mutated.replace(/\n/g, '\r\n') : mutated, 'utf8')
}

const CASES = [
  {
    name: '【缺陷①】调用点绕过封装（withFileToken( 被改名 → 该处文件静默全裂）',
    apply () {
      mutate(SAMPLE_REL, '  return withFileToken(', '  return withFileTokenXX(')
    }
  },
  {
    name: '【缺陷②】调用点删掉 withFileToken 的 import（函数未定义 → 运行时 ReferenceError）',
    apply () {
      mutate(SAMPLE_REL,
        "import { withFileToken } from '@/utils/localFileServer'",
        "import { localFileServerInfo } from '@/utils/localFileServer'")
    }
  },
  {
    name: '【缺陷③】封装被就地重写为空实现（总是返回原 URL，不追加令牌）',
    apply () {
      mutate(UTIL_REL,
        "  return `${url}${sep}fileToken=${encodeURIComponent(token ?? '')}`",
        '  return url')
    }
  },
  {
    name: '【缺陷④】封装去掉「非字符串原样返回」守卫（对 undefined 静默拼出 undefined 字面量）',
    apply () {
      mutate(UTIL_REL,
        "  if (typeof url !== 'string' || !url) return url",
        '  if (!url) return url')
    }
  },
  {
    name: '【缺陷⑤】withFileToken 的 export 被删（调用点全部失效）',
    apply () {
      mutate(UTIL_REL,
        'export const withFileToken = (url, token) => {',
        'const withFileTokenHidden = (url, token) => {')
    }
  },
  {
    name: '【缺陷⑥】扫描器失效：URL 正则收窄到只认 127.0.0.1（命中数跌破下限）',
    apply () {
      mutate(GATE_REL,
        'const URL_RE = /https?:\\/\\/(?:localhost|127\\.0\\.0\\.1):\\$\\{[^}]*\\}\\/file\\?/g',
        'const URL_RE = /https?:\\/\\/127\\.0\\.0\\.1:\\$\\{[^}]*\\}\\/file\\?/g')
    }
  },
  {
    name: '【缺陷⑦】行注释剥离正则丢掉负向后顾（`http://` 的 // 被当注释起点 → 整行被剥 → 0 命中）',
    apply () {
      // 这正是门禁注释里记录的历史坑：首次运行就踩中，被空转自检当场拦下。
      mutate(GATE_REL, "        .replace(/(?<!:)\\/\\/.*$/gm, '')", "        .replace(/\\/\\/.*$/gm, '')")
    }
  }
]

console.log('===== 本地文件 URL 令牌封装门禁变异检验 =====\n')
console.log(`沙箱：${sandbox}`)
console.log(`用例数：${CASES.length}\n`)

// 前置自检：未变异的沙箱必须先跑通。
resetSandbox()
const base = runGate()
if (base.crashed || base.code !== 0) {
  const head = (base.out.split('\n').find((l) => l.trim()) || '').slice(0, 140)
  console.log(`  [致命] 变异前的基线门禁就没跑通（exit=${base.code}）`)
  console.log(`         ${head}`)
  console.log('         请先修好门禁再谈判别力 —— 否则下面全是假通过')
  fs.rmSync(sandbox, { recursive: true, force: true })
  process.exit(1)
}
console.log('  [基线] 未变异时门禁通过（确认后续「捕获」不是门禁在崩）\n')

let invalid = 0
let missed = 0
for (const c of CASES) {
  resetSandbox()
  try {
    c.apply()
  } catch (e) {
    console.log(`  [无效] ${c.name}\n          变异未生效：${e.message}`)
    invalid++
    allCaught = false
    continue
  }
  const r = runGate()
  if (r.crashed) {
    const head = (r.out.split('\n').find((l) => l.trim()) || '').slice(0, 110)
    console.log(`  [无效] ${c.name}\n          ⚠ 门禁自身崩溃（exit=${r.code} 且无 [FAIL] 行）：${head}`)
    allCaught = false
  } else if (r.code !== 0) {
    const failed = (r.out.match(/\[FAIL\]/g) || []).length
    const first = (r.out.match(/\[FAIL\][^\r\n]*/) || [''])[0].replace('[FAIL] ', '').trim()
    console.log(`  [捕获] ${c.name}\n          exit=${r.code}，${failed} 项失败，首项：${first}`)
  } else {
    console.log(`  [漏网] ${c.name}\n          ⚠ 门禁仍然 exit=0 —— 该断言没有判别力`)
    allCaught = false
    missed++
  }
}

fs.rmSync(sandbox, { recursive: true, force: true })
// ⚠ 汇总行必须同时计入 [无效] 与 [漏网]（AGENTS §2.1 第 10 条 ②）：
//   只计 invalid 时，「7/7 全部捕获」与「捕获 5 + 漏网 2」在输出上完全无法区分。
const caught = CASES.length - invalid - missed
console.log(`\n===== 结论：${caught}/${CASES.length} 个变异被门禁捕获`
  + `${invalid > 0 ? `，${invalid} 个用例无效（变异未生效）` : ''}`
  + `${missed > 0 ? `，${missed} 个漏网（门禁无判别力）` : ''} =====`)
if (allCaught) {
  console.log('✓ 门禁有判别力')
  process.exit(0)
}
console.log('✗ 存在无判别力的断言或无效用例')
process.exit(1)