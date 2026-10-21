#!/usr/bin/env node
/**
 * 跨实例通话冒烟 —— 直连双实例（call-multi-instance 交付后补验）
 *
 * 形态：两个后端实例共用同一 MySQL + Redis
 *        客户端 A 连实例 A（默认 5050/5051），客户端 B 连实例 B（默认 5055/5056）
 *        → 即「A、B 被打到不同实例」
 *
 * 判据（每一步都要求**跨实例**送达）：
 *   1. A 的 CALL_INVITE(-10)       → B 收到
 *   2. B 的 CALL_ACCEPT(-11)       → A 收到 CALL_JOIN(-17)
 *   3. A 的 CALL_SIGNAL(-13) offer → B 收到
 *   4. B 的 CALL_SIGNAL(-13) answer→ A 收到
 *   5. A 的 CALL_HANGUP(-14)       → B 收到
 *
 * 反例对照（`--control`）：要求实例 A 以 `--easychat.call.broadcast-signaling=false` 启动，
 *   此时第 1 步**必须送不到**，且房间**必须已建在 Redis 里**（证明服务端确实处理了这次呼叫，
 *   被抑制的只是**投递**，不是「根本没收到」）。
 *   —— 这一对（正例通过 + 反例通过）才说明「跨实例送达」这条断言**有判别力**。
 *
 * 前置：先执行 seed-test-users.sql 建好 4 对测试账号，并起好两个实例。
 * 退出码：0 符合预期 / 1 断言失败 / 2 环境前提不成立
 */
import {
  PORTS, URLS, PAIRS, loadWs, login, connectWs, createReporter, redisCmd, sleep
} from './lib/smoke-lib.mjs'

const CONTROL = process.argv.includes('--control')
const EXPECT_DELIVERY = !CONTROL
const P = PAIRS[0]

const R = createReporter(
  `跨实例通话冒烟 —— ${CONTROL ? '反例对照（实例 A 广播已关闭，期望第 1 步送不到）' : '正例（默认 broadcast-signaling=true）'}` +
  `\n实例A HTTP ${PORTS.httpA}/WS ${PORTS.wsA}   实例B HTTP ${PORTS.httpB}/WS ${PORTS.wsB}`
)

let cA
let cB
let hb
const cleanup = () => {
  if (hb) clearInterval(hb)
  try { cA && cA.close() } catch { /* ignore */ }
  try { cB && cB.close() } catch { /* ignore */ }
}
const exitWith = (code) => {
  cleanup()
  setTimeout(() => process.exit(code), 150)
}

async function main () {
  const WebSocket = loadWs()

  // ── 前提：登录 + 连接 ───────────────────────────────────────
  let tokA
  let tokB
  try {
    tokA = await login(URLS.httpA, P.mail[0])
    tokB = await login(URLS.httpB, P.mail[1])
  } catch (e) { R.fatal(e.message); return }
  R.info(`A 登录成功（${URLS.httpA}）  B 登录成功（${URLS.httpB}）`)

  try {
    cA = await connectWs(WebSocket, URLS.wsA, tokA, `A@实例A:${PORTS.wsA}`)
    cB = await connectWs(WebSocket, URLS.wsB, tokB, `B@实例B:${PORTS.wsB}`)
  } catch (e) { R.fatal(e.message); return }
  R.info(`${cA.label} 已连接   ${cB.label} 已连接`)

  hb = setInterval(() => {
    try { cA.send({ messageType: -4 }); cB.send({ messageType: -4 }) } catch { /* 连接已断 */ }
  }, 8000)

  const callId = `mi-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`

  // ── 1. INVITE ──────────────────────────────────────────────
  R.head('[1] CALL_INVITE(-10) A→实例A，期望 B@实例B 收到')
  cA.send({ messageType: -10, callType: 1, mediaType: 1, callId, toUserId: P.b })
  const invite = await cB.wait((f) => f.messageType === -10 && f.callId === callId, 8000)

  if (EXPECT_DELIVERY) {
    R.ok('跨实例：INVITE 送达 B', !!invite, invite ? `fromUserId=${invite.fromUserId}` : 'B 未收到任何 INVITE 帧')
    if (!invite) { R.summary(); exitWith(1); return }
  } else {
    R.ok('反例：广播关闭时 INVITE **不得**送达 B', !invite, invite ? '竟然送到了 —— 说明环境没按要求关广播' : 'B 确实收不到')
    // 房间是 Redisson RMap（HASH 类型）—— 用 EXISTS 判存在，不要用 GET（会 WRONGTYPE）
    const exists = await redisCmd('EXISTS', `call:room:${callId}`)
    R.ok('反例：房间**已建在 Redis**（证明服务端处理了呼叫，被抑制的只是投递）', exists === ':1',
      `EXISTS call:room:${callId} → ${exists}`)
    R.summary()
    exitWith(R.fail === 0 ? 0 : 1)
    return
  }

  // ── 2. ACCEPT ──────────────────────────────────────────────
  R.head('[2] CALL_ACCEPT(-11) B→实例B，期望 A 收到 CALL_JOIN(-17)')
  cB.send({ messageType: -11, callId })
  const join = await cA.wait((f) => f.messageType === -17 && f.callId === callId, 8000)
  R.ok('跨实例：ACCEPT 触发的 JOIN 送达 A', !!join, join ? `members=${JSON.stringify(join.members)}` : 'A 未收到 JOIN')

  // ── 3/4. SIGNAL 双向中继 ───────────────────────────────────
  R.head('[3] CALL_SIGNAL(-13) offer A→B')
  cA.send({ messageType: -13, callId, toUserId: P.b, signalType: 'offer', sdp: 'v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n' })
  const s1 = await cB.wait((f) => f.messageType === -13 && f.callId === callId && f.signalType === 'offer', 8000)
  R.ok('跨实例：offer 送达 B', !!s1, s1 ? `fromUserId=${s1.fromUserId}` : 'B 未收到 offer')

  R.head('[4] CALL_SIGNAL(-13) answer B→A')
  cB.send({ messageType: -13, callId, toUserId: P.a, signalType: 'answer', sdp: 'v=0\r\no=- 2 1 IN IP4 127.0.0.1\r\n' })
  const s2 = await cA.wait((f) => f.messageType === -13 && f.callId === callId && f.signalType === 'answer', 8000)
  R.ok('跨实例：answer 送达 A', !!s2, s2 ? `fromUserId=${s2.fromUserId}` : 'A 未收到 answer')

  // ── 5. HANGUP ──────────────────────────────────────────────
  R.head('[5] CALL_HANGUP(-14) A→实例A，期望 B 收到')
  cA.send({ messageType: -14, callId })
  const hang = await cB.wait((f) => f.messageType === -14 && f.callId === callId, 8000)
  R.ok('跨实例：HANGUP 送达 B', !!hang, hang ? `fromUserId=${hang.fromUserId}` : 'B 未收到 HANGUP')

  await sleep(2000)
  console.log(`\n  callId=${callId}  （核对 call_log：SELECT * FROM call_log ORDER BY id DESC LIMIT 1）`)
  R.summary()
  exitWith(R.fail === 0 ? 0 : 1)
}

main().catch((e) => { console.error(e); exitWith(1) })
