#!/usr/bin/env node
// 变异检验：故意让基线与活库产生四类漂移，验证 verify_schema_drift.mjs 是否真的会失败。
//
// 目的：证明门禁**有判别力**，而不是「脚本存在所以有门禁」（AGENTS §2.1 第 1 条）。
// 这条门禁尤其需要变异证明——它面对的是一个**真实存在的库**，
// 若解析器写错导致它恒绿，它就会给「迁移已对齐」这个结论盖上假的官方印章。
//
// 沙箱而非改真文件：门禁的 BASELINE 由脚本自身位置解析 ROOT 得出，
// 因此把 easychat.sql 与门禁脚本一起复制到临时目录，它就会只读沙箱副本。
// 活库连接不受影响（本就该对着真库验）。因此**无需脏工作区守卫**。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进下面的 BASELINE_FILES，
//   否则沙箱缺文件 → 门禁在沙箱里恒红 → 后续「全部捕获」全是假通过。
//
// 用法：node scripts/verify/mutation_schema_drift.mjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_schema_drift.mjs'
const BASELINE_REL = 'easychat.sql'

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-schemagate-'))
let allCaught = true
let skipped = 0

function resetSandbox () {
  fs.rmSync(sandbox, { recursive: true, force: true })
  fs.mkdirSync(sandbox, { recursive: true })
  for (const rel of [BASELINE_REL]) {
    const dst = path.join(sandbox, rel)
    fs.mkdirSync(path.dirname(dst), { recursive: true })
    fs.copyFileSync(path.join(ROOT, rel), dst)
  }
  const gateDst = path.join(sandbox, GATE_REL)
  fs.mkdirSync(path.dirname(gateDst), { recursive: true })
  fs.copyFileSync(path.join(ROOT, GATE_REL), gateDst)
}

function runGate (extraArgs = []) {
  try {
    execFileSync('node', [path.join(sandbox, GATE_REL), ...extraArgs], {
      cwd: sandbox, encoding: 'utf8', stdio: 'pipe', maxBuffer: 32 * 1024 * 1024
    })
    return { code: 0, out: '' }
  } catch (e) {
    return {
      code: e.status === undefined ? null : e.status,
      out: (e.stdout || '') + (e.stderr || '')
    }
  }
}

function mutateBaseline (find, repl) {
  const p = path.join(sandbox, BASELINE_REL)
  const src = fs.readFileSync(p, 'utf8')
  // ⚠️ 锚点换行不敏感（AGENTS §2.1 第 10 条 ④）：easychat.sql 在 Windows checkout 下是 CRLF，
  //   而锚点里写的是字面量 `\n` → includes 恒 false → 变异静默空转而汇总行仍显示已捕获。
  const eol = src.includes('\r\n') ? '\r\n' : '\n'
  const srcLf = src.replace(/\r\n/g, '\n')
  const findLf = find.replace(/\r\n/g, '\n')
  const replLf = repl.replace(/\r\n/g, '\n')
  if (!srcLf.includes(findLf)) return '锚点未命中'
  const mutated = srcLf.replace(findLf, replLf)
  if (mutated === srcLf) return '变异后内容未变化'
  fs.writeFileSync(p, mutated.replace(/\n/g, eol), 'utf8')
  return null
}

const mutations = [
  {
    name: '变异1 基线新增一张活库没有的表（对应「改了基线但没建表」）',
    find: '-- ----------------------------\r\n-- Table structure for chat_message\r\n-- ----------------------------\r\nDROP TABLE IF EXISTS `chat_message`;',
    repl: 'DROP TABLE IF EXISTS `ec_probe_missing_tbl`;\r\nCREATE TABLE `ec_probe_missing_tbl` (\r\n  `id` int(11) NOT NULL AUTO_INCREMENT,\r\n  PRIMARY KEY (`id`) USING BTREE\r\n) ENGINE=InnoDB;\r\n\r\n-- ----------------------------\r\n-- Table structure for chat_message\r\n-- ----------------------------\r\nDROP TABLE IF EXISTS `chat_message`;'
  },
  {
    name: '变异2 基线给已有表加一列（对应「改了基线但没 ALTER 出来」）',
    find: "  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',\r\n  `status` tinyint(1) NULL DEFAULT NULL COMMENT '0:未发布 1:灰度发布 2:全网发布',",
    repl: "  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',\r\n  `ec_probe_col` varchar(10) NULL DEFAULT NULL COMMENT '漂移探针',\r\n  `status` tinyint(1) NULL DEFAULT NULL COMMENT '0:未发布 1:灰度发布 2:全网发布',"
  },
  {
    name: '变异3 基线把 password 列宽改回 32（2026-10-02 真实事故形态）',
    find: "`password` varchar(60)",
    repl: "`password` varchar(32)"
  },
  {
    name: '变异4 基线把 password 列类型换成 text（应触发类型漂移 + 硬不变量）',
    find: "`password` varchar(60)",
    repl: "`password` text"
  },
  {
    name: '变异5 解析器失配：把 CREATE TABLE 关键字改掉（门禁必须自己发现解析为 0 张表）',
    find: 'CREATE TABLE `app_update`',
    repl: 'CREATE TABL `app_update`'
  }
]

console.log('=== 变异检验：verify_schema_drift.mjs ===')
console.log('（沙箱基线副本 + 活库真连，不触碰工作区任何文件）')
console.log('沙箱：' + sandbox + '\n')

const real = (() => {
  try {
    execFileSync('node', [path.join(ROOT, GATE_REL)], { cwd: ROOT, encoding: 'utf8', stdio: 'pipe', maxBuffer: 32 * 1024 * 1024 })
    return 0
  } catch (e) { return e.status === undefined ? null : e.status }
})()

resetSandbox()
const base = runGate()
const matched = base.code === real
if (!matched) allCaught = false
console.log(`  [${matched ? 'PASS' : 'FAIL'}] 沙箱基线与真实基线一致：真实 exit=${real} / 沙箱 exit=${base.code}`)
console.log('         （真实门禁当前应为绿=0；若无 ERROR，变异才有意义）\n')

for (const m of mutations) {
  resetSandbox()
  const problem = mutateBaseline(m.find, m.repl)
  if (problem) {
    console.log(`  [FAIL ] ${m.name} —— ${problem}（脚本需更新，不算通过）`)
    allCaught = false
    skipped++
    continue
  }
  const r = runGate()
  const caught = r.code !== 0 && r.code !== null
  if (!caught) allCaught = false
  console.log(`  [${caught ? 'CAUGHT' : 'MISSED'}] ${m.name} → exit=${r.code}`)
  if (caught) {
    const lines = (r.out || '').split(/\r?\n/)
      .filter((l) => l.includes('[ERROR]'))
      .map((l) => '            ' + l.trim().replace(/\s+/g, ' '))
    lines.slice(0, 2).forEach((l) => console.log(l))
    if (lines.length > 2) console.log(`            ...（共 ${lines.length} 条 ERROR）`)
  }
}

fs.rmSync(sandbox, { recursive: true, force: true })

const executed = mutations.length - skipped
console.log('')
if (skipped > 0) console.log(`注意：有 ${skipped} 条变异因锚点未命中而未执行（不算通过）。`)
console.log(allCaught
  ? `结论：${executed}/${mutations.length} 条变异均被捕获 + 基线一致，门禁有判别力`
  : '结论：存在未被捕获的变异或基线不一致，门禁无判别力，需修复')
process.exit(allCaught ? 0 : 1)