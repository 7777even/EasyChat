#!/usr/bin/env node
// 变异检验：故意把 dbSqlCore.mjs / ADB.js 退回缺陷实现，
// 验证 verify_local_db_core.mjs 是否真的会失败。
//
// 目的：证明门禁**有判别力**（AGENTS §2.1 第 1 条）。
// 本门禁锁定两个「静默失效」缺陷：① where 条件真值过滤导致更新命中范围扩大
// ② 字段不在列映射时被静默丢弃。若门禁恒绿，下次重构把它们改回去也无人察觉。
//
// 沙箱而非改真文件：门禁的 ROOT 由脚本自身位置解析，把被读文件一起复制到
// 临时目录，它就只读沙箱副本。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进 FILES，
//   否则沙箱缺文件 → 门禁在沙箱里恒红 → 后续「全部捕获」全是假通过。
//
// ⚠ 本脚本**必须**区分「门禁判定失败」与「门禁自己崩了」：
//   非 0 退出但没有任何 [FAIL] 行 = 门禁崩溃，不算捕获。
//   2026-10-04 在通话门禁上踩过：Windows 下 ESM 路径未转 file:// URL，
//   门禁根本没跑起来就退出，变异脚本却报出 100% 假通过。
//
// 用法：node scripts/verify/mutation_local_db_core.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_local_db_core.mjs'

const FILES = [
  'easychat-front/src/main/db/dbSqlCore.mjs',
  'easychat-front/src/main/db/ADB.js'
]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-localdb-'))
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
 * ⚠ 锚点匹配**必须对换行不敏感**。
 *   仓库里的 ADB.js 是 CRLF（Windows 上用 edit 工具写入），
 *   而锚点里写的是 `\n` —— 直接 includes() 恒为 MISS。
 *   实测 5/12 个用例因「锚点未命中」被判 [无效]，而汇总行却写「12/12 全部捕获」，
 *   差点又交出一份没跑过的检验。故：先归一化 LF，写回时按原文风格还原。
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

const CORE = FILES[0]
const ADB = FILES[1]

const CASES = [
  {
    name: '【缺陷①】where 条件退回真值过滤（空串/0 被静默丢弃 → 更新命中范围扩大）',
    apply () {
      mutate(CORE,
        '    if (v === undefined || v === null) continue\n    if (map[item] === undefined) {\n      dropped.push(item)\n      continue\n    }\n    whereClauses.push',
        '    if (!v) continue\n    if (map[item] === undefined) {\n      dropped.push(item)\n      continue\n    }\n    whereClauses.push')
    }
  },
  {
    name: '【缺陷②】字段不在列映射时不再报入 dropped（静默丢弃复活）',
    apply () {
      mutate(CORE,
        '    if (map[item] === undefined) {\n      dropped.push(item)\n      continue\n    }\n    dbColumns.push',
        '    if (map[item] === undefined) {\n      continue\n    }\n    dbColumns.push')
    }
  },
  {
    name: 'UPDATE 短路保护失效：空 set 也照样拼 SQL',
    apply () {
      mutate(CORE,
        "  if (setClauses.length === 0) {\n    return { sql: null, params, skipped: true, reason: 'set 子句为空（无可更新列）', dropped }\n  }\n",
        '')
    }
  },
  {
    name: 'UPDATE 短路保护失效：空 where 也照样拼 SQL（退化成全表更新）',
    apply () {
      mutate(CORE,
        "  if (whereClauses.length === 0) {\n    return { sql: null, params, skipped: true, reason: 'where 条件为空（会退化成全表更新）', dropped }\n  }\n",
        '')
    }
  },
  {
    name: 'where 条件字段未校验列映射（拼出 `undefined = ?`）',
    apply () {
      mutate(CORE,
        '    if (map[item] === undefined) {\n      dropped.push(item)\n      continue\n    }\n    whereClauses.push',
        '    whereClauses.push')
    }
  },
  {
    name: 'set 值 null 被误判为未提供（无法显式写 null）',
    apply () {
      mutate(CORE,
        'function isWritable (value) {\n  return value !== undefined\n}',
        'function isWritable (value) {\n  return value !== undefined && value !== null\n}')
    }
  },
  {
    name: '列映射缺失时不再安全跳过（直接抛异常）',
    apply () {
      mutate(CORE, '  const map = columnsMap || {}\n  const setClauses = []',
        '  const map = columnsMap\n  const setClauses = []')
    }
  },
  {
    name: 'alter 判定不再过滤已存在的列（add column 不幂等 → 二次启动报错）',
    apply () {
      mutate(CORE,
        '    if (!exists) pending.push({ tableName: item.tableName, field: item.field, sql: item.sql })',
        '    pending.push({ tableName: item.tableName, field: item.field, sql: item.sql })')
    }
  },
  {
    name: 'alter 判定对 pragma 返回 null 不做防御（非数组即抛异常）',
    apply () {
      mutate(CORE,
        '    const exists = Array.isArray(fieldList) && fieldList.some((row) => row && row.name === item.field)',
        '    const exists = fieldList.some((row) => row.name === item.field)')
    }
  },
  {
    name: 'ADB.js 绕开纯核心、自行内联真值过滤（缺陷①原地复活）',
    apply () {
      mutate(ADB,
        '    const { sql, params, skipped, reason, dropped } = buildUpdateSql(\n        tableName, data, paramData, globalColumnsMap[tableName]);',
        '    const sql = "update " + tableName;\n    const params = [];\n    const skipped = false, reason = "", dropped = [];\n    for (const item in paramData) { if (paramData[item]) params.push(paramData[item]); }')
    }
  },
  {
    name: 'ADB.js 绕开纯核心、自行拼 INSERT（不再经过 buildInsertSql）',
    apply () {
      mutate(ADB,
        '    const { sql, params, dropped } = buildInsertSql(\n        sqlPrefix, tableName, data, globalColumnsMap[tableName]);',
        '    const sql = sqlPrefix + " " + tableName;\n    const params = Object.values(data || {});\n    const dropped = [];')
    }
  },
  {
    name: 'ADB.js 退回「查完 pragma 立刻执行 ALTER」（非幂等）',
    apply () {
      mutate(ADB,
        '        const pending = [];\n        for (const item of alter_tables) {\n            const fieldList = await queryAll(`pragma table_info(${item.tableName})`, []);\n            const exists = Array.isArray(fieldList) && fieldList.some(row => row && row.name === item.field);\n            if (!exists) {\n                pending.push(item);\n            }\n        }\n        for (const item of pending) {\n            await run(item.sql, []);\n        }',
        '        for (const item of alter_tables) {\n            const fieldList = await queryAll(`pragma table_info(${item.tableName})`, []);\n            const field = fieldList.some(row => row.name === item.field);\n            if (!field) {\n                await run(item.sql, []);\n            }\n        }')
    }
  }
]

console.log('===== 本地 DB 门禁变异检验 =====\n')
console.log(`沙箱：${sandbox}`)
console.log(`用例数：${CASES.length}\n`)

// 前置自检：未变异的沙箱必须先跑通。
// 少了这一步，所有「捕获」都可能只是门禁在崩（2026-10-04 在通话门禁上踩中）。
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
  }
}

fs.rmSync(sandbox, { recursive: true, force: true })
// ⚠ 汇总行必须计入 [无效] 的数量。
//   初版固定打印「N/N 全部捕获」，而实测 5/12 因锚点未命中被判 [无效] ——
//   汇总行仍然显示 100%，**与「全部捕获」完全无法区分**。
//   这正是基线自检要防的那类假通过，不能在汇总处再漏一次。
console.log(`\n===== 结论：${CASES.length - invalid}/${CASES.length} 个变异被门禁捕获`
  + `${invalid > 0 ? `，${invalid} 个用例无效（变异未生效）` : ''} =====`)
if (allCaught) {
  console.log('✓ 门禁有判别力')
  process.exit(0)
}
console.log('✗ 存在无判别力的断言或无效用例')
process.exit(1)