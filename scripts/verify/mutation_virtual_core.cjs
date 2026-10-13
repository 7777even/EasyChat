#!/usr/bin/env node
// 变异检验：故意把 virtualListCore.mjs 退回缺陷实现，
// 验证 verify_virtual_core.mjs 是否真的会失败。
//
// 目的（AGENTS §2.1 第 1 条 / 第 14 条）：证明虚拟滚动算法门禁有判别力。
//   被测是消息列表自研虚拟滚动的两个关键：偏移前缀和表 + 二分定位。
//   这类缺陷的表现是「滚到某处消息错位 / 白屏 / 越界」，**不抛异常**，
//   纯 UI 层难以察觉，只能靠算法断言 + 变异反验。
//
// 沙箱而非改真文件：门禁用静态相对路径 import 被测核心
//   （`'../../easychat-front/src/renderer/src/utils/virtualListCore.mjs'`），
//   沙箱保持相同相对层级；核心无任何依赖，复制即可。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进 COPY。
// ⚠ 本脚本**必须**区分「门禁判定失败」与「门禁自己崩了」：
//   非 0 退出但没有任何 [FAIL] 行 = 门禁崩溃，不算捕获。
//
// 用法：node scripts/verify/mutation_virtual_core.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_virtual_core.mjs'
const CORE_REL = 'easychat-front/src/renderer/src/utils/virtualListCore.mjs'

const COPY = [CORE_REL]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-virtual-'))
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
    name: '【缺陷①】偏移表改为递减（二分前提失效 → 定位全错、消息错位）',
    apply () {
      mutate(CORE_REL, '    arr[i + 1] = arr[i] + h', '    arr[i + 1] = arr[i] - h')
    }
  },
  {
    name: '【缺陷②】实测高度不再要求 > 0（负高度被采纳 → 单调性破坏）',
    apply () {
      mutate(CORE_REL, '    const h = measured && measured > 0 ? measured : estimateHeight',
        '    const h = measured ? measured : estimateHeight')
    }
  },
  {
    name: '【缺陷③】未测量项不再回落估算高度（高度缺失 → NaN / 布局塌陷）',
    apply () {
      mutate(CORE_REL, '    const h = measured && measured > 0 ? measured : estimateHeight',
        '    const h = measured && measured > 0 ? measured : 0')
    }
  },
  {
    name: '【缺陷④】二分比较方向反写（offsets[mid] <= target → >=）',
    apply () {
      mutate(CORE_REL, '    if (offsets[mid] <= target) {', '    if (offsets[mid] >= target) {')
    }
  },
  {
    // ⚠ 初版写成 `lo = mid + 1` → `lo = mid - 1`（二分收敛方向反写），
    //   实测**死循环**——lo 不推进，整个脚本卡死到超时（2026-10-13）。
    //   改用等价意图、不改变收敛性的写法：命中分支记的 res 偏移一格。
    name: '【缺陷⑤】二分命中分支记录的下标偏移一格（返回相邻项 → 定位错行）',
    apply () {
      mutate(CORE_REL, '      res = mid', '      res = mid + 1')
    }
  },
  {
    name: '【缺陷⑥】末项下标越界一格（offsets.length - 2 → -1，读到表外的哨位）',
    apply () {
      mutate(CORE_REL, '  const last = offsets.length - 2 // 最后一个有效项下标',
        '  const last = offsets.length - 1 // 最后一个有效项下标')
    }
  },
  {
    name: '【缺陷⑦】空表不再回落 0（返回 -1 → 渲染区间从 -1 起，Vue 列表报错）',
    apply () {
      mutate(CORE_REL, '  if (last < 0) return 0', '  if (last < 0) return -1')
    }
  },
  {
    name: '【缺陷⑧】可见区间的下边界判定反写（渲染上半屏而非下半屏）',
    apply () {
      mutate(CORE_REL, '  while (end < n - 1 && offsets[end + 1] < bottom) {',
        '  while (end < n - 1 && offsets[end + 1] > bottom) {')
    }
  },
  {
    name: '【缺陷⑨】末下标不再夹到 n-1（overscan 使区间越界 → 渲染不存在的行）',
    apply () {
      mutate(CORE_REL, '  end = Math.min(n - 1, end + (overscan || 0))', '  end = end + (overscan || 0)')
    }
  },
  {
    name: '【缺陷⑩】视口高度缺省不再回落 600（只渲染 1 条 → 聊天窗几乎空白）',
    apply () {
      mutate(CORE_REL, '  const bottom = scrollTop + (viewportHeight || 600)', '  const bottom = scrollTop + (viewportHeight || 0)')
    }
  }
]

console.log('===== 虚拟滚动核心门禁变异检验 =====\n')
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