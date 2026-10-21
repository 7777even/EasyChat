#!/usr/bin/env node
/**
 * Redis 不可用时的通话失败语义 —— 实跑（此前只有静态断言）
 *
 * 模拟方式：`DEBUG SLEEP` **阻塞** Redis N 秒，到期**自动恢复**。
 *   不用「停 Redis 服务」：本机 Redis 是 Windows 服务且会话通常非管理员，
 *   停得起来却起不回来，会把开发机留在残局；DEBUG SLEEP 无需权限且自动恢复。
 *
 * 期望（README §4.7「Redis 不可用 → 发起方收到 WS 错误帧 2803…不再静默」）：
 *   发起方 A 在**超时后**收到 messageType=-12(REJECT) 且 code=2803，
 *   而不是毫无反应，也不是把通话「假装发起成功」。
 *
 * ⚠ 已知（实测两次：28034ms / 28311ms）：Redisson 未显式配 timeout，走默认约 30s，
 *   故 2803 约在**阻塞后 28s** 到达 —— 失败反馈偏慢，已登记为遗留项 #35。
 *   本脚本的等待窗口必须大于该超时，否则会误判为「没收到」。
 *
 * 前置：seed-test-users.sql 已执行；实例 A 已起（HTTP 5050、WS 5051）。
 * 退出码：0 符合预期 / 1 断言失败 / 2 环境前提不成立
 */
import {
  PORTS, URLS, PAIRS, loadWs, login, connectWs, createReporter, redisBlock, redisPing, sleep
} from './lib/smoke-lib.mjs'

const BLOCK_SECONDS = Number(process.env.MI_REDIS_BLOCK || 45)
const WAIT_MS = Number(process.env.MI_REDIS_WAIT_MS || 40000)

const P = PAIRS[0]
const R = createReporter(
  `Redis 不可用时的通话失败语义实跑（阻塞 ${BLOCK_SECONDS}s，到期自动恢复）` +
  `\n实例A HTTP ${PORTS.httpA}/WS ${PORTS.wsA}`
)

let c
let hb
const cleanup = () => {
  if (hb) clearInterval(hb)
  try { c && c.close() } catch { /* ignore */ }
}
const exitWith = (code) => {
  cleanup()
  setTimeout(() => process.exit(code), 150)
}

async function main () {
  const WebSocket = loadWs()

  // ── 前提：Redis 正常 → 登录 → WS 已建立（必须在阻塞前完成）──
  if (!(await redisPing())) { R.fatal('Redis 当前不可 PING，无法开始'); return }
  let token
  try {
    token = await login(URLS.httpA, P.mail[0])
  } catch (e) { R.fatal(e.message); return }
  try {
    c = await connectWs(WebSocket, URLS.wsA, token, 'A@实例A')
  } catch (e) { R.fatal(e.message); return }
  R.info('Redis PING 正常，A 已登录并连上 WS')

  hb = setInterval(() => { try { c.send({ messageType: -4 }) } catch { /* 连接已断 */ } }, 8000)

  const t0 = Date.now()
  await redisBlock(BLOCK_SECONDS)
  await sleep(2000)

  // ★ 前提断言：阻塞必须真的生效，否则「收到了 2803」可能根本没经历故障
  const blocked = !(await redisPing(1200))
  R.ok('前提：Redis 已被真正阻塞（PING 在 1.2s 内无响应）', blocked, blocked ? '' : 'PING 仍立即返回 PONG —— 测试环境无效')
  if (!blocked) { R.summary(); exitWith(2); return }

  // ── 阻塞中发起通话 ─────────────────────────────────────────
  R.head('[1] Redis 阻塞中发起 CALL_INVITE(-10)，期望 A 收到 code=2803 的错误帧')
  const callId = `rd-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  c.send({ messageType: -10, callType: 1, mediaType: 1, callId, toUserId: P.b })
  const err = await c.wait((f) => f.messageType === -12 && f.callId === callId, WAIT_MS)
  const elapsed = Date.now() - t0
  R.ok('Redis 不可用 → 发起方收到错误帧（不静默）', !!err,
    err ? `code=${err.code} message=${err.message}（阻塞后 ${elapsed}ms）` : `${WAIT_MS}ms 内未收到任何错误帧`)
  if (err) {
    R.ok('错误码为 2803（通话服务暂时不可用）', err.code === 2803, `实际 code=${err.code}`)
    R.ok('文案明确告知不可用（用户可感知）', /不可用|稍后/.test(err.message || ''), `message=${err.message}`)
    R.info(`★ 实测失败反馈延迟 ≈ ${Math.round(elapsed / 1000)}s（Redisson 默认超时约 30s）—— 已登记为遗留项 #35`)
  }

  // ── 等自动恢复 ─────────────────────────────────────────────
  R.head('[2] 等待阻塞到期，验证 Redis 与实例自动恢复')
  let recovered = false
  for (let i = 0; i < 40; i++) {
    await sleep(3000)
    if (await redisPing(1500)) { recovered = true; break }
  }
  R.ok('Redis 到期自动恢复（无需人工干预）', recovered, recovered ? `耗时 ${Date.now() - t0}ms` : '超时未恢复')

  await sleep(3000)
  try {
    const r = await fetch(`${URLS.httpA}/account/checkCode`)
    const j = await r.json()
    R.ok('实例 A 在 Redis 恢复后仍可用', j && j.code === 0, `code=${j && j.code}`)
  } catch (e) {
    R.ok('实例 A 在 Redis 恢复后仍可用', false, e.message)
  }

  R.summary()
  exitWith(R.fail === 0 ? 0 : 1)
}

main().catch((e) => { console.error(e); exitWith(1) })
