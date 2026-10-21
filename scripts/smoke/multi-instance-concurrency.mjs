#!/usr/bin/env node
/**
 * 跨实例并发隔离冒烟 —— 多路通话同时进行时信令**不串房**
 *
 * ⚠ 这是**隔离性**冒烟，**不是**压力/容量测试：
 *   它验证「多路通话并发时，callId 不互相污染、帧不丢不乱序、彼此不串」，
 *   而**不**给出吞吐上限、延迟分布、连接数上限 —— 容量结论仍需专门压测环境。
 *
 * 形态：4 对好友，**每对的发起方连实例 A（5051）、被叫方连实例 B（5056）**
 *       → 4 路通话全部跨实例，且同时进行。
 *
 * 核心断言（隔离性是重点）：
 *   1. 每路被叫方只收到**本路**的 INVITE（1 帧）
 *   2. 任一客户端收到的所有帧都必须属于本路（callId 校验）—— **不串房**
 *   3. ACCEPT → 每路发起方收到本路 JOIN
 *   4. 突发 50 帧 SIGNAL → 被叫方 50 帧**全到**、seq 集合完整、顺序不乱
 *   5. HANGUP → 每路被叫方收到本路 HANGUP
 *
 * 前置：seed-test-users.sql 已执行；两个实例已起（HTTP 5050/5055、WS 5051/5056）。
 * 退出码：0 符合预期 / 1 断言失败 / 2 环境前提不成立
 */
import {
  PORTS, URLS, PAIRS, loadWs, login, connectWs, createReporter, sleep
} from './lib/smoke-lib.mjs'

const BURST = Number(process.env.MI_BURST || 50)
const R = createReporter(
  `跨实例并发隔离冒烟（${PAIRS.length} 路同时进行，突发 ${BURST} 帧/路）` +
  `\n发起方连实例A ${PORTS.wsA}，被叫方连实例B ${PORTS.wsB}`
)

const clients = []
let hb
const cleanup = () => {
  if (hb) clearInterval(hb)
  clients.forEach((c) => { try { c.close() } catch { /* ignore */ } })
}
const exitWith = (code) => {
  cleanup()
  setTimeout(() => process.exit(code), 150)
}
const framesOf = (c, type) => c.frames.filter((f) => f.messageType === type)

async function main () {
  const WebSocket = loadWs()

  // ── 登录 + 连接 ────────────────────────────────────────────
  for (const p of PAIRS) {
    try {
      p.tokenA = await login(URLS.httpA, p.mail[0])
      p.tokenB = await login(URLS.httpB, p.mail[1])
      p.callId = `cc-${p.a.slice(-2)}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`
    } catch (e) { R.fatal(e.message); return }
  }
  R.info(`${PAIRS.length} 对账号登录完成`)

  for (const p of PAIRS) {
    try {
      p.ca = await connectWs(WebSocket, URLS.wsA, p.tokenA, `A:${p.a}`)
      p.cb = await connectWs(WebSocket, URLS.wsB, p.tokenB, `B:${p.b}`)
    } catch (e) { R.fatal(e.message); return }
    clients.push(p.ca, p.cb)
  }
  R.info(`${clients.length} 个 WS 连接已建立（发起方在实例 A / 被叫方在实例 B）`)

  hb = setInterval(() => {
    clients.forEach((c) => { try { c.send({ messageType: -4 }) } catch { /* 连接已断 */ } })
  }, 8000)

  // ── 1. 4 路 INVITE 同时 ────────────────────────────────────
  R.head('[1] 4 路 CALL_INVITE(-10) 同时发出')
  PAIRS.forEach((p) => p.ca.send({ messageType: -10, callType: 1, mediaType: 1, callId: p.callId, toUserId: p.b }))
  await sleep(2500)

  for (const p of PAIRS) {
    const inv = framesOf(p.cb, -10)
    R.ok(`路 ${p.a.slice(-2)}：被叫方恰好收到 1 帧 INVITE`, inv.length === 1, `实收 ${inv.length} 帧`)
    R.ok(`路 ${p.a.slice(-2)}：INVITE 的 callId 属本路`, inv.length === 1 && inv[0].callId === p.callId,
      inv.length ? `callId=${inv[0].callId}` : '无帧')
  }

  // ★ 隔离性核心断言：每个客户端收到的所有帧都必须属于本路
  for (const p of PAIRS) {
    const foreign = [...p.ca.frames, ...p.cb.frames].filter((f) => f.callId && f.callId !== p.callId)
    R.ok(`路 ${p.a.slice(-2)}：无其他路的帧混入（不串房）`, foreign.length === 0,
      foreign.length ? `混入 ${foreign.length} 帧：${[...new Set(foreign.map((f) => f.callId))].join(',')}` : '')
  }

  // ── 2. 4 路 ACCEPT 同时 ────────────────────────────────────
  R.head('[2] 4 路 CALL_ACCEPT(-11) 同时发出')
  PAIRS.forEach((p) => p.cb.send({ messageType: -11, callId: p.callId }))
  await sleep(2500)
  for (const p of PAIRS) {
    const j = framesOf(p.ca, -17)
    R.ok(`路 ${p.a.slice(-2)}：发起方收到本路 JOIN(-17)`, j.length === 1 && j[0].callId === p.callId, `实收 ${j.length} 帧`)
  }

  // ── 3. 突发 SIGNAL ─────────────────────────────────────────
  R.head(`[3] 每路突发 ${BURST} 帧 CALL_SIGNAL(-13)`)
  PAIRS.forEach((p) => {
    for (let i = 0; i < BURST; i++) {
      p.ca.send({ messageType: -13, callId: p.callId, toUserId: p.b, signalType: 'offer', sdp: `seq=${i}` })
    }
  })
  await sleep(4000)
  for (const p of PAIRS) {
    const sig = framesOf(p.cb, -13)
    const seqs = sig.map((f) => (f.sdp || '').replace('seq=', ''))
    const uniq = new Set(seqs)
    R.ok(`路 ${p.a.slice(-2)}：被叫方收到全部 ${BURST} 帧（不丢）`, sig.length === BURST, `实收 ${sig.length}`)
    R.ok(`路 ${p.a.slice(-2)}：seq 集合完整且无重复`, uniq.size === BURST, `唯一 seq ${uniq.size}/${BURST}`)
    R.ok(`路 ${p.a.slice(-2)}：帧序与发送序一致（不乱序）`, seqs.every((s, i) => Number(s) === i),
      seqs.every((s, i) => Number(s) === i) ? '' : `实际前 10 个 seq = ${seqs.slice(0, 10).join(',')}`)
  }

  // ── 4. 4 路 HANGUP ─────────────────────────────────────────
  R.head('[4] 4 路 CALL_HANGUP(-14) 同时发出')
  PAIRS.forEach((p) => p.ca.send({ messageType: -14, callId: p.callId }))
  await sleep(3000)
  for (const p of PAIRS) {
    const h = framesOf(p.cb, -14)
    R.ok(`路 ${p.a.slice(-2)}：被叫方收到本路 HANGUP`, h.length === 1 && h[0].callId === p.callId, `实收 ${h.length} 帧`)
  }

  R.summary()
  exitWith(R.fail === 0 ? 0 : 1)
}

main().catch((e) => { console.error(e); exitWith(1) })
