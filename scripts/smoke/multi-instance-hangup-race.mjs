#!/usr/bin/env node
/**
 * 跨实例「双方同时挂断」竞态冒烟 —— 守住「一通电话只记一条 call_log」。
 *
 * <p><b>它守的是什么</b>：{@code endCall} 结束通话时必须**原子**地抢占结束权，
 * 否则双方同时挂断（分属两实例）会**各写一条 call_log** → 同一通话被记两遍。
 *
 * <p><b>缺陷与修复（2026-10-21）</b>：
 * <ul>
 *   <li>旧实现：{@code evictRoom} 是「先 get 探测存在、再 delete」——<b>两步非原子</b>；
 *       实测 15 轮「同时挂断」落库 <b>16 行</b>（重复率 ~7%，重复行 start_time 完全相同）。</li>
 *   <li>现实现：{@code RMap#remove(key, value)}（Redisson 里是<b>单条 EVAL</b>，原子）
 *       把「房间是否仍属于该通话」与「抢占结束权」合并为一步 → 并发只有一方拿到 true。</li>
 * </ul>
 *
 * <p><b>为什么必须端到端</b>：竞态窗口在两次 Redis 往返之间（~1ms），
 * 单元测试（mock）只能锁住「机制必须是原子 compare-and-delete」，
 * **证明不了真并发下只有一条落库** —— 只有真打、真落库、真数行才算数。
 *
 * <p>前置：seed-test-users.sql 已执行；两个实例已起；本机有 mysql CLI。
 * 退出码：0 一通一条（无重复）/ 1 发现重复 / 2 环境前提不成立
 */
import { execFileSync } from 'node:child_process'
import {
  PORTS, PAIRS, loadWs, login, connectWs, createReporter, sleep
} from './lib/smoke-lib.mjs'

const ROUNDS = Number(process.env.MI_RACE_ROUNDS || 20)
const DB = process.env.MI_DB || 'easychat'
const DB_USER = process.env.MI_DB_USER || 'root'
const DB_PASS = process.env.MI_DB_PASS || 'root'
const DB_HOST = process.env.MI_DB_HOST || '127.0.0.1'
const DB_PORT = process.env.MI_DB_PORT || '3306'

const P = PAIRS[0]   // mi-a1 / mi-b1
const R = createReporter(
  `跨实例「双方同时挂断」竞态冒烟（${ROUNDS} 轮，期望每通电话恰好 1 条 call_log）` +
  `\nA@实例A ${PORTS.wsA}   B@实例B ${PORTS.wsB}`
)

/** 找 mysql CLI（沿用 verify_schema_drift.mjs 的做法：零新依赖） */
function mysqlBin () {
  const candidates = [
    process.env.MI_MYSQL_BIN,
    'C:\\Program Files\\MySQL\\MySQL Server 5.7\\bin\\mysql.exe',
    'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
    '/usr/bin/mysql',
    '/usr/local/bin/mysql'
  ].filter(Boolean)
  for (const c of candidates) {
    try {
      execFileSync(c, ['--version'], { stdio: 'pipe' })
      return c
    } catch { /* 试下一个 */ }
  }
  return null
}

function sql (bin, statement) {
  const out = execFileSync(
    bin,
    [`--host=${DB_HOST}`, `--port=${DB_PORT}`, `--user=${DB_USER}`,
      `--password=${DB_PASS}`, '--default-character-set=utf8mb4',
      '--batch', '--skip-column-names', DB, `--execute=${statement}`],
    { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }
  )
  return out.split(/\r?\n/).map((s) => s.trim()).filter(Boolean)
}

async function main () {
  const bin = mysqlBin()
  if (!bin) { R.fatal('本机找不到 mysql CLI —— 无法核对 call_log 行数（可用 MI_MYSQL_BIN 指定）'); return }

  const WebSocket = loadWs()
  let tokA
  let tokB
  try {
    tokA = await login(`http://127.0.0.1:${PORTS.httpA}/api`, P.mail[0])
    tokB = await login(`http://127.0.0.1:${PORTS.httpB}/api`, P.mail[1])
  } catch (e) { R.fatal(e.message); return }

  const cA = await connectWs(WebSocket, `ws://127.0.0.1:${PORTS.wsA}/ws`, tokA, 'A')
  const cB = await connectWs(WebSocket, `ws://127.0.0.1:${PORTS.wsB}/ws`, tokB, 'B')
  R.info(`A、B 已连接（分属两实例）；先清空测试账号的历史 call_log`)
  sql(bin, `DELETE FROM call_log WHERE caller_id='${P.a}' OR peer_id='${P.a}';`)

  const hb = setInterval(() => {
    try { cA.send({ messageType: -4 }); cB.send({ messageType: -4 }) } catch { /* 连接已断 */ }
  }, 8000)

  for (let i = 0; i < ROUNDS; i++) {
    const callId = `race-${Date.now().toString(36)}-${i}`
    cA.send({ messageType: -10, callType: 1, mediaType: 1, callId, toUserId: P.b })
    await sleep(300)
    cB.send({ messageType: -11, callId })
    await sleep(400)
    // ★ 同一 tick 内双方同时挂断 —— 竞态窗口就在这里
    cA.send({ messageType: -14, callId })
    cB.send({ messageType: -14, callId })
    await sleep(700)
    process.stdout.write('.')
  }
  console.log('')
  clearInterval(hb)
  cA.close()
  cB.close()

  const rows = Number(sql(bin, `SELECT COUNT(*) FROM call_log WHERE caller_id='${P.a}';`)[0] || 0)
  const distinct = Number(sql(bin, `SELECT COUNT(DISTINCT start_time) FROM call_log WHERE caller_id='${P.a}';`)[0] || 0)
  const dupes = sql(bin,
    `SELECT start_time, COUNT(*) FROM call_log WHERE caller_id='${P.a}' GROUP BY start_time HAVING COUNT(*)>1;`)

  R.info(`call_log 行数 = ${rows}，不同通话数（distinct start_time）= ${distinct}，期望都是 ${ROUNDS}`)
  R.ok('每通电话恰好一条 call_log（无重复落库）', rows === ROUNDS && distinct === ROUNDS,
    dupes.length ? `重复明细：${dupes.join(' / ')}` : '')
  R.ok('不存在同一通话的多行', dupes.length === 0, dupes.length ? `${dupes.length} 组重复` : '')

  R.summary()
  process.exitCode = R.fail === 0 ? 0 : 1
}

main().catch((e) => { console.error(e); process.exitCode = 1 })
