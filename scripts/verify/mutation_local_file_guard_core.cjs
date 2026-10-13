#!/usr/bin/env node
// 变异检验：故意把 localFileGuardCore.mjs 退回缺陷实现，
// 验证 verify_local_file_guard_core.mjs 是否真的会失败。
//
// 目的（AGENTS §2.1 第 1 条 / 第 14 条）：证明这条**安全关键**门禁有判别力。
//   被测逻辑是遗留 #29 加固的核心：路径穿越拦截 + 文件令牌比对。
//   若门禁恒绿，下次重构把它们改回去（无异常、无测试红、仅本地文件可被读）无人察觉。
//
// 沙箱而非改真文件：门禁的 ROOT 由脚本自身位置解析，把被读文件一起复制到
// 临时目录，它就只读沙箱副本 —— 不碰工作区，无需 git 干净。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进 FILES，
//   否则沙箱缺文件 → 门禁在沙箱里恒红 → 后续「全部捕获」全是假通过。
//
// ⚠ 本脚本**必须**区分「门禁判定失败」与「门禁自己崩了」：
//   非 0 退出但没有任何 [FAIL] 行 = 门禁崩溃，不算捕获。
//
// ⚠ 刻意**不列入**的等价变异（AGENTS §2.1 第 3 条：不做假覆盖，撤除而非假装通过）：
//   ① `timingSafeEqual` 换成 `===`：行为等价，只丢常量时间性质，行为断言抓不到
//      （该性质需用基准耗时测量，属另一类门禁）；
//   ② 删 `a.length !== b.length` 判定：下游 timingSafeEqual 对不等长会抛异常，
//      而门禁第 6 组未包 try → 门禁自身崩溃。按纪律崩溃不算捕获，故撤除该用例。
//   ③ 【2026-10-13 实测撤除】单独删 `if (c === r) return false`（缺陷② 原用例）：
//      删掉后 `startsWith(r + sep)` 仍把「根目录自身」判否 → **等价变异**（结构性冗余）。
//      该守卫的价值只在「缺陷① 同时发生」时才显现，故不为它单列用例。
//   ④ 【2026-10-13 实测撤除】只删 `expected` 的空值守卫（缺陷⑥ 原用例）：
//      删掉后 `provided` 的空值守卫 + 长度守卫仍拦住全部现有用例 → **等价变异**。
//      已改为「两处空值守卫**同时**删除」的组合用例（见缺陷⑥），
//      配套地给门禁补了 `tokensMatch('', '')` 断言 —— 该组合此前**无任何覆盖**。
//
// 用法：node scripts/verify/mutation_local_file_guard_core.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_local_file_guard_core.mjs'
const CORE_REL = 'easychat-front/src/main/localFileGuardCore.mjs'

const FILES = [CORE_REL]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-fileguard-'))
let allCaught = true

function resetSandbox () {
  fs.rmSync(sandbox, { recursive: true, force: true })
  fs.mkdirSync(sandbox, { recursive: true })
  for (const rel of FILES) {
    const dst = path.join(sandbox, rel)
    fs.mkdirSync(path.dirname(dst), { recursive: true })
    fs.copyFileSync(path.join(ROOT, rel), dst)
  }
  const gateDst = path.join(sandbox, GATE_REL)
  fs.mkdirSync(path.dirname(gateDst), { recursive: true })
  fs.copyFileSync(path.join(ROOT, GATE_REL), gateDst)
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
 *
 * ⚠ 锚点匹配**必须对换行不敏感**：先归一化 LF 再匹配，写回时按原文风格还原。
 *   （本仓多次出现「锚点写 \n 而文件是 CRLF → 恒不命中 → 汇总行却写全部捕获」。）
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
    name: '【缺陷①】startsWith 不拼 sep（兄弟目录 U001evil 被误判为「在内部」）',
    apply () {
      mutate(CORE_REL, '    return c.startsWith(r + sep)', '    return c.startsWith(r)')
    }
  },
  {
    name: '【缺陷③】候选路径不再 resolve 归一（../ 不折叠 → 上跳可逃逸）',
    apply () {
      mutate(CORE_REL, '    const c = resolve(candidate)', '    const c = candidate')
    }
  },
  {
    name: '【缺陷④】入参只判空不判类型（resolve(数字) 抛异常，不再「判否不抛」）',
    apply () {
      mutate(CORE_REL,
        "    if (typeof candidate !== 'string' || !candidate) return false",
        '    if (!candidate) return false')
    }
  },
  {
    name: '【缺陷⑤】tokensMatch 恒返回 true（任意令牌皆通过鉴权）',
    apply () {
      mutate(CORE_REL, '    return timingSafeEqual(a, b)', '    return true')
    }
  },
  {
    name: '【缺陷⑥】tokensMatch **两处**空值守卫同时删除（两头都空即放行）',
    apply () {
      // 必须同时删两处：单删一处会被另一处 + 长度守卫兜住（等价变异，已实测撤除）。
      // 两处同删后 tokensMatch('', '') → 长度相同(0) → timingSafeEqual → true → 放行。
      mutate(CORE_REL,
        "    if (typeof expected !== 'string' || !expected) return false\n" +
        "    if (typeof provided !== 'string' || !provided) return false",
        "    if (typeof expected !== 'string') return false\n" +
        "    if (typeof provided !== 'string') return false")
    }
  },
  {
    name: '【缺陷⑦】文件令牌退化为常量（失去 CSPRNG 随机性）',
    apply () {
      mutate(CORE_REL,
        "export const generateFileToken = () => randomBytes(32).toString('hex')",
        "export const generateFileToken = () => 'a'.repeat(64)")
    }
  },
  {
    name: '【缺陷⑧】assertInsideRoot 越界不再抛错（路由层拿不到 400）',
    apply () {
      mutate(CORE_REL,
        "    if (!isInsideRoot(root, candidate)) {\n        throw new Error('路径越界：目标不在允许访问的目录内')\n    }",
        '    if (!isInsideRoot(root, candidate)) {\n        return resolve(candidate)\n    }')
    }
  }
]

console.log('===== 本地文件守卫核心门禁变异检验 =====\n')
console.log(`沙箱：${sandbox}`)
console.log(`用例数：${CASES.length}\n`)

// 前置自检：未变异的沙箱必须先跑通。
// 少了这一步，所有「捕获」都可能只是门禁在崩。
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
// ⚠ 汇总行必须同时计入 [无效] 与 [漏网]。
//   2026-10-13 首版只减 invalid，输出「8/8 个变异被门禁捕获」却同时有 2 条 [漏网]
//   且 exit=1 —— **「全部捕获」与「捕获 6 条 + 漏网 2 条」在汇总上无法区分**，
//   这与本仓已犯过三次的同类问题同源（AGENTS §2.1 第 10 条 ②）。
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