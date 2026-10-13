#!/usr/bin/env node
// 变异检验：故意把 preflightBaselineCore.mjs 退回缺陷实现，
// 验证 verify_preflight_baseline_core.mjs 是否真的会失败。
//
// 目的（AGENTS §2.1 第 1 条 / 第 14 条）：这条纯核心正是为遗留 #11 抽出来的 ——
//   此前 `verify_migration_flyway.mjs` 对 preflight 的断言**全是读源码找字面量**，
//   看不出**条件分支的方向**：把
//     `if (!missingTables.length && !missingCols.length) { 放行 }`
//   反写成
//     `if (missingTables.length || missingCols.length) { 放行 }`
//   四条静态断言**全部照样通过**，而语义已完全颠倒（结构不符反而放行 = 迁移被静默跳过）。
//
//   抽出纯核心 + 行为测试解决了「静态抓不到方向」，但**行为测试自己有没有判别力**
//   仍需变异反验 —— 这正是本脚本的职责，且变异①就是上面那个原始缺陷的方向反写。
//   ⚠ 这条路径一旦失效，后果是 fail-open：闸门拒绝启动变成放行启动。
//
// 沙箱而非改真文件：门禁 import 纯核心（无 fs / child_process / process 依赖），
//   复制即可；但**必须同时复制门禁自身**，它 readFileSync 同一路径做「纯度」断言。
//
// ⚠ 本脚本**必须**区分「门禁判定失败」与「门禁自己崩了」：
//   非 0 退出但没有任何 [FAIL] 行 = 门禁崩溃，不算捕获。
//
// 用法：node scripts/verify/mutation_preflight_baseline_core.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_preflight_baseline_core.mjs'
const CORE_REL = 'scripts/migrate/preflightBaselineCore.mjs'

const COPY = [CORE_REL]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-preflight-'))
let allCaught = true

function resetSandbox () {
  fs.rmSync(sandbox, { recursive: true, force: true })
  fs.mkdirSync(sandbox, { recursive: true })
  for (const rel of COPY) {
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

/** 锚点未命中即抛错；匹配对换行不敏感，按原文风格写回。 */
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
    name: '【缺陷① · 遗留 #11 原始缺陷】结构一致判定的方向反写（结构不符反而放行 → 迁移被静默跳过）',
    apply () {
      // ⚠ 这正是抽出本纯核心要防的那一条：静态断言对此完全无感。
      mutate(CORE_REL,
        '  if (!missingTables.length && !missingCols.length) {',
        '  if (missingTables.length || missingCols.length) {')
    }
  },
  {
    name: '【缺陷②】结构不符时的 exitCode 由 1 变 0（拒绝启动变成放行 = fail-open）',
    apply () {
      mutate(CORE_REL, '    exitCode: 1,', '    exitCode: 0,')
    }
  },
  {
    name: '【缺陷③】「库为空」判定反写（空库反而被拒，全新环境起不来）',
    apply () {
      mutate(CORE_REL, '  if (Number(tableCount) === 0) {', '  if (Number(tableCount) !== 0) {')
    }
  },
  {
    name: '【缺陷④】「已有历史表」判定反写（有记账表却去做结构比对 → 误拒启动）',
    apply () {
      mutate(CORE_REL, '  if (Number(hasHistory) > 0) {', '  if (Number(hasHistory) < 0) {')
    }
  },
  {
    name: '【缺陷⑤】baseline-version 与最大迁移号相等的判定反写（存量库被当最新版 → 误拒）',
    apply () {
      mutate(CORE_REL, '  if (Number(configuredVersion) !== Number(maxVersion)) {',
        '  if (Number(configuredVersion) === Number(maxVersion)) {')
    }
  },
  {
    name: '【缺陷⑥ · 2026-10-04 自查发现的坑】env 优先判定被短路（运维声明的真实版本号读不到）',
    apply () {
      // 后果：脚本把自己报错里建议的处置路径（设 SPRING_FLYWAY_BASELINE_VERSION）堵死。
      mutate(CORE_REL,
        "  if (env !== undefined && env !== null && /^\\d+$/.test(String(env).trim())) {",
        "  if (false && env !== undefined && env !== null && /^\\d+$/.test(String(env).trim())) {")
    }
  },
  {
    name: '【缺陷⑦】迁移编号正则由三位放宽为任意位（两位 / 四位文件被计入最大号）',
    apply () {
      // ⚠ 初版只放宽了 filter 的正则，而 map 侧仍是 `(\d{3})`：
      //   四位编号在 map 里 match 返回 null → `null[1]` 抛 TypeError → **门禁自身崩溃**，
      //   按纪律崩溃不算捕获。教训：同一语义的正则分散在两处时，变异必须**同步改两处**，
      //   否则制造的是「代码内部不一致的崩溃」而非可判定的行为变化。
      mutate(CORE_REL,
        "    .filter((f) => /^easychat-migration-\\d{3}-.*\\.sql$/.test(f))",
        "    .filter((f) => /^easychat-migration-\\d+-.*\\.sql$/.test(f))")
      mutate(CORE_REL,
        "    .map((f) => Number(f.match(/^easychat-migration-(\\d{3})-/)[1]))",
        "    .map((f) => Number(f.match(/^easychat-migration-(\\d+)-/)[1]))")
    }
  },
  {
    // 【2026-10-13 实测撤除】初版有「基线列解析不再要求类型关键字」用例，实测**漏网**。
    //   查证为**等价变异**：`PRIMARY KEY / UNIQUE / KEY / INDEX / CONSTRAINT / FULLTEXT`
    //   这些非列行已被上一行守卫 continue 掉，而真实 SQL 不存在「反引号后无类型」的行，
    //   故 `[a-z]` 这层要求在当前实现下冗余。按 §2.1 第 3 条**撤除该用例**，不做假覆盖。
    name: '【缺陷⑧】活库列解析不再跳过畸形行（空行被当成表名 → 全部表判为缺列）',
    apply () {
      mutate(CORE_REL, '    if (t.length < 2) continue', '    if (t.length < 0) continue')
    }
  },
  {
    name: '【缺陷⑨】缺失表不再记入 missingTables（基线有而活库没有的表被放过 → 漂移不报）',
    apply () {
      // ⚠ 初版写成 `if (lc) { push; continue }`：表存在时才 push，而表**不存在**时
      //   不再 continue → 紧接着 `lc.has(c)` 对 undefined 调用 → 门禁崩溃。
      //   崩溃按纪律不算捕获，故改为「缺表直接跳过、不记入」的写法。
      mutate(CORE_REL, '    if (!lc) { missingTables.push(t); continue }',
        '    if (!lc) { continue }')
    }
  }
]

console.log('===== 迁移前置闸门纯核心门禁变异检验 =====\n')
console.log(`沙箱：${sandbox}`)
console.log(`用例数：${CASES.length}\n`)

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