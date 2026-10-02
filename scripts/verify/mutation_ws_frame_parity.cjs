#!/usr/bin/env node
// 变异检验：故意破坏 WS 帧协议两端，验证 verify_ws_frame_parity.mjs 是否真的会失败。
//
// 目的：证明门禁**有判别力**，而不是「脚本存在所以有门禁」。
// 依据 AGENTS.md §2.1 第 1 条：任何写入 CI / Git hook 的脚本，交付前必须实跑并贴出退出码。
//
// ⚠ 三条纪律（本脚本自身的踩坑记录，勿删）：
//   1. Windows 上 mvn 是 mvn.cmd，execFileSync 不能直接执行 —— 必须走 cmd /c，
//      否则 ENOENT 使 e.status 为 null，被 `!== 0` 误判成「捕获成功」。
//   2. `exit === null`（命令启动失败）与 `[SKIP]`（锚点未命中）必须一律判为失败。
//      只看汇总的 [CAUGHT] 会被这些假通过骗过去。
//   3. 锚点用 CRLF 敏感的确切字符串；改动源文件后先跑本脚本确认锚点仍命中。
//
// 用法：node scripts/verify/mutation_ws_frame_parity.cjs
// ⚠ 会临时改写 3 个源文件并还原；**务必确保工作区无未提交改动**，否则还原会覆盖你的工作。
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const CLIENT = path.join(ROOT, 'easychat-front/src/main/wsClient.js')
const ENUM = path.join(ROOT, 'easychat-java/src/main/java/com/easychat/entity/enums/MessageTypeEnum.java')
const CHAT = path.join(ROOT, 'easychat-java/src/main/java/com/easychat/service/impl/ChatMessageServiceImpl.java')

const targets = [CLIENT, ENUM, CHAT]
const originals = {}
for (const t of targets) originals[t] = fs.readFileSync(t, 'utf8')

// 前置守卫：工作区必须干净，否则还原会覆盖未提交改动
try {
  const dirty = execFileSync('git', ['status', '--porcelain', '--', ...targets],
    { cwd: ROOT, encoding: 'utf8', stdio: 'pipe' }).trim()
  if (dirty) {
    console.error('[mutation] 拒绝执行：以下目标文件有未提交改动，还原会覆盖你的工作：\n' + dirty)
    console.error('[mutation] 请先提交或 stash，再重跑。')
    process.exit(2)
  }
} catch (e) {
  console.error('[mutation] git status 失败：' + (e.message || e))
  process.exit(2)
}

function restoreAll () {
  for (const t of targets) fs.writeFileSync(t, originals[t], 'utf8')
}

function runGate () {
  try {
    execFileSync('node', [path.join(ROOT, 'scripts/verify/verify_ws_frame_parity.mjs')],
      { cwd: ROOT, encoding: 'utf8', stdio: 'pipe' })
    return { code: 0, out: '' }
  } catch (e) {
    // e.status === null 表示命令根本没启动成功（ENOENT 等），必须当作失败而非「非零即捕获」
    return { code: e.status, started: true, out: (e.stdout || '') + (e.stderr || '') }
  }
}

// 每条：find 必须在原文里精确命中，replace 后内容必须变化
const mutations = [
  {
    name: '变异1 客户端加了服务端不存在的帧（协议漂移）',
    file: CLIENT,
    find: '            case 27: {',
    repl: '            case 99: {\n                break;\n            }\n            case 27: {'
  },
  {
    name: '变异2 客户端删掉 case 22（在线状态帧静默失效）',
    file: CLIENT,
    find: 'case 22: { // ONLINE_STATUS：在线状态变更帧',
    repl: 'case 220: { // ONLINE_STATUS：在线状态变更帧'
  },
  {
    name: '变异3 客户端删掉 case 19（群公告帧静默失效）',
    file: CLIENT,
    find: '            case 19:',
    repl: '            case 1900:'
  },
  {
    name: '变异7 客户端把 case 14 改成别的号（撤回消息静默失效）',
    file: CLIENT,
    find: '            case 14://撤回消息',
    repl: '            case 140://撤回消息'
  },
  {
    name: '变异4 服务端帧号重复（27 改成 26）',
    file: ENUM,
    find: 'ONLINE_STATUS_HIDDEN(27,',
    repl: 'ONLINE_STATUS_HIDDEN(26,'
  },
  {
    name: '变异5 服务端新增帧 28 但客户端未接（意图未声明）',
    file: ENUM,
    find: 'ONLINE_STATUS_HIDDEN(27,',
    repl: 'BRAND_NEW_FRAME(28, "", "新帧");\n\n    ONLINE_STATUS_HIDDEN(27,'
  },
  {
    name: '变异6 落库白名单被清空（结构改写 → 本项检查失效）',
    file: CHAT,
    find: 'ArraysUtil.contains(new Integer[]{\r\n                MessageTypeEnum.CHAT.getType(),\r\n                MessageTypeEnum.GROUP_CREATE.getType(),\r\n                MessageTypeEnum.ADD_FRIEND.getType(),\r\n                MessageTypeEnum.MEDIA_CHAT.getType()\r\n        }, messageTypeEnum.getType()))',
    repl: 'ArraysUtil.contains(new Integer[]{\r\n        }, messageTypeEnum.getType()))'
  },
  {
    name: '变异8 落库白名单引用了枚举里不存在的项（源码与枚举不同步）',
    file: CHAT,
    find: 'MessageTypeEnum.MEDIA_CHAT.getType()\r\n        }, messageTypeEnum.getType())) {',
    repl: 'MessageTypeEnum.NOT_EXIST_ENUM_NAME.getType()\r\n        }, messageTypeEnum.getType())) {'
  },
  {
    name: '变异9 KNOWN_GAP 过期（25 已落库但没删登记）',
    file: CHAT,
    find: 'MessageTypeEnum.MEDIA_CHAT.getType()\r\n        }, messageTypeEnum.getType())) {',
    repl: 'MessageTypeEnum.MEDIA_CHAT.getType(),\r\n                MessageTypeEnum.LOCATION.getType()\r\n        }, messageTypeEnum.getType())) {'
  }
]

let allCaught = true
let skipped = 0
console.log('=== 变异检验：verify_ws_frame_parity.mjs ===')
console.log('（临时改写 ' + targets.length + ' 个源文件，结束时全部还原）\n')

for (const m of mutations) {
  const src = originals[m.file]
  if (!src.includes(m.find)) {
    console.log(`  [FAIL ] ${m.name} —— 锚点未命中，变异未生效（脚本需更新，不算通过）`)
    allCaught = false
    skipped++
    continue
  }
  const mutated = src.replace(m.find, m.repl)
  if (mutated === src) {
    console.log(`  [FAIL ] ${m.name} —— 变异后内容未变化`)
    allCaught = false
    skipped++
    continue
  }
  try {
    restoreAll()
    fs.writeFileSync(m.file, mutated, 'utf8')
    const r = runGate()
    const caught = r.code !== 0 && r.code !== null
    if (!caught) allCaught = false
    console.log(`  [${caught ? 'CAUGHT' : 'MISSED'}] ${m.name} → exit=${r.code}`)
    if (caught) {
      const errs = (r.out || '').split(/\r?\n/)
        .filter((l) => l.includes('[ERROR]'))
        .map((l) => '            ' + l.trim())
      errs.slice(0, 3).forEach((l) => console.log(l))
      if (errs.length > 3) console.log(`            ...（共 ${errs.length} 条 ERROR）`)
    }
  } finally {
    restoreAll()
  }
}

const base = runGate()
const baseOk = base.code === 0
if (!baseOk) allCaught = false
console.log(`\n  [${baseOk ? 'PASS' : 'FAIL'}] 还原后基线复跑 exit=${base.code}`)

console.log('')
if (skipped > 0) console.log(`注意：有 ${skipped} 条变异因锚点未命中而未执行（不算通过）。`)
console.log(allCaught
  ? `结论：全部 ${mutations.length} 条变异均被捕获，门禁有判别力`
  : '结论：存在未被捕获的变异，门禁无判别力，需修复')
process.exit(allCaught ? 0 : 1)