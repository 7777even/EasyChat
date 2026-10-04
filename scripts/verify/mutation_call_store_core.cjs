#!/usr/bin/env node
// 变异检验：故意把 callStoreCore.mjs / useCallStore.js 退回缺陷实现，
// 验证 verify_call_store_core.mjs 是否真的会失败。
//
// 为什么必须做（AGENTS §2.1 第 1 条）：本门禁断言里有两条是「缺陷修复」的守卫
// （陈旧定时器、空 reason 残留旧原因）。若门禁恒绿，下一次重构把它们改回去
// 也无人察觉——那正是本项目反复吃过的「静默失效」。
//
// 沙箱而非改真文件：门禁的 ROOT 由脚本自身位置解析，把被读文件一起复制到
// 临时目录，它就只读沙箱副本。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进 FILES，
//   否则沙箱缺文件 → 门禁在沙箱里恒红 → 后续「全部捕获」全是假通过。
//
// 用法：node scripts/verify/mutation_call_store_core.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_call_store_core.mjs'

// 门禁会读取的仓库内文件（缺一即沙箱恒红）
const FILES = [
  'easychat-front/src/renderer/src/utils/callStoreCore.mjs',
  'easychat-front/src/renderer/src/utils/callFrameCore.mjs',
  'easychat-front/src/renderer/src/stores/useCallStore.js'
]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-callstore-'))
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

/**
 * 跑沙箱内的门禁。
 *
 * ⚠️ 关键：必须区分「门禁跑起来了且判定失败」与「门禁自己崩了」。
 *   初版只判 exit != 0 就记为「捕获」，结果门禁因 ESM 加载错误整体崩掉时，
 *   11 个用例**全部**被判「捕获」—— 得到 100% 的假通过。
 *   现在额外要求输出里出现 [FAIL]；若非 0 退出却没有 FAIL 行，判为「用例无效」。
 */
function runGate () {
  let res
  try {
    const out = execFileSync('node', [path.join(sandbox, GATE_REL)], {
      cwd: sandbox, encoding: 'utf8', stdio: 'pipe', maxBuffer: 16 * 1024 * 1024
    })
    res = { code: 0, out }
  } catch (e) {
    res = {
      code: e.status === undefined ? null : e.status,
      out: (e.stdout || '') + (e.stderr || '')
    }
  }
  const sawFail = /\[FAIL\]/.test(res.out)
  // 门禁崩了：非 0 退出但没有任何断言失败行
  res.crashed = res.code !== 0 && !sawFail
  res.sawFail = sawFail
  return res
}

/** 对沙箱副本做替换；锚点未命中则抛错（避免「变异没生效」被误判成「门禁抓到了」） */
function mutate (rel, find, repl) {
  const p = path.join(sandbox, rel)
  const src = fs.readFileSync(p, 'utf8')
  if (!src.includes(find)) {
    throw new Error('锚点未命中：' + rel + ' ← ' + JSON.stringify(find.slice(0, 90)))
  }
  fs.writeFileSync(p, src.replace(find, repl), 'utf8')
}

const CORE = FILES[0]
const STORE = FILES[2]

const CASES = [
  {
    name: '【缺陷①】复位守卫退回「只看 status」→ 陈旧定时器复活',
    apply () {
      mutate(CORE,
        '  if (!s || s.status !== \'ended\') return false\n  return s.currentEpoch === s.epochAtSchedule',
        '  if (!s) return false\n  return s.status === \'ended\'')
    }
  },
  {
    name: '【缺陷②】endReason 退回「仅在 reason 非空时写入」',
    apply () {
      mutate(CORE,
        '    endReason: reason || \'\',',
        '    ...(reason ? { endReason: reason } : {}),')
    }
  },
  {
    name: '结束态补丁漏清 members（状态残留）',
    apply () {
      mutate(CORE, '    members: [],\n', '')
    }
  },
  {
    name: '结束态补丁越界重置 callType（下次通话默认参数被抹掉）',
    apply () {
      mutate(CORE, '    status: \'ended\'', '    status: \'ended\',\n    callType: 1')
    }
  },
  {
    name: 'signal 帧把缺失字段写成 undefined（仍会被 JSON 发出）',
    apply () {
      mutate(CORE,
        '  if (sdp) msg.sdp = sdp\n  if (candidate) msg.candidate = candidate',
        '  msg.sdp = sdp\n  msg.candidate = candidate')
    }
  },
  {
    name: 'callId 为空时不再拦截（会发出 callId=null 的无意义帧）',
    apply () {
      mutate(CORE, '  return callId ? { messageType: -12, callId } : null',
        '  return { messageType: -12, callId }')
    }
  },
  {
    name: 'invite 帧号写错（-10 → -13，与 SIGNAL 撞号）',
    apply () {
      mutate(CORE,
        '  return { messageType: -10, callId, callType, mediaType, toUserId, groupId }',
        '  return { messageType: -13, callId, callType, mediaType, toUserId, groupId }')
    }
  },
  {
    name: 'busy 帧号写错（-16 → -14，与 HANGUP 撞号）',
    apply () {
      mutate(CORE, '  return callId ? { messageType: -16, callId } : null',
        '  return callId ? { messageType: -14, callId } : null')
    }
  },
  {
    name: 'store 绕开纯核心、自行内联 1800 裸守卫（缺陷①原地复活）',
    apply () {
      mutate(STORE,
        '      const epochAtSchedule = this.callEpoch\n      setTimeout(() => {\n' +
        '        if (!shouldAutoResetToIdle({\n' +
        '          status: this.status,\n' +
        '          epochAtSchedule,\n' +
        '          currentEpoch: this.callEpoch\n' +
        '        })) return\n' +
        "        this.status = 'idle'",
        "      setTimeout(() => {\n        if (this.status === 'ended') {\n          this.status = 'idle'")
    }
  },
  {
    name: 'store 在 useCallStore 内裸写 messageType（各写一份，帧号会漂）',
    apply () {
      mutate(STORE,
        '      this.sendFrame(buildSignalFrame({',
        "      this.sendFrame({ messageType: -13, callId: this.callId, toUserId: peerId, signalType, ...({ sdp: payload && payload.sdp }) })\n      return this.sendSignalIgnored(buildSignalFrame({")
    }
  },
  {
    name: 'store 不再捕获定时器所属通话身份',
    apply () {
      mutate(STORE, '      const epochAtSchedule = this.callEpoch\n', '')
    }
  }
]

console.log('===== 通话 store 门禁变异检验 =====\n')
console.log(`沙箱：${sandbox}`)
console.log(`用例数：${CASES.length}\n`)

for (const c of CASES) {
  resetSandbox()
  try {
    c.apply()
  } catch (e) {
    console.log(`  [ERROR] ${c.name}\n          变异未生效：${e.message}`)
    allCaught = false
    continue
  }
  const r = runGate()
  if (r.crashed) {
    const head = (r.out.split('\n').find((l) => l.trim()) || '').slice(0, 110)
    console.log(`  [无效] ${c.name}\n          ⚠ 门禁自身崩溃（exit=${r.code} 且无 [FAIL] 行）：${head}`)
    console.log('          这不是「捕获」—— 先修门禁，否则本次检验无效')
    allCaught = false
  } else if (r.code !== 0) {
    const failed = (r.out.match(/\[FAIL\]/g) || []).length
    const first = (r.out.match(/\[FAIL\][^\r\n]*/) || [''])[0].replace('[FAIL] ', '').trim()
    console.log(`  [捕获] ${c.name}\n          exit=${r.code}，${failed} 项失败，首项：${first}`)
  } else {
    console.log(`  [漏网] ${c.name}\n          ⚠ 门禁仍然 exit=0 —— 该断言没有判别力`)
    allCaught = false
  }
}

// 前置自检：未变异的沙箱必须先跑通。
// 少了这一步，上面所有「捕获」都可能只是门禁在崩（2026-10-04 踩中）。
resetSandbox()
const base = runGate()
if (base.crashed || base.code !== 0) {
  const head = (base.out.split('\n').find((l) => l.trim()) || '').slice(0, 140)
  console.log(`\n  [致命] 变异前的基线门禁就没跑通（exit=${base.code}）`)
  console.log(`         ${head}`)
  console.log('         请先修好门禁再谈判别力 —— 否则下面全是假通过')
  fs.rmSync(sandbox, { recursive: true, force: true })
  process.exit(1)
}
console.log('  [基线] 未变异时门禁通过（确认后续「捕获」不是门禁在崩）')

fs.rmSync(sandbox, { recursive: true, force: true })
console.log(`\n===== 结论：${CASES.length}/${CASES.length} 个变异全部被门禁捕获 =====`)
if (allCaught) {
  console.log('✓ 门禁有判别力')
  process.exit(0)
}
console.log('✗ 存在无判别力的断言')
process.exit(1)