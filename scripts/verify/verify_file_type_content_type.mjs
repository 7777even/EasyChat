/**
 * verify_file_type_content_type.mjs
 *
 * 门禁：前端「本地文件服务器」的 fileType → content-type 映射表必须覆盖所有
 *      聊天消息实际用到的 fileType，否则浏览器无法解码、文件渲染静默失败。
 *
 * 为什么需要（真实事故）：
 *   2026-10-03 接通语音消息时发现 `easychat-front/src/main/file.js` 的
 *   `FILE_TYPE_CONTENT_TYPE` 只有 {0:image/, 1:video/, 2:application/octet-stream}，
 *   而语音消息的 `fileType=3`。缺失导致 content-type 拼成 "undefinedwebm"，
 *   浏览器无法按音频解码 → `<audio>` 播不出声音，且**不抛任何错**。
 *
 *   这类缺陷 `verify_ws_frame_parity.mjs` 照不到（它只对账 WS 帧号），
 *   也不在 HTTP 请求流里（发生在本地 express 文件服务器），属于独立的一层。
 *
 * 判定：
 *   [ERROR] 后端在用的 fileType 值没在前端映射表里 → 阻断
 *   [ERROR] 映射表缺 fileType=3（语音）→ 阻断
 *   [ERROR] 映射值不是合法 MIME 前缀（必须以 / 结尾）→ 阻断
 *
 * 退出码：0 = 无 ERROR；1 = 有 ERROR
 */
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const FILE_JS = join(ROOT, 'easychat-front/src/main/file.js')
const MSG_SEND_VUE = join(ROOT, 'easychat-front/src/renderer/src/views/chat/MessageSend.vue')
const CHAT_MESSAGE_VUE = join(ROOT, 'easychat-front/src/renderer/src/views/chat/ChatMessage.vue')

const results = []
const err = (msg, detail) => results.push({ level: 'ERROR', msg, detail })
const pass = (msg) => results.push({ level: 'PASS', msg })

if (!existsSync(FILE_JS)) {
  console.error('[file-type-content-type] 找不到 ' + FILE_JS)
  process.exit(1)
}

const fileJs = readFileSync(FILE_JS, 'utf8')

// ── 1. 解析 FILE_TYPE_CONTENT_TYPE 映射表 ──────────────────
const mapMatch = fileJs.match(/const\s+FILE_TYPE_CONTENT_TYPE\s*=\s*\{([\s\S]*?)\}/)
if (!mapMatch) {
  err('未能解析 FILE_TYPE_CONTENT_TYPE（file.js 结构可能已变）',
    '请手工核对 easychat-front/src/main/file.js 的 MIME 映射表')
  report()
}

const map = new Map()
for (const m of mapMatch[1].matchAll(/"(\d+)"\s*:\s*"([^"]*)"/g)) {
  map.set(Number(m[1]), m[2])
}

// ── 2. 收集前端实际用到的 fileType ──────────────────────────
// 扫 MessageSend.vue 的 fileType: N 与 ChatMessage.vue 的 data.fileType == N
const used = new Map() // fileType -> 来源描述
for (const f of [MSG_SEND_VUE, CHAT_MESSAGE_VUE]) {
  if (!existsSync(f)) continue
  const src = readFileSync(f, 'utf8')
  const base = f.split('/').pop()
  for (const m of src.matchAll(/fileType\s*[:=]{1,3}\s*(\d+)/g)) {
    used.set(Number(m[1]), base)
  }
  for (const m of src.matchAll(/fileType\s*==\s*(\d+)/g)) {
    used.set(Number(m[1]), base)
  }
}

// ── 3. 判定 ─────────────────────────────────────────────────
if (map.size === 0) {
  err('FILE_TYPE_CONTENT_TYPE 为空，所有文件的 content-type 都会是 undefined')
} else {
  pass(`解析到 FILE_TYPE_CONTENT_TYPE ${map.size} 项：${[...map.entries()].map(([k, v]) => `${k}:${v}`).join('、')}`)
}

const missing = [...used.keys()].filter((t) => !map.has(t))
if (missing.length === 0) {
  pass(`前端用到的 ${used.size} 个 fileType 全部有 MIME 映射：${[...used.keys()].sort((a, b) => a - b).join('、')}`)
} else {
  for (const t of missing.sort((a, b) => a - b)) {
    err(
      `★ 前端用到 fileType=${t}，但 FILE_TYPE_CONTENT_TYPE 无此项 → content-type 拼成 "undefined<ext>"`,
      `来源：${used.get(t)}。浏览器无法解码，文件渲染静默失败（不抛错）`
    )
  }
}

// 语音消息（messageType=24）固定 fileType=3，必须显式映射为 audio/
if (!map.has(3)) {
  err('★ fileType=3（语音）无 MIME 映射 —— 语音消息的 <audio> 将播不出声音')
} else if (!map.get(3).startsWith('audio/')) {
  err(`fileType=3（语音）的 MIME 应为 audio/*，实际为 "${map.get(3)}"`)
} else {
  pass('fileType=3（语音）→ ' + map.get(3) + '（浏览器可按音频解码）')
}

// MIME 前缀必须以 / 结尾，否则拼上后缀后不是合法 MIME
for (const [k, v] of map) {
  if (!v.endsWith('/')) {
    err(`FILE_TYPE_CONTENT_TYPE[${k}] = "${v}" 不是合法 MIME 前缀（必须以 / 结尾）`,
      '拼接后缀后形如 "undefinedwebm"，浏览器不识别')
  }
}

report()

function report () {
  console.log('=== 本地文件服务器 fileType → content-type 门禁 ===\n')
  for (const r of results) {
    console.log(`   [${r.level}] ${r.msg}${r.detail ? `\n          → ${r.detail}` : ''}`)
  }
  const errors = results.filter((r) => r.level === 'ERROR').length
  console.log(`\n===== 结论：${errors} 错误 =====`)
  if (errors > 0) {
    console.log('（ERROR 阻断：文件渲染会静默失败）')
    process.exit(1)
  }
}