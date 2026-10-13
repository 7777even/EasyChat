#!/usr/bin/env node
// 变异检验：故意把 exportChatCore.mjs 退回缺陷实现，
// 验证 verify_export_chat_core.mjs 是否真的会失败。
//
// 目的（AGENTS §2.1 第 1 条 / 第 14 条）：证明这条门禁有判别力。
//   被测核心含一条**安全控制**：`csvCell` 的 CSV 公式注入防护（OWASP CSV Injection）。
//   聊天内容来自**对方**，「=cmd|'/c calc'!A1」这类载荷若原样写入 CSV，
//   受害者用 Excel 打开导出记录时即会执行。若门禁恒绿，下次重构去掉该前缀无人察觉。
//
// 沙箱而非改真文件：门禁的 ROOT 由脚本自身位置解析，它只 import 纯核心 .mjs
//   （无 electron / fs / node_modules 依赖），故复制两个文件到临时目录即可 ——
//   不碰工作区、无需 git 干净。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进 FILES，
//   否则沙箱缺文件 → 门禁在沙箱里恒红 → 后续「全部捕获」全是假通过。
//
// ⚠ 本脚本**必须**区分「门禁判定失败」与「门禁自己崩了」：
//   非 0 退出但没有任何 [FAIL] 行 = 门禁崩溃，不算捕获。
//
// ⚠ 刻意**不列入**的等价变异（AGENTS §2.1 第 3 条：不做假覆盖）：
//   ① `pad` 改成模板串补零：输出等价；
//   ② `buildTxt` 的 `lines.join('\n')` 改用 `\r\n`：换行风格差异，
//      属「有意的格式选择」而非缺陷，且门禁对该项本就不断言换行符。
//
// 用法：node scripts/verify/mutation_export_chat_core.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_export_chat_core.mjs'
const CORE_REL = 'easychat-front/src/main/exportChatCore.mjs'

const FILES = [CORE_REL]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-exportchat-'))
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
 * ⚠ 锚点匹配**必须对换行不敏感**：exportChatCore.mjs 是 **CRLF**，
 *   锚点里写 `\n` 会恒不命中 → 变异静默空转而汇总行照常显示（AGENTS §2.1 第 10 条 ④）。
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
    name: '【缺陷① · 安全】剥前导空白那一步被去掉（「␣=cmd|… 」绕过注入防护）',
    apply () {
      // 这正是 2026-10-06 首次运行门禁时抓出的真实安全缺口（OWASP CSV Injection）。
      mutate(CORE_REL,
        "    const probe = v.replace(/^[\\s﻿ ]+/, '')\n    if (/^[=+\\-@]/.test(probe)) {",
        "    const probe = v\n    if (/^[=+\\-@]/.test(probe)) {")
    }
  },
  {
    name: '【缺陷② · 安全】公式前缀（单引号）不再添加（公式注入防护整体失效）',
    apply () {
      mutate(CORE_REL,
        "        // 前缀直接加在原始内容上（保留空白），Excel 才会把整格视作文本\n        v = \"'\" + v",
        "        // 前缀直接加在原始内容上（保留空白），Excel 才会把整格视作文本\n        v = v")
    }
  },
  {
    name: '【缺陷③】触发字符集漏掉 `-`（负数/公式以减号开头的载荷绕过）',
    apply () {
      mutate(CORE_REL, "if (/^[=+\\-@]/.test(probe)) {", "if (/^[=+@]/.test(probe)) {")
    }
  },
  {
    name: '【缺陷④】CSV 双引号不再翻倍（引号未转义 → 列错位 / 结构被改写）',
    apply () {
      mutate(CORE_REL,
        "    return '\"' + v.replace(/\"/g, '\"\"') + '\"'",
        "    return '\"' + v + '\"'")
    }
  },
  {
    name: '【缺陷⑤】单元格内换行不再转空格（多行内容撑破 CSV 结构）',
    apply () {
      mutate(CORE_REL, "    v = v.replace(/\\r?\\n/g, ' ')", '    v = v')
    }
  },
  {
    name: '【缺陷⑥】CSV 丢失 UTF-8 BOM（Excel 打开中文乱码）',
    apply () {
      mutate(CORE_REL, "    return '\\uFEFF' + rows.join('\\r\\n')", "    return rows.join('\\r\\n')")
    }
  },
  {
    name: '【缺陷⑦】media 消息内容不再降级为占位符（把文件名/二进制当正文导出）',
    apply () {
      mutate(CORE_REL,
        "        const label = FILE_TYPE_LABEL[Number(row.file_type)] || '文件'\n        if (Number(row.file_type) === 2) {\n            return `[${label}] ${row.file_name || ''}`.trim()\n        }\n        return `[${label}]`",
        "        return row.message_content == null ? '' : String(row.message_content)")
    }
  },
  {
    name: '【缺陷⑧】文件名非法字符不再替换（保存对话框 / 落盘失败）',
    apply () {
      mutate(CORE_REL,
        "    return String(name || '').replace(/[\\\\/:*?\"<>|]/g, '_').slice(0, 50) || 'chat'",
        "    return String(name || '').slice(0, 50) || 'chat'")
    }
  },
  {
    name: '【缺陷⑨】非法时间戳不再回落为空串（导出出现 Invalid Date）',
    apply () {
      mutate(CORE_REL, '    if (isNaN(d.getTime())) return \'\'', '    if (isNaN(d.getTime())) d.setTime(0)')
    }
  },
  {
    name: '【缺陷⑩】云端全量排序方向反转（导出顺序倒置 → 消息时间乱序）',
    apply () {
      mutate(CORE_REL,
        'rows.slice().sort((a, b) => Number(a.send_time) - Number(b.send_time))',
        'rows.slice().sort((a, b) => Number(b.send_time) - Number(a.send_time))')
    }
  }
]

console.log('===== 聊天导出核心门禁变异检验 =====\n')
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