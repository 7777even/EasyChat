#!/usr/bin/env node
// 变异检验：故意把 dbModelCore.mjs 退回缺陷实现，
// 验证 verify_db_model_core.mjs 是否真的会失败。
//
// 目的（AGENTS §2.1 第 1 条 / 第 14 条）：证明这条门禁有判别力。
//   被测核心是主进程本地 SQLite 的**构造层**，三类「写错了也不报错」的形态：
//   ① 参数顺序颠倒 → 改到别人的行；② WHERE 条件漏项 → 命中范围扩大；
//   ③ 驼峰字段名直接当列名 → SQLite 不报错、甚至凭空建列。
//   若门禁恒绿，下次重构把它们改回去（数据没写进去 / 写到别人的会话）无人察觉。
//
// 沙箱而非改真文件：门禁的 ROOT 由脚本自身位置解析，它只 import 纯核心 .mjs
//   （无 sqlite3 / 无 store / 无 node_modules 依赖），复制两个文件即可 ——
//   不碰工作区、无需 git 干净。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进 FILES，
//   否则沙箱缺文件 → 门禁在沙箱里恒红 → 后续「全部捕获」全是假通过。
//
// ⚠ 本脚本**必须**区分「门禁判定失败」与「门禁自己崩了」：
//   非 0 退出但没有任何 [FAIL] 行 = 门禁崩溃，不算捕获。
//
// 用法：node scripts/verify/mutation_db_model_core.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_db_model_core.mjs'
const CORE_REL = 'easychat-front/src/main/db/dbModelCore.mjs'

const FILES = [CORE_REL]
// ⚠ 门禁第 11 节还要读 4 个 Model 源文件（断言「Model 层不得残留内联 SQL 字面量」）。
//   **2026-10-13 首次运行即踩中**：漏复制它们 → 沙箱内 readFileSync 抛 ENOENT →
//   门禁崩溃且**不产生任何 [FAIL] 行** → 若无基线自检，8 条变异的结果会全是假信息。
const MODELS = [
  'easychat-front/src/main/db/ChatMessageModel.js',
  'easychat-front/src/main/db/ChatSessionUserModel.js',
  'easychat-front/src/main/db/UserSetting.js',
  'easychat-front/src/main/db/LaterHandleModel.js'
]
const COPY = [...FILES, ...MODELS]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-dbmodel-'))
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
    name: '【缺陷①】分页 limit 参数顺序颠倒（每页条数与总页数对不上）',
    apply () {
      mutate(CORE_REL,
        '  params.push(offset)\n  params.push(limit)',
        '  params.push(limit)\n  params.push(offset)')
    }
  },
  {
    name: '【缺陷②】未读数自增的参数顺序颠倒（改到别人的会话）',
    apply () {
      mutate(CORE_REL,
        "    params: [noReadCount, userId, contactId]",
        '    params: [userId, contactId, noReadCount]')
    }
  },
  {
    name: '【缺陷③】会话更新不再剔除主键（一条会话凭空变成另一条）',
    apply () {
      mutate(CORE_REL,
        '  info.contactId = null\n  info.userId = null',
        '  info.contactId = info.contactId')
    }
  },
  {
    name: '【缺陷④】SET 子句把驼峰字段名直接当列名（SQLite 凭空建列，数据没进去）',
    apply () {
      mutate(CORE_REL,
        "  const COL = { lastMessage: 'last_message', lastReceiveTime: 'last_receive_time', noReadCount: 'no_read_count' }",
        "  const COL = { lastMessage: 'lastMessage', lastReceiveTime: 'lastReceiveTime', noReadCount: 'noReadCount' }")
    }
  },
  {
    name: '【缺陷⑤】墓碑帧预览顺带把未读数 +1（删消息反而把会话标红点）',
    apply () {
      mutate(CORE_REL,
        "    sql: 'update chat_session_user set last_message = ? where user_id = ? and contact_id = ?',\n    params: [lastMessage, userId, contactId]",
        "    sql: 'update chat_session_user set last_message = ?, no_read_count = no_read_count + 1 where user_id = ? and contact_id = ?',\n    params: [lastMessage, userId, contactId]")
    }
  },
  {
    name: '【缺陷⑥】「当前正看着的会话不计未读」判定方向反转',
    apply () {
      mutate(CORE_REL,
        '  if (sessionId !== currentSessionId) {',
        '  if (sessionId === currentSessionId) {')
    }
  },
  {
    name: '【缺陷⑦】稍后处理 INSERT 的列与参数顺序错位（messageId 写进 sessionId 列）',
    apply () {
      mutate(CORE_REL,
        '      row.userId, row.messageId, row.sessionId, row.contactId,',
        '      row.userId, row.sessionId, row.messageId, row.contactId,')
    }
  },
  {
    name: '【缺陷⑧】端口分配不再对空表回落（空库首登拿到端口 1 → 占用系统端口）',
    apply () {
      mutate(CORE_REL,
        '  if (currentMax === null || currentMax === undefined) return FIRST_SERVER_PORT',
        '  if (currentMax === undefined) return FIRST_SERVER_PORT')
    }
  }
]

console.log('===== 主进程本地 db 构造核心门禁变异检验 =====\n')
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