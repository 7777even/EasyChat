/**
 * verify_ws_frame_parity.mjs
 *
 * 门禁：WebSocket 帧协议双向对账（服务端 MessageTypeEnum ↔ 客户端 wsClient.js case）
 *
 * 为什么需要（真实事故）：
 *   2026-10-02 发现服务端直推 messageType=25（LOCATION 位置消息），
 *   但客户端 wsClient.js 的 switch 没有 case 25 —— **对端实时收不到位置消息**，
 *   而发送方自己的回显、历史消息拉取（loadHistoryMessage）都正常，
 *   因此常规冒烟全绿、spec 也曾验收通过。只有把两端帧号摆在一起对账才现形。
 *
 * 三类判定：
 *   [ERROR] 客户端 case 在服务端枚举中不存在（负数通话帧走白名单）→ 协议漂移，阻断
 *   [ERROR] 服务端「落库白名单」里的帧，客户端无对应 case → 历史漫游都拉不到，阻断
 *   [ERROR] 「需实时处理的帧」在客户端无对应 case → 静默失效，阻断
 *   [ERROR] 服务端帧号重复 / 不连续有洞 → 阻断
 *   [WARN]  枚举中存在但既不落库、也无客户端 case 的帧 → 「功能未接通」候选，仅提示
 *
 * 退出码：0 = 无 ERROR；1 = 有 ERROR
 */
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const ENUM_FILE = join(ROOT, 'easychat-java/src/main/java/com/easychat/entity/enums/MessageTypeEnum.java')
const CLIENT_FILE = join(ROOT, 'easychat-front/src/main/wsClient.js')
const CHAT_MSG_IMPL = join(ROOT, 'easychat-java/src/main/java/com/easychat/service/impl/ChatMessageServiceImpl.java')

const results = []
const err = (msg, detail) => results.push({ level: 'ERROR', msg, detail })
const warn = (msg, detail) => results.push({ level: 'WARN', msg, detail })
const pass = (msg) => results.push({ level: 'PASS', msg })

/**
 * 「必须走 wsClient 实时 case」的帧。
 * 声明式：服务端新增帧号后，若未同步更新本清单，门禁会因「枚举里有此帧但清单没有」
 * 而提示（见下方 WARN 项），提示人确认该帧到底需不需要实时处理。
 *
 * 不在清单里的枚举帧 = 服务端内部帧（投递前被转换掉，客户端永不会收到）：
 *   13 ADD_FRIEND_SELF —— applyContactConvert 在投递前转成 ADD_FRIEND(1)
 */
const MUST_HANDLE = {
  // 真实消息路径（落本地库 + reciveMessage）
  0: 'INIT 连接初始化',
  1: 'ADD_FRIEND 好友打招呼',
  2: 'CHAT 普通聊天',
  3: 'GROUP_CREATE 群创建',
  4: 'CONTACT_APPLY 好友/入群申请',
  5: 'MEDIA_CHAT 媒体消息',
  6: 'FILE_UPLOAD 文件上传完成',
  7: 'FORCE_OFF_LINE 强制下线',
  8: 'DISSOLUTION_GROUP 解散群聊',
  9: 'ADD_GROUP 加入群聊',
  10: 'CONTACT_NAME_UPDATE 更新群昵称',
  11: 'LEAVE_GROUP 退出群聊',
  12: 'REMOVE_GROUP 被移出群聊',
  14: 'RECALL_MESSAGE 撤回消息',
  15: 'MOMENT_NEW 朋友圈新动态',
  16: 'MOMENT_LIKE 朋友圈点赞',
  17: 'MOMENT_COMMENT 朋友圈评论',
  18: 'MOMENT_AT 朋友圈@提醒',
  19: 'GROUP_NOTICE 群公告更新',
  20: 'ADMIN_DELETE 管理员删除消息',
  21: 'TYPING_STATUS 正在输入',
  22: 'ONLINE_STATUS 在线状态变更',
  26: 'NUDGE 拍一拍',
  27: 'ONLINE_STATUS_HIDDEN 在线状态已隐藏',
  // 通话信令帧：负数号段，不在 MessageTypeEnum 中（服务端按 int 常量硬编码）
  '-10': 'CALL_INVITE',
  '-11': 'CALL_ACCEPT',
  '-12': 'CALL_REJECT',
  '-13': 'CALL_SIGNAL',
  '-14': 'CALL_HANGUP',
  '-15': 'CALL_CANCEL',
  '-16': 'CALL_BUSY',
  '-17': 'CALL_JOIN'
}

// ── 解析服务端枚举 ──────────────────────────────────────────
let serverFramesRaw = []

function parseServerFrames () {
  if (!existsSync(ENUM_FILE)) throw new Error('找不到 ' + ENUM_FILE)
  const src = readFileSync(ENUM_FILE, 'utf8')
  const frames = []
  // 形如  ONLINE_STATUS_HIDDEN(27, "", "在线状态已隐藏");
  const re = /^\s{4}([A-Z_][A-Z0-9_]*)\((-?\d+)\s*,/gm
  let m
  while ((m = re.exec(src)) !== null) {
    frames.push({ name: m[1], type: Number(m[2]) })
  }
  serverFramesRaw = frames
  return frames
}

// ── 解析客户端 case ─────────────────────────────────────────
function parseClientCases () {
  if (!existsSync(CLIENT_FILE)) throw new Error('找不到 ' + CLIENT_FILE)
  const src = readFileSync(CLIENT_FILE, 'utf8')
  const cases = []
  const re = /^\s*case\s+(-?\d+)\s*:/gm
  let m
  while ((m = re.exec(src)) !== null) {
    cases.push(Number(m[1]))
  }
  return cases
}

// ── 解析服务端「落库白名单」──���────────────────────────────────
// ChatMessageServiceImpl 里 `ArraysUtil.contains(new Integer[]{ MessageTypeEnum.X.getType(), ... }, messageTypeEnum.getType())`
// 是唯一决定「该消息是否写入 chat_message 表」的条件。落在白名单里的帧是持久化消息，
// 因此**必须**有客户端 case —— 否则连历史漫游（loadHistoryMessage）都拿不到它。
function parsePersistTypes () {
  if (!existsSync(CHAT_MSG_IMPL)) return null
  const src = readFileSync(CHAT_MSG_IMPL, 'utf8')
  // 抓 `ArraysUtil.contains(new Integer[]{ ... }` 到其闭合括号之间的枚举引用
  const start = src.indexOf('ArraysUtil.contains(new Integer[]{')
  if (start < 0) return null
  const open = src.indexOf('{', start)
  let depth = 0
  let end = open
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') {
      depth--
      if (depth === 0) { end = i; break }
    }
  }
  const block = src.slice(open, end + 1)
  const names = [...block.matchAll(/MessageTypeEnum\.([A-Z_]+)\.getType\(\)/g)].map((m) => m[1])
  if (names.length === 0) {
    // 白名单被清空/结构被改写 —— 返回空 Map 让主流程走「解析失败」告警分支，
    // 不能静默当成「没有落库帧」（那会让本项检查整体失效而不报错）
    return new Map()
  }
  const map = new Map()
  for (const n of names) {
    // 名 → 帧号：借服务端枚举表反查
    const hit = serverFramesRaw.find((f) => f.name === n)
    if (!hit) {
      // 枚举表里没有这个名字 → 源码与 MessageTypeEnum 不同步，必须暴露而不是忽略
      throw new Error(
        `ChatMessageServiceImpl 落库白名单引用了 MessageTypeEnum 中不存在的项 ${n}（枚举表未同步？）`
      )
    }
    map.set(hit.type, n)
  }
  return map
}

/**
 * 已知「功能未接通」登记项：既不在服务端落库白名单、客户端也无 case 的帧。
 * 门禁每次都把它们打印出来，让技术债保持可见（而不是靠人记忆）。
 * 若有人把其中某帧加入落库白名单或客户端 case，本表需同步删除 → 门禁会提示不一致。
 *
 * ⚠ 这两项是 2026-10-02 本门禁首次运行时**发现的既有缺陷**，非本次引入：
 *   DB 中 message_type 只有 1/2，0 条 24/25 记录 → 这两个功能从未被真实使用过。
 *   修复需改服务端落库白名单（业务能力变更，L3），故此处仅登记不修。
 */
const KNOWN_GAP = {
  25: 'LOCATION 位置消息：落库白名单未含 + wsClient 无 case + Chat.vue:136 分发条件未含 → 端到端未接通（2026-10-02 parity 门禁发现）',
  24: 'VOICE 语音消息：落库白名单未含 + wsClient 无 case + ChatMessageVoice.vue 为死组件（从未 import）→ 端到端未接通（同上）'
}

/**
 * 服务端内部帧 / 渲染→服务端请求帧：客户端**本就不该**有 case，显式登记以消除噪音。
 */
const INTERNAL_FRAMES = {
  13: 'ADD_FRIEND_SELF：applyContactConvert 在投递前转成 ADD_FRIEND(1)，客户端永不会收到',
  23: 'USER_STATUS_CHANGE：渲染→服务端的「改状态」请求帧，不是服务端推送'
}

// ── 主流程 ──────────────────────────────────────────────────
let serverFrames, clientCases
try {
  serverFrames = parseServerFrames()
  clientCases = parseClientCases()
} catch (e) {
  console.error('[ws-frame-parity] 解析失败:', e.message)
  process.exit(1)
}

console.log('=== WebSocket 帧协议双向对账 ===\n')
console.log(`服务端 MessageTypeEnum: ${serverFrames.length} 帧`)
console.log(`客户端 wsClient case : ${clientCases.length} 个\n`)

// 1. 服务端帧号唯一性与连续性
const typeCount = new Map()
for (const f of serverFrames) {
  typeCount.set(f.type, (typeCount.get(f.type) || 0) + 1)
}
let dupFound = false
for (const [type, count] of typeCount) {
  if (count > 1) {
    err(`服务端帧号重复：type=${type} 出现 ${count} 次`, serverFrames.filter((f) => f.type === type).map((f) => f.name).join(', '))
    dupFound = true
  }
}
if (!dupFound) pass('服务端帧号无重复')

const serverTypes = serverFrames.filter((f) => f.type >= 0).map((f) => f.type).sort((a, b) => a - b)
let holeFound = false
for (let i = 1; i < serverTypes.length; i++) {
  if (serverTypes[i] !== serverTypes[i - 1] + 1) {
    err(`服务端帧号不连续：${serverTypes[i - 1]} 与 ${serverTypes[i]} 之间有空洞`)
    holeFound = true
  }
}
if (!holeFound) pass(`服务端帧号 0..${serverTypes[serverTypes.length - 1]} 连续无空洞`)

// 2. 客户端 case 必须都能在服务端找到（负数走 MUST_HANDLE 白名单）
const clientUnknown = clientCases.filter((c) => {
  if (c < 0) return false
  return !typeCount.has(c)
})
if (clientUnknown.length === 0) {
  pass('客户端 case 全部存在于服务端枚举（含负数通话帧）')
} else {
  err(
    `客户端处理了服务端不存在的帧（协议漂移）：${clientUnknown.join(', ')}`,
    '可能是 MessageTypeEnum 被删/改号，或客户端残留了已废弃分支'
  )
}

// 3. 「需实时处理」的帧必须有客户端 case —— 这是抓「静默失效」的关键项
const clientCaseSet = new Set(clientCases)
const missingMust = Object.keys(MUST_HANDLE)
  .map(Number)
  .filter((t) => !clientCaseSet.has(t))
if (missingMust.length === 0) {
  pass(`「需实时处理」的 ${Object.keys(MUST_HANDLE).length} 个帧在客户端均有 case`)
} else {
  for (const t of missingMust) {
    err(
      `★ 需实时处理的帧在客户端无 case（功能静默失效）：type=${t} ${MUST_HANDLE[String(t)]}`,
      '服务端会直推该帧，客户端 switch 未匹配 → 不落库、不转发、历史拉取能补但实时收不到'
    )
  }
}

// 4. MUST_HANDLE 中声明了但服务端枚举没有的（清单过期）
const stale = Object.keys(MUST_HANDLE)
  .map(Number)
  .filter((t) => t >= 0 && !typeCount.has(t))
if (stale.length === 0) {
  pass('MUST_HANDLE 清单与服务端枚举一致（无过期项）')
} else {
  err(`MUST_HANDLE 清单已过期：${stale.join(', ')} 不在服务端枚举中`, '帧被删除或改号，请同步更新本脚本')
}

// 5. ★ 落库帧必须有客户端 case（从服务端源码自动推导，不靠人工清单）
//    落 chat_message 表 = 持久化消息，客户端无 case 则本地库无行、
//    云端漫游（loadHistoryMessage）虽能拉到却不被分发渲染 → 彻底看不见。
const persistTypes = parsePersistTypes()
const persistSet = new Set(persistTypes ? [...persistTypes.keys()] : [])
if (!persistTypes || persistTypes.size === 0) {
  // 阻断而非告警：本项是「落库帧 → 客户端 case」链路完整性的唯一保障。
  // 解析失败（结构改写 / 白名单被清空）会让本项整体失效，必须要求人来看，
  // 绝不能静默通过 —— 否则门禁在结构漂移后变成永远绿的空壳。
  err(
    '未能解析服务端落库白名单（ChatMessageServiceImpl 结构可能已变或白名单被清空）',
    '本项检查无法执行。请确认 ArraysUtil.contains(new Integer[]{...}, ...) 结构，或更新本脚本的解析逻辑'
  )
} else {
  console.log(`\n服务端落库白名单（ChatMessageServiceImpl）：${[...persistTypes.entries()].map(([t, n]) => `${t}:${n}`).join('、')}`)
  const persistMissing = [...persistTypes.keys()].filter((t) => !clientCaseSet.has(t))
  if (persistMissing.length === 0) {
    pass(`全部 ${persistTypes.size} 个落库帧在客户端均有 case（历史漫游链路完整）`)
  } else {
    for (const t of persistMissing) {
      err(
        `★ 落库帧在客户端无 case（历史漫游也拿不到）：type=${t} ${persistTypes.get(t)}`,
        '消息已写入 chat_message 表，但客户端无 case → 不落本地库、不转发、不渲染'
      )
    }
  }
}

// 6. 「功能未接通」帧：既不落库、客户端也无 case，且未在 KNOWN_GAP 登记
const declaredSet = new Set(Object.keys(MUST_HANDLE).map(Number))
const knownGapSet = new Set(Object.keys(KNOWN_GAP).map(Number))
const internalSet = new Set(Object.keys(INTERNAL_FRAMES).map(Number))
const unwired = serverFrames.filter(
  (f) => !declaredSet.has(f.type) && !persistSet.has(f.type) &&
         !internalSet.has(f.type) && !knownGapSet.has(f.type)
)
if (unwired.length === 0) {
  pass('无「未登记意图」的帧（每个帧都已声明为 MUST_HANDLE / 落库帧 / 内部帧 / 已知缺口）')
} else {
  // 阻断而非告警：新增帧号后必须显式声明意图，否则「服务端加了帧、客户端没接」会静默通过
  err(
    `枚举中有 ${unwired.length} 个帧既未声明为 MUST_HANDLE、也不在落库白名单或 INTERNAL_FRAMES 中`,
    unwired.map((f) => `${f.type} ${f.name}`).join('、') +
      ' —— 新增帧号后必须显式声明：需实时处理则加进 MUST_HANDLE；服务端内部/请求帧则加进 INTERNAL_FRAMES；已知未接通则加进 KNOWN_GAP'
  )
}

// 7. KNOWN_GAP 一致性：登记为缺口但其实已接通 → 清单该删了
const gapStale = [...knownGapSet].filter((t) => persistSet.has(t) || clientCaseSet.has(t))
if (gapStale.length === 0) {
  pass(`KNOWN_GAP 登记的 ${knownGapSet.size} 个缺口仍然成立（未接通）`)
} else {
  err(
    `KNOWN_GAP 已过期：${gapStale.join(', ')} 现已落库或已有客户端 case`,
    '说明该缺口已被修复，请从本脚本 KNOWN_GAP 中删除对应条目'
  )
}

const internalStale = Object.keys(INTERNAL_FRAMES).map(Number).filter((t) => clientCaseSet.has(t))
if (internalStale.length > 0) {
  warn(`INTERNAL_FRAMES 登记已过期：${internalStale.join(', ')} 客户端已有 case，不再是内部帧`)
}

// 8. 已知缺口清单（每次都打印，保持技术债可见）
if (knownGapSet.size > 0) {
  console.log('\n--- 已知「功能未接通」帧（技术债，非阻断）---')
  for (const [t, why] of Object.entries(KNOWN_GAP)) {
    console.log(`   [GAP] type=${t} ${why}`)
  }
}

// ── 输出 ────────────────────────────────────────────────────
for (const r of results) {
  const line = `   [${r.level}] ${r.msg}${r.detail ? `\n          → ${r.detail}` : ''}`
  console.log(line)
}

const errors = results.filter((r) => r.level === 'ERROR').length
const warns = results.filter((r) => r.level === 'WARN').length
console.log(`\n===== 结论：${errors} 错误 / ${warns} 警告 =====`)
if (errors > 0) {
  console.log('（ERROR 阻断：协议漂移或功能静默失效，必须修）')
  process.exit(1)
}