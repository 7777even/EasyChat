#!/usr/bin/env node
/**
 * 跨实例**负载 / 吞吐**冒烟 —— 为「容量/吞吐上限」这一未覆盖项提供**可执行**的测量
 *
 * <p><b>它测什么</b>（全部为**跨实例**路径：发起方在实例 A、被叫方在实例 B）：
 * <ol>
 *   <li>N 路通话**同时**发起（INVITE）→ 全部送达的耗时；</li>
 *   <li>N 路**同时**接听（ACCEPT）→ 全部收到 JOIN 的耗时；</li>
 *   <li>每路突发 M 帧信令 → 送达帧数、**吞吐（帧/秒）**、端到端延迟；</li>
 *   <li>N 路**同时**挂断 → 全部送达；</li>
 *   <li>隔离性：任何一路都**不得**收到别路的帧（不串房）。</li>
 * </ol>
 *
 * <p>⚠ <b>数字怎么用（重要）</b>：本脚本输出的是**当前这台机器/这套部署**的实测值。
 * 若 DB / Redis 与后端**同机**（开发机常见），三者争抢同一份 CPU 与内存，
 * 测出的吞吐**不可迁移**到生产（生产通常把 DB/Redis 独立部署）。
 * 因此本脚本的价值是：**在目标部署环境跑一次**，拿到**那套环境**的数字，
 * 而不是在本机测一个看起来像结论、实则误导的数。
 *
 * <p>用法：
 * <pre>
 *   mysql -uroot -proot -D easychat &lt; scripts/smoke/seed-test-users.sql
 *   node scripts/smoke/multi-instance-load.mjs
 *   MI_LOAD_PAIRS=8 MI_LOAD_FRAMES=500 node scripts/smoke/multi-instance-load.mjs
 * </pre>
 *
 * <p>退出码：0 全部符合预期 / 1 有断言失败 / 2 环境前提不成立
 */
import {
  PORTS, PAIRS, loadWs, login, connectWs, createReporter, sleep
} from './lib/smoke-lib.mjs'

const PAIRS_N = Math.min(Number(process.env.MI_LOAD_PAIRS || PAIRS.length), PAIRS.length)
const FRAMES = Number(process.env.MI_LOAD_FRAMES || 200)

const R = createReporter(
  `跨实例负载 / 吞吐冒烟（${PAIRS_N} 路并发 × 每路 ${FRAMES} 帧突发）` +
  `\n发起方连实例A ${PORTS.wsA}，被叫方连实例B ${PORTS.wsB}` +
  `\n⚠ 数字只对**当前这套部署**有效（同机 DB/Redis 时不可迁移到生产）`
)

const sleepMs = (ms) => new Promise((r) => setTimeout(r, ms))

/** 等待条件成立，返回耗时（ms）；超时返回 -1 */
async function waitUntil (fn, timeoutMs = 20000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    if (fn()) return Date.now() - t0
    await sleepMs(20)
  }
  return -1
}

async function main () {
  const WebSocket = loadWs()
  const used = PAIRS.slice(0, PAIRS_N)

  // ── 登录 + 连接 ────────────────────────────────────────────
  for (const p of used) {
    try {
      p.tokenA = await login(`http://127.0.0.1:${PORTS.httpA}/api`, p.mail[0])
      p.tokenB = await login(`http://127.0.0.1:${PORTS.httpB}/api`, p.mail[1])
    } catch (e) { R.fatal(e.message); return }
  }
  R.info(`${used.length} 对账号登录完成`)
  for (const p of used) {
    try {
      p.ca = await connectWs(WebSocket, `ws://127.0.0.1:${PORTS.wsA}/ws`, p.tokenA, `A:${p.a}`)
      p.cb = await connectWs(WebSocket, `ws://127.0.0.1:${PORTS.wsB}/ws`, p.tokenB, `B:${p.b}`)
    } catch (e) { R.fatal(e.message); return }
  }
  R.info(`${used.length * 2} 个 WS 连接已建立（全部跨实例）`)

  const hb = setInterval(() => {
    for (const p of used) {
      try { p.ca.send({ messageType: -4 }); p.cb.send({ messageType: -4 }) } catch { /* 已断 */ }
    }
  }, 8000)

  // ── 1. 全部同时发起 ────────────────────────────────────────
  R.head(`[1] ${used.length} 路 CALL_INVITE(-10) 同时发出`)
  used.forEach((p) => { p.callId = `load-${p.a}-${Date.now().toString(36)}` })
  const tInvite0 = Date.now()
  used.forEach((p) => p.ca.send({ messageType: -10, callType: 1, mediaType: 1, callId: p.callId, toUserId: p.b }))
  const tInvite = await waitUntil(() => used.every((p) => p.cb.frames.some((f) => f.messageType === -10 && f.callId === p.callId)))
  R.ok('全部 INVITE 送达', tInvite >= 0, tInvite >= 0 ? `耗时 ${tInvite}ms` : '超时未送达')

  // ── 2. 全部同时接听 ────────────────────────────────────────
  R.head(`[2] ${used.length} 路 CALL_ACCEPT(-11) 同时发出`)
  const tAccept0 = Date.now()
  used.forEach((p) => p.cb.send({ messageType: -11, callId: p.callId }))
  const tAccept = await waitUntil(() => used.every((p) => p.ca.frames.some((f) => f.messageType === -17 && f.callId === p.callId)))
  R.ok('全部 JOIN 送达', tAccept >= 0, tAccept >= 0 ? `耗时 ${tAccept}ms` : '超时未送达')

  // ── 3. 突发信令 + 吞吐测量 ─────────────────────────────────
  R.head(`[3] 每路突发 ${FRAMES} 帧 CALL_SIGNAL(-13)`)
  const tBurst0 = Date.now()
  for (const p of used) {
    for (let i = 0; i < FRAMES; i++) {
      p.ca.send({ messageType: -13, callId: p.callId, toUserId: p.b, signalType: 'offer', sdp: `seq=${i}` })
    }
  }
  const total = used.length * FRAMES
  const tBurst = await waitUntil(() => {
    for (const p of used) {
      const n = p.cb.frames.filter((f) => f.messageType === -13 && f.callId === p.callId).length
      if (n < FRAMES) return false
    }
    return true
  }, 60000)
  const elapsed = Date.now() - tBurst0
  R.ok('全部突发信令送达（不丢帧）', tBurst >= 0,
    tBurst >= 0 ? `${total} 帧用时 ${elapsed}ms` : '超时（有帧未送达）')
  if (tBurst >= 0) {
    const fps = Math.round(total / (elapsed / 1000))
    R.info(`★ 跨实例信令吞吐 ≈ ${fps} 帧/秒（${total} 帧 / ${elapsed}ms）`)
    R.info(`   INVITE 阶段 ${tInvite}ms，ACCEPT 阶段 ${tAccept}ms（${used.length} 路并发）`)
  }

  // ── 4. 隔离性：不得串房 ────────────────────────────────────
  R.head('[4] 隔离性检查')
  for (const p of used) {
    const foreign = [...p.ca.frames, ...p.cb.frames].filter((f) => f.callId && f.callId !== p.callId)
    R.ok(`路 ${p.a.slice(-2)}：无别路帧混入（不串房）`, foreign.length === 0,
      foreign.length ? `混入 ${foreign.length} 帧` : '')
  }

  // ── 5. 全部同时挂断 ────────────────────────────────────────
  R.head(`[5] ${used.length} 路 CALL_HANGUP(-14) 同时发出`)
  used.forEach((p) => p.ca.send({ messageType: -14, callId: p.callId }))
  const tHang = await waitUntil(() => used.every((p) => p.cb.frames.some((f) => f.messageType === -14 && f.callId === p.callId)))
  R.ok('全部 HANGUP 送达', tHang >= 0, tHang >= 0 ? `耗时 ${tHang}ms` : '超时未送达')

  clearInterval(hb)
  for (const p of used) { try { p.ca.close(); p.cb.close() } catch { /* ignore */ } }
  R.summary()
  process.exitCode = R.fail === 0 ? 0 : 1
}

main().catch((e) => { console.error(e); process.exitCode = 1 })
