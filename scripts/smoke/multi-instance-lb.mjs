#!/usr/bin/env node
/**
 * LB 分流下的跨实例通话冒烟 —— 两种 LB 形态
 *
 * <p><b>形态 A（默认）内置 round-robin TCP 代理</b>：HTTP 8088 → 5050|5055，WS 8099 → 5051|5056。
 * 它与真实 LB 的差别：只做 TCP 转发，<b>不处理 HTTP 层</b>（Host 头、upgrade 头、超时…）。
 *
 * <p><b>形态 B（{@code MI_LB_MODE=external}）真实 nginx</b>：外部 LB（容器）按真实配置
 * 转发 HTTP 与 WebSocket upgrade。此时「两个客户端落在不同副本」不再由脚本自证，
 * 而是从 <b>nginx 自己的 access log</b>（{@code $upstream_addr}）里读出来 ——
 * <b>证据来自被测系统本身</b>，而不是来自测试脚本的记账。
 *
 * <p>两条断言，缺一不可：
 * <ol>
 *   <li><b>前提</b>：两个 WS 连接**确实落在不同实例**。不断言这条，
 *       后面的「全通过」可能只是两个客户端挤在同一个实例上（**假阳性**）。</li>
 *   <li><b>行为</b>：INVITE / JOIN / SIGNAL×2 / HANGUP 五步全部跨实例送达。</li>
 * </ol>
 *
 * <p>前置：seed-test-users.sql 已执行；两个实例已起（HTTP 5050/5055、WS 5051/5056）。
 * 形态 B 另需：外部 LB 已就绪（默认 8088/8099），且 {@code MI_NGINX_LOG} 指向其 access log。
 *
 * <p>退出码：0 符合预期 / 1 断言失败 / 2 环境前提不成立
 */
import { existsSync, readFileSync } from 'node:fs'
import {
  PORTS, PAIRS, loadWs, login, connectWs, createReporter, startRoundRobinLb, sleep
} from './lib/smoke-lib.mjs'

const EXTERNAL = process.env.MI_LB_MODE === 'external'
const EXT_HTTP_PORT = Number(process.env.MI_LB_HTTP || 8088)
const EXT_WS_PORT = Number(process.env.MI_LB_WS || 8099)
const LB_LOG = process.env.MI_LB_LOG || ''
const LB_LOG_FILTER = process.env.MI_LB_LOG_FILTER || '/ws'
const LB_LOG_EXTRACT = process.env.MI_LB_LOG_EXTRACT || '([^|\\s]+)$'

const P = PAIRS[0]
const R = createReporter(
  (EXTERNAL ? '外部 LB（真实 nginx）' : '内置 round-robin TCP 代理') + '下的跨实例通话冒烟' +
  `\nLB HTTP ${EXTERNAL ? EXT_HTTP_PORT : PORTS.lbHttp}   LB WS ${EXTERNAL ? EXT_WS_PORT : PORTS.lbWs}` +
  `   后端 HTTP ${PORTS.httpA}|${PORTS.httpB}   WS ${PORTS.wsA}|${PORTS.wsB}`
)

const LB_HTTP = `http://127.0.0.1:${EXTERNAL ? EXT_HTTP_PORT : PORTS.lbHttp}/api`
const LB_WS = `ws://127.0.0.1:${EXTERNAL ? EXT_WS_PORT : PORTS.lbWs}/ws`

let cA
let cB
let hb
let lbHttp
let lbWs
const cleanup = () => {
  if (hb) clearInterval(hb)
  try { lbHttp && lbHttp.server.close() } catch { /* ignore */ }
  try { lbWs && lbWs.server.close() } catch { /* ignore */ }
  try { cA && cA.close() } catch { /* ignore */ }
  try { cB && cB.close() } catch { /* ignore */ }
}
const exitWith = (code) => { cleanup(); setTimeout(() => process.exit(code), 150) }

/**
 * 外部 LB 形态：读 LB 自己的日志，提取「本次连接落到哪个后端副本」。
 *
 * <p>取证方式可配（不同 LB 的日志格式不同）：
 * <ul>
 *   <li>{@code MI_LB_LOG} —— 日志文件路径（nginx 挂载出来的 access log）；</li>
 *   <li>{@code MI_LB_LOG_FILTER} —— 筛选行的子串（默认 `/ws`，只取 WS 连接）；</li>
 *   <li>{@code MI_LB_LOG_EXTRACT} —— 提取「后端标识」的**正则（须含一个捕获组）**，
 *       默认取行尾字段（nginx 的 {@code $upstream_addr} 就是行尾）。
 *       HAProxy 用 {@code server (\S+)} 即可（raw 日志里形如 `... backend ws_be/server b2`）。</li>
 * </ul>
 */
function lbBackendsFromLog (sinceLines) {
  if (!LB_LOG || !existsSync(LB_LOG)) return null
  const all = readFileSync(LB_LOG, 'utf8').split(/\r?\n/).filter(Boolean)
  const re = new RegExp(LB_LOG_EXTRACT)
  return all.slice(sinceLines)
    .filter((l) => l.includes(LB_LOG_FILTER))
    .map((l) => { const m = l.match(re); return m ? m[1] : null })
    .filter(Boolean)
}
function lbLogLineCount () {
  if (!LB_LOG || !existsSync(LB_LOG)) return 0
  return readFileSync(LB_LOG, 'utf8').split(/\r?\n/).filter(Boolean).length
}

async function main () {
  const logBefore = lbLogLineCount()
  if (!EXTERNAL) {
    lbHttp = startRoundRobinLb(PORTS.lbHttp, [PORTS.httpA, PORTS.httpB])
    lbWs = startRoundRobinLb(PORTS.lbWs, [PORTS.wsA, PORTS.wsB])
    await sleep(300)
  }

  const WebSocket = loadWs()
  let tokA
  let tokB
  try {
    tokA = await login(LB_HTTP, P.mail[0])
    tokB = await login(LB_HTTP, P.mail[1])
  } catch (e) { R.fatal(e.message); return }
  R.info(`A、B 均经 LB 登录成功（${LB_HTTP}）`)

  try {
    cA = await connectWs(WebSocket, LB_WS, tokA, 'A')
    cB = await connectWs(WebSocket, LB_WS, tokB, 'B')
  } catch (e) { R.fatal(e.message); return }

  // ── 前提断言：两个客户端确实落在不同副本 ──────────────────────
  if (EXTERNAL) {
    // 外部 LB：等 LB 把连接日志刷出来（WS 请求在**连接关闭**时才记 access log）
    cA.close()
    cB.close()
    await sleep(800)
    const ups = lbBackendsFromLog(logBefore) || []
    const distinct = [...new Set(ups)]
    R.info(`LB 日志中 WS 连接的后端标识 = [${ups.join(', ')}]（日志：${LB_LOG || '未配置'}）`)
    R.ok('外部 LB 分流：A 与 B 落在**不同**实例（证据取自 LB 自己的日志）',
      distinct.length >= 2, `去重后 ${distinct.length} 个后端标识`)
    if (distinct.length < 2) {
      R.summary('（未能证明分流到不同实例，后续断言无意义）')
      exitWith(1)
      return
    }
    // 重新连上（前面的连接已被关闭）
    try {
      cA = await connectWs(WebSocket, LB_WS, tokA, 'A')
      cB = await connectWs(WebSocket, LB_WS, tokB, 'B')
    } catch (e) { R.fatal(e.message); return }
  } else {
    const [wsA, wsB] = lbWs.assignments
    R.info(`内置代理的分发记录 = [${lbWs.assignments.join(', ')}]  →  A 落在 :${wsA}，B 落在 :${wsB}`)
    R.ok('LB 分流：A 与 B 落在**不同**实例', lbWs.assignments.length >= 2 && wsA !== wsB,
      `分发 = [${lbWs.assignments.join(', ')}]`)
    if (lbWs.assignments.length < 2 || wsA === wsB) {
      R.summary('（未实现跨实例分流，后续断言无意义）')
      exitWith(1)
      return
    }
  }

  hb = setInterval(() => {
    try { cA.send({ messageType: -4 }); cB.send({ messageType: -4 }) } catch { /* 连接已断 */ }
  }, 8000)

  const callId = `lb-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`

  R.head('[1] CALL_INVITE(-10) A→B（经 LB）')
  cA.send({ messageType: -10, callType: 1, mediaType: 1, callId, toUserId: P.b })
  const invite = await cB.wait((f) => f.messageType === -10 && f.callId === callId, 8000)
  R.ok('跨实例：INVITE 送达 B', !!invite, invite ? `fromUserId=${invite.fromUserId}` : 'B 未收到 INVITE')
  if (!invite) { R.summary(); exitWith(1); return }

  R.head('[2] CALL_ACCEPT(-11) B→A')
  cB.send({ messageType: -11, callId })
  const join = await cA.wait((f) => f.messageType === -17 && f.callId === callId, 8000)
  R.ok('跨实例：ACCEPT 触发的 JOIN 送达 A', !!join, join ? `members=${JSON.stringify(join.members)}` : 'A 未收到 JOIN')

  R.head('[3] CALL_SIGNAL(-13) offer A→B')
  cA.send({ messageType: -13, callId, toUserId: P.b, signalType: 'offer', sdp: 'v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n' })
  const s1 = await cB.wait((f) => f.messageType === -13 && f.signalType === 'offer', 8000)
  R.ok('跨实例：offer 送达 B', !!s1, s1 ? `fromUserId=${s1.fromUserId}` : 'B 未收到 offer')

  R.head('[4] CALL_SIGNAL(-13) answer B→A')
  cB.send({ messageType: -13, callId, toUserId: P.a, signalType: 'answer', sdp: 'v=0\r\no=- 2 1 IN IP4 127.0.0.1\r\n' })
  const s2 = await cA.wait((f) => f.messageType === -13 && f.signalType === 'answer', 8000)
  R.ok('跨实例：answer 送达 A', !!s2, s2 ? `fromUserId=${s2.fromUserId}` : 'A 未收到 answer')

  R.head('[5] CALL_HANGUP(-14) A→B')
  cA.send({ messageType: -14, callId })
  const hang = await cB.wait((f) => f.messageType === -14 && f.callId === callId, 8000)
  R.ok('跨实例：HANGUP 送达 B', !!hang, hang ? `fromUserId=${hang.fromUserId}` : 'B 未收到 HANGUP')

  await sleep(2000)
  console.log(`\n  callId=${callId}`)
  R.summary()
  exitWith(R.fail === 0 ? 0 : 1)
}

main().catch((e) => { console.error(e); exitWith(1) })
