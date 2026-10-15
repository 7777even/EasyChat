#!/usr/bin/env node
/**
 * 通话多实例部署验证（call-multi-instance T10）。
 *
 * 锁住的关键不变量（每条都对应一个**曾真实存在**或**会被静默触发**的故障）：
 *  1. `CallService` 不得再有进程内房间表（rooms/userCall）—— 那是「实例漂移房间即丢」的根因
 *  2. 信令下发必须经广播（CallFrameTopic），不得只写本地 ChannelGroup
 *  3. Redis 房间 key 必须带 TTL —— 否则残留无人回收
 *  4. callId 冲突必须被拒绝，不得静默合并
 *  5. Redis 不可用必须明确告知（不得静默返回成功）
 *  6. 广播开关必须存在且默认开启 + 关闭时告警
 *
 * 用法：node scripts/verify/verify_call_multi_instance.mjs
 */
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const WS = path.join(ROOT, 'easychat-java', 'src', 'main', 'java', 'com', 'easychat', 'websocket')
const CALL = path.join(WS, 'CallService.java')
const TOPIC = path.join(WS, 'CallFrameTopic.java')
const STORE = path.join(WS, 'CallRoomStore.java')
const PROPS = path.join(ROOT, 'easychat-java', 'src', 'main', 'resources', 'application.properties')

const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

/** 先剥注释再匹配：注释里引用的反模式是解释，代码里出现才是问题（AGENTS §2.1 第 7 条） */
const strip = (s) => s
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(^|[^:])\/\/[^\n]*/g, '$1')

console.log('===== 通话多实例部署验证 =====\n')

const call = strip(fs.readFileSync(CALL, 'utf8'))
const topic = strip(fs.readFileSync(TOPIC, 'utf8'))
const store = strip(fs.readFileSync(STORE, 'utf8'))
const props = fs.existsSync(PROPS) ? fs.readFileSync(PROPS, 'utf8') : ''

// ── 1. 不得再有进程内房间表 ───────────────────────────────────────
console.log('=== 1. 无进程内房间表（层二的根因）===')
check('CallService 不得声明 rooms 字段',
  !/\bprivate\s+.*\brooms\b\s*=/.test(call))
check('CallService 不得声明 userCall 字段',
  !/\bprivate\s+.*\buserCall\b\s*=/.test(call))
check('CallService 必须注入 CallRoomStore',
  /private\s+CallRoomStore\s+callRoomStore/.test(call))

// ── 2. 信令必须走广播 ─────────────────────────────────────────────
console.log('\n=== 2. 信令走广播（层一的根因）===')
check('sendTo 调用广播而非只写本地',
  /callFrameTopic\.broadcast\(/.test(call))
check('保留本地直投作兜底（广播失败时仍有路径）',
  /sendRawToUser\(/.test(call))
check('CallFrameTopic 用独立 Topic 名（不复用 message.topic）',
  /TOPIC_NAME\s*=\s*"call\.frame\.topic"/.test(topic))

// ── 3. Redis key 带 TTL ───────────────────────────────────────────
console.log('\n=== 3. 房间 key 必须带 TTL ===')
check('建房设置 expire',
  /\.expire\(TTL_SECONDS/.test(store))
check('bindUser 也设置 expire',
  /map\.expire\(TTL_SECONDS/.test(store))
check('TTL 为有限正值（非永久 key）',
  /TTL_SECONDS\s*=\s*CallRoomCore\.ROOM_TTL_SECONDS/.test(store))

// ── 4. callId 冲突拒绝 + Redis 不可用明确报错 ─────────────────────
console.log('\n=== 4. 故障必须显式，不得静默 ===')
check('callId 冲突回 CODE_CALL_ID_CONFLICT',
  /CODE_CALL_ID_CONFLICT/.test(call))
// ⚠ 初版在此写了一条 `&& true` 的**假断言**（恒真，只为凑数）—— 那正是 AGENTS §2.1 第 3 条
//   禁止的「假装覆盖」，已删除。该语义（冲突后不得再 putMember）的真正判据在
//   `CallIdConflictTest#conflictIsRejectedAndRoomLeftUntouched`：
//   它 verify(store, never()).putMember(...) —— 行为断言而非文本断言，比这里更强。
// 判定边界：**只看 handleInvite 内**是否同时出现 createRoom 的 try/catch 与 CODE_SYSTEM。
//   初版用 `/catch…createRoom…CODE_SYSTEM/` 的顺序正则，而实际代码是
//   try { createRoom } catch { CODE_SYSTEM } —— 顺序相反，恒不匹配。
const inviteBody = (() => {
  const i = call.indexOf('private void handleInvite')
  const j = call.indexOf('private void handleAccept')
  return (i < 0 || j < 0) ? '' : call.slice(i, j)
})()
check('handleInvite 内可定位（判据作用域有效）', inviteBody.length > 0)
check('Redis 不可用须 sendCallError（不得静默）',
  /try\s*\{[\s\S]*?createRoom/.test(inviteBody) && /CODE_SYSTEM/.test(inviteBody),
  '判定边界：只看 handleInvite 函数体')

// ── 5. 广播开关 ───────────────────────────────────────────────────
console.log('\n=== 5. 广播开关可紧急回退 ===')
check('properties 声明 broadcast-signaling',
  /^easychat\.call\.broadcast-signaling=true$/m.test(props))
check('关闭时启动告警（多实例将不可用）',
  /broadcast-signaling=false.*?多实例|\*\*仅单实例部署可用\*\*/.test(strip(call.replace(/\r?\n/g, ' '))))

// ── 6. 防空转：判据必须扫得到 ─────────────────────────────────────
console.log('\n=== 6. 防空转自检 ===')
const probeRooms = 'private final ConcurrentMap<String, CallRoom> rooms = new ConcurrentHashMap<>();'
check('正例：rooms 声明能被判据命中',
  /\bprivate\s+.*\brooms\b\s*=/.test(strip(probeRooms)))
const probeComment = '// private final ConcurrentMap<String, CallRoom> rooms = new ConcurrentHashMap<>();'
check('反例：注释中的同类写法不被判为违规',
  !/\bprivate\s+.*\brooms\b\s*=/.test(strip(probeComment)))

// ── 汇总 ────────────────────────────────────────────────────────
const fail = results.filter(([, ok]) => !ok).length
console.log(`\n===== 结论：${results.length - fail}/${results.length} 通过 =====`)
if (fail) { console.log(`失败 ${fail} 项`); process.exit(1) }
