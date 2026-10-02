#!/usr/bin/env node
// 变异检验：故意破坏 ChannelContextUtils 的在线状态隐私逻辑，
// 验证 ChannelContextUtilsOnlineStatusTest 是否真的会失败（测试是否有判别力）。
//
// 依据 AGENTS.md §2.1 第 3 条：新增方法若跳过 TDD 红阶段，须用变异检验补偿。
//
// ⚠ 三条纪律（本脚本自身的踩坑记录，勿删）：
//   1. Windows 上 mvn 是 mvn.cmd，execFileSync 不能直接执行 —— 必须走 cmd /c，
//      否则 ENOENT 使 e.status 为 null，被 `!== 0` 误判成「捕获成功」。
//   2. `exit === null` 与 `[SKIP]`（锚点未命中）必须一律判失败。
//   3. 源文件是 CRLF，锚点字符串必须写 \r\n，否则正则/字符串匹配不中。
//
// 用法：node scripts/verify/mutation_channel_online_status.cjs
// ⚠ 会临时改写 ChannelContextUtils.java 并还原；**务必确保工作区该文件无未提交改动**。
// 耗时：每条变异跑一次 mvn test（约 10–20s），8 条约 2–3 分钟。
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const JAVA_ROOT = path.join(ROOT, 'easychat-java')
const TARGET = path.join(JAVA_ROOT, 'src/main/java/com/easychat/websocket/ChannelContextUtils.java')
const original = fs.readFileSync(TARGET, 'utf8')

// Windows 上 Maven 是 mvn.cmd，execFileSync 不经 shell 无法执行 .cmd
const MVN = process.env.MVN_BIN ||
  'D:\\apache-maven-3.9.11\\apache-maven-3.9.11\\bin\\mvn.cmd'

// 前置守卫
try {
  const dirty = execFileSync('git', ['status', '--porcelain', '--', TARGET],
    { cwd: ROOT, encoding: 'utf8', stdio: 'pipe' }).trim()
  if (dirty) {
    console.error('[mutation] 拒绝执行：ChannelContextUtils.java 有未提交改动，还原会覆盖你的工作：\n' + dirty)
    process.exit(2)
  }
} catch (e) {
  console.error('[mutation] git status 失败：' + (e.message || e))
  process.exit(2)
}

function runTests () {
  try {
    const out = execFileSync('cmd', ['/c', MVN, '-B', 'test', '-Dtest=ChannelContextUtilsOnlineStatusTest'],
      { cwd: JAVA_ROOT, encoding: 'utf8', stdio: 'pipe', timeout: 900000 })
    return { code: 0, out }
  } catch (e) {
    return { code: e.status, out: (e.stdout || '') + (e.stderr || '') }
  }
}

const mutations = [
  {
    name: '变异1 去掉广播前的开关判定（关闭隐私后仍广播）',
    find: '        if (!isOnlineStatusVisible(userId)) {',
    repl: '        if (false) {'
  },
  {
    name: '变异2 isOnlineStatusVisible 恒返回 true（开关完全失效）',
    find: '    private boolean isOnlineStatusVisible(String userId) {',
    repl: '    private boolean isOnlineStatusVisible(String userId) {\n        if (true) return true;'
  },
  {
    name: '变异3 抹除帧错用 ONLINE_STATUS(22) 而非 27',
    find: 'hiddenDto.setMessageType(MessageTypeEnum.ONLINE_STATUS_HIDDEN.getType());',
    repl: 'hiddenDto.setMessageType(MessageTypeEnum.ONLINE_STATUS.getType());'
  },
  {
    name: '变异4 抹除帧不带 hidden 标记（客户端无从判定）',
    find: 'extend.put("hidden", Boolean.TRUE);',
    repl: 'extend.put("hidden", Boolean.FALSE);'
  },
  {
    name: '变异5 抹除帧也推给离线好友（浪费且可能泄漏状态）',
    find: '            if (StringTools.isEmpty(friendId) || !isUserOnline(friendId)) {\r\n                continue;\r\n            }\r\n            MessageSendDto hiddenDto = new MessageSendDto();',
    repl: '            if (StringTools.isEmpty(friendId)) {\r\n                continue;\r\n            }\r\n            MessageSendDto hiddenDto = new MessageSendDto();'
  },
  {
    name: '变异6 抹除帧改成读开关（导致关不掉残留状态点）',
    find: '    public void pushOnlineStatusHidden(String userId) {\r\n        if (StringTools.isEmpty(userId)) {\r\n            return;\r\n        }\r\n        List<String> contactList',
    repl: '    public void pushOnlineStatusHidden(String userId) {\r\n        if (StringTools.isEmpty(userId)) {\r\n            return;\r\n        }\r\n        if (!isOnlineStatusVisible(userId)) {\r\n            return;\r\n        }\r\n        List<String> contactList'
  },
  {
    name: '变异7 抹除帧 contactId 写错成自己以外的值',
    find: 'hiddenDto.setContactId(userId);',
    repl: 'hiddenDto.setContactId("WRONG_ID");'
  },
  {
    name: '变异8 抹除帧不设 sendUserId（客户端无法识别来源）',
    find: 'hiddenDto.setSendUserId(userId);',
    repl: 'hiddenDto.setSendUserId(null);'
  }
]

let allCaught = true
let skipped = 0
console.log('=== 变异检验：ChannelContextUtilsOnlineStatusTest ===')
console.log('（临时改写 ChannelContextUtils.java，结束时还原；每条跑一次 mvn test）\n')

for (const m of mutations) {
  if (!original.includes(m.find)) {
    console.log(`  [FAIL ] ${m.name} —— 锚点未命中，变异未生效（脚本需更新，不算通过）`)
    allCaught = false
    skipped++
    continue
  }
  const mutated = original.replace(m.find, m.repl)
  if (mutated === original) {
    console.log(`  [FAIL ] ${m.name} —— 变异后内容未变化`)
    allCaught = false
    skipped++
    continue
  }
  try {
    fs.writeFileSync(TARGET, mutated, 'utf8')
    const r = runTests()
    const caught = r.code !== 0 && r.code !== null
    if (!caught) allCaught = false
    console.log(`  [${caught ? 'CAUGHT' : 'MISSED'}] ${m.name} → exit=${r.code}`)
    if (caught) {
      const fails = (r.out || '').split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l.startsWith('[ERROR]   ChannelContextUtilsOnlineStatusTest'))
      fails.slice(0, 3).forEach((l) => console.log('            ' + l))
      if (fails.length > 3) console.log(`            ...（共 ${fails.length} 条用例转红）`)
    }
  } finally {
    fs.writeFileSync(TARGET, original, 'utf8')
  }
}

const base = runTests()
const baseOk = base.code === 0
if (!baseOk) allCaught = false
console.log(`\n  [${baseOk ? 'PASS' : 'FAIL'}] 还原后基线复跑 exit=${base.code}`)

console.log('')
if (skipped > 0) console.log(`注意：有 ${skipped} 条变异因锚点未命中而未执行（不算通过）。`)
console.log(allCaught
  ? `结论：全部 ${mutations.length} 条变异均被捕获，测试有判别力`
  : '结论：存在未捕获变异，测试无判别力，需修复')
process.exit(allCaught ? 0 : 1)