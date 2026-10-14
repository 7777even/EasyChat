#!/usr/bin/env node
// 变异检验：故意把 callFrameCore.mjs 退回缺陷实现，
// 验证 verify_call_core.mjs 是否真的会失败。
//
// 目的（AGENTS §2.1 第 1 条 / 第 14 条）：证明通话状态机这条门禁有判别力。
//   被测的是「信令帧 → 状态转移 + 副作用」的纯函数。通话缺陷的特征是
//   **UI 停在「呼叫中」却无任何报错**，静态断言抓不到，只能靠行为断言 + 变异反验。
//   门禁注释点名的历史缺陷（发起方停在 calling 收不到 connected）就在变异③。
//
// 沙箱而非改真文件：门禁用**静态相对路径** import 被测核心
//   （`'../../easychat-front/src/renderer/src/utils/callFrameCore.mjs'`），
//   故沙箱必须保持相同的相对层级；核心无任何第三方依赖，复制即可。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进 COPY，否则沙箱缺文件 →
//   门禁在沙箱里恒红 → 后续「全部捕获」全是假通过。
//
// ⚠ 本脚本**必须**区分「门禁判定失败」与「门禁自己崩了」：
//   非 0 退出但没有任何 [FAIL] 行 = 门禁崩溃，不算捕获。
//
// 用法：node scripts/verify/mutation_call_core.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_call_core.mjs'
// 2026-10-13：ICE 失败兜底由**另一道**门禁验证（verify_call_ice_fail.mjs），
// 故沙箱必须同时带上它，否则缺陷⑪~⑮ 会被判成「门禁未跑到」而非「断言未覆盖」。
const GATE2_REL = 'scripts/verify/verify_call_ice_fail.mjs'
const CORE_REL = 'easychat-front/src/renderer/src/utils/callFrameCore.mjs'

const COPY = [CORE_REL]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-callcore-'))
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
  const gate2Dst = path.join(sandbox, GATE2_REL)
  fs.mkdirSync(path.dirname(gate2Dst), { recursive: true })
  fs.copyFileSync(path.join(ROOT, GATE2_REL), gate2Dst)
}

function runGate () {
  let res
  try {
    // ⚠ **两道门禁都要跑**：缺陷⑪~⑮ 针对 ICE 失败兜底，其断言在 GATE2 里。
    //   初版只跑第一道 → 那 5 条变异会全部「漏网」，而真因是门禁没被调用，
    //   不是判别力不足（AGENTS §2.1 第 3 条：不把环境问题伪装成覆盖缺口）。
    let out = ''
    let code = 0
    for (const rel of [GATE_REL, GATE2_REL]) {
      try {
        out += execFileSync('node', [path.join(sandbox, rel)], {
          cwd: sandbox, encoding: 'utf8', stdio: 'pipe', maxBuffer: 16 * 1024 * 1024
        })
      } catch (e) {
        code = e.status === undefined ? null : e.status
        out += (e.stdout || '') + (e.stderr || '')
      }
    }
    res = { code, out }
  } catch (e) {
    res = { code: e.status === undefined ? null : e.status, out: (e.stdout || '') + (e.stderr || '') }
  }
  const sawFail = /\[FAIL\]/.test(res.out)
  res.crashed = res.code !== 0 && !sawFail
  return res
}

/** 锚点未命中即抛错（避免「变异没生效」被误判成「门禁抓到了」）；匹配对换行不敏感。 */
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
    name: '【缺陷①】INVITE 不再置 ringing（来电界面不弹出，UI 毫无反应）',
    apply () {
      mutate(CORE_REL, "    patch.status = 'ringing'", "    patch.status = 'invited'")
    }
  },
  {
    name: '【缺陷②】iceServers 缺省不再回落空数组（state 里落 undefined，后续 spread 崩）',
    apply () {
      mutate(CORE_REL, '    patch.iceServers = frame.iceServers || []', '    patch.iceServers = frame.iceServers')
    }
  },
  {
    name: '【缺陷③ · 历史缺陷回归】他人 JOIN 时不再把 calling/ringing 转为 connected',
    apply () {
      // 门禁注释点名的历史缺陷：发起方停在「呼叫中」，无任何报错。
      mutate(CORE_REL,
        "    } else if (state.status === 'calling' || state.status === 'ringing') {",
        "    } else if (state.status === 'ended') {")
    }
  },
  {
    name: '【缺陷④】「我是否新成员」判定反写（offer 发反对象、状态转错）',
    apply () {
      mutate(CORE_REL, '    if (newMemberId === selfId) {', '    if (newMemberId !== selfId) {')
    }
  },
  {
    name: '【缺陷⑤】offer 时不再排除自己（房内仅自己也会给自己发 offer → 回声/自连接）',
    apply () {
      mutate(CORE_REL, '        if (uid !== selfId) effects.push({ type: \'offer\', peerId: uid })',
        "        effects.push({ type: 'offer', peerId: uid })")
    }
  },
  {
    name: '【缺陷⑥】SIGNAL 的 offer 分支映射成 answer（应答方向反了，永远协商不成功）',
    apply () {
      mutate(CORE_REL,
        "    if (frame.signalType === 'offer') {\n      effects.push({ type: 'answer', peerId, sdp: frame.sdp })",
        "    if (frame.signalType === 'offer') {\n      effects.push({ type: 'remoteAnswer', peerId, sdp: frame.sdp })")
    }
  },
  {
    name: '【缺陷⑦】candidate 不再产生 remoteCandidate（ICE 候选丢失，NAT 下连不通）',
    apply () {
      mutate(CORE_REL,
        "    } else if (frame.signalType === 'candidate') {\n      effects.push({ type: 'remoteCandidate', peerId, candidate: frame.candidate })",
        "    } else if (frame.signalType === 'candidate') {\n      effects.push({ type: 'candidate', peerId, candidate: frame.candidate })")
    }
  },
  {
    name: '【缺陷⑧】HANGUP 的结束原因错配成「对方已取消」（用户看到错误原因）',
    apply () {
      mutate(CORE_REL,
        "effects.push({ type: 'endCall', reason: '对方已挂断' })",
        "effects.push({ type: 'endCall', reason: '对方已取消' })")
    }
  },
  {
    name: '【缺陷⑨】BUSY 结束帧分支被删（忙线时界面不结束，通话假死）',
    apply () {
      mutate(CORE_REL,
        "  } else if (t === FRAME_BUSY) {\n    effects.push({ type: 'endCall', reason: '对方忙线' })",
        "  } else if (t === -999) {\n    effects.push({ type: 'endCall', reason: '对方忙线' })")
    }
  },
  {
    name: '【缺陷⑩】兜底失效：INVITE 判定改为恒真（任意未知帧都被当成来电处理）',
    apply () {
      mutate(CORE_REL, '  if (t === FRAME_INVITE) {', '  if (t !== FRAME_SIGNAL && t < 0) {')
    }
  },
  // ── ICE 失败兜底（2026-10-13 新增，C3 / ADR-004）────────────────────
  // 这几条针对**门禁 verify_call_ice_fail.mjs**，故沙箱需同时带上该门禁。
  {
    name: '【缺陷⑪ · 本轮】ICE 判定方向反写（connected 也被判失败 → 通话必断）',
    apply () {
      mutate(CORE_REL, '  if (iceState !== ICE_STATE_FAILED) return { patch, effects }',
        '  if (iceState === ICE_STATE_FAILED) return { patch, effects }')
    }
  },
  {
    name: '【缺陷⑫ · 本轮】终态守卫被删（挂断后残留 ICE 事件把界面拉回「连接失败」）',
    apply () {
      mutate(CORE_REL, '  if (TERMINAL_STATUSES.includes(status)) return { patch, effects }', '')
    }
  },
  {
    name: '【缺陷⑬ · 本轮】idle 守卫被删（无通话时凭空进入通话并弹提示）',
    apply () {
      mutate(CORE_REL, "  if (status === 'idle') return { patch, effects }", '')
    }
  },
  {
    name: '【缺陷⑭ · 本轮】失败时不产出 endCall（状态卡在 icefailed，不清理 peer 与本地流）',
    apply () {
      mutate(CORE_REL, "  effects.push({ type: 'endCall', reason: ICE_PROMPT })", '')
    }
  },
  {
    name: '【缺陷⑮ · 本轮】新来电不清 errorMsg（上一通的失败提示挂在本次来电界面）',
    apply () {
      mutate(CORE_REL, "    patch.errorMsg = ''", '')
    }
  }
]

console.log('===== 通话状态机门禁变异检验 =====\n')
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
// ⚠ 汇总行必须同时计入 [无效] 与 [漏网]（AGENTS §2.1 第 10 条 ②）
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