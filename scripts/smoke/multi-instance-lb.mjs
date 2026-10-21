#!/usr/bin/env node
/**
 * LB 分流下的跨实例通话冒烟 —— 模拟「外部 LB + N 个后端副本」部署形态
 *
 * 形态：
 *        客户端 A ──┐
 *        客户端 B ──┼──► LB(HTTP 8088 → 5050|5055, WS 8099 → 5051|5056) ──► 实例A / 实例B
 *                   （连接级 round-robin，同一 Redis + MySQL）
 *
 * 两条断言，缺一不可：
 *   [前提断言] 两个 WS 连接**确实落在不同实例**（以 LB 的分发记录为准）。
 *              —— 不断言这条，后面的「全通过」可能只是两个客户端挤在同一实例上，属假阳性。
 *   [行为断言] INVITE / JOIN / SIGNAL×2 / HANGUP 五步全部跨实例送达。
 *
 * 前置：seed-test-users.sql 已执行；两个实例已起（HTTP 5050/5055、WS 5051/5056）。
 * 退出码：0 符合预期 / 1 断言失败 / 2 环境前提不成立
 */
import {
  PORTS, URLS, PAIRS, loadWs, login, connectWs, createReporter, startRoundRobinLb, sleep
} from './lib/smoke-lib.mjs'

const P = PAIRS[0]
const R = createReporter(
  'LB 分流下的跨实例通话冒烟' +
  `\nHTTP LB ${PORTS.lbHttp} → ${PORTS.httpA}|${PORTS.httpB}   WS LB ${PORTS.lbWs} → ${PORTS.wsA}|${PORTS.wsB}`
)

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
const exitWith = (code) => {
  cleanup()
  setTimeout(() => process.exit(code), 150)
}

async function main () {
  lbHttp = startRoundRobinLb(PORTS.lbHttp, [PORTS.httpA, PORTS.httpB])
  lbWs = startRoundRobinLb(PORTS.lbWs, [PORTS.wsA, PORTS.wsB])
  await sleep(300)

  const WebSocket = loadWs()
  let tokA
  let tokB
  try {
    tokA = await login(URLS.lbHttp, P.mail[0])
    tokB = await login(URLS.lbHttp, P.mail[1])
  } catch (e) { R.fatal(e.message); return }
  R.info(`A、B 均经 HTTP LB 登录成功；HTTP 分发记录 = [${lbHttp.assignments.join(', ')}]`)

  try {
    cA = await connectWs(WebSocket, URLS.lbWs, tokA, 'A@LB')
    cB = await connectWs(WebSocket, URLS.lbWs, tokB, 'B@LB')
  } catch (e) { R.fatal(e.message); return }

  const [wsA, wsB] = lbWs.assignments
  R.info(`WS 分发记录 = [${lbWs.assignments.join(', ')}]  →  A 落在 :${wsA}，B 落在 :${wsB}`)

  // ★ 前提断言：必须真的分到了两个不同实例
  const split = lbWs.assignments.length >= 2 && wsA !== wsB
  R.ok('LB 分流：A 与 B 落在**不同**实例', split, `分发 = [${lbWs.assignments.join(', ')}]`)
  if (!split) {
    R.summary('（未实现跨实例分流，后续断言无意义）')
    exitWith(1)
    return
  }

  hb = setInterval(() => {
    try { cA.send({ messageType: -4 }); cB.send({ messageType: -4 }) } catch { /* 连接已断 */ }
  }, 8000)

  const callId = `lb-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`

  R.head('[1] CALL_INVITE(-10) A→B（经 LB，A/B 不同实例）')
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
  R.summary(`WS 分发 = [${lbWs.assignments.join(', ')}]`)
  exitWith(R.fail === 0 ? 0 : 1)
}

main().catch((e) => { console.error(e); exitWith(1) })
