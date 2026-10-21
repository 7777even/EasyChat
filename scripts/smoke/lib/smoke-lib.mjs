/**
 * 跨实例通话冒烟 · 公共库
 *
 * 供 scripts/smoke/ 下的四个冒烟脚本共用：
 *   multi-instance-smoke.mjs        直连双实例的完整通话生命周期
 *   multi-instance-lb.mjs           经 round-robin LB 分流到两实例
 *   multi-instance-redis-down.mjs   Redis 不可用时的失败语义
 *   multi-instance-concurrency.mjs  多路并发下的信令隔离
 *
 * 设计约束（都是踩过的坑，别改回去）：
 *   1. **零外部依赖**：只用 Node 内置 net/fetch + easychat-front 已有的 `ws`。
 *      不引 redis-cli（Windows 路径各异），Redis 访问一律走原生 RESP。
 *   2. **不 spawn 子进程**：`spawnSync/execFileSync` 之后再 `process.exit()` 会在
 *      Windows 上触发 libuv `UV_HANDLE_CLOSING` 断言（实测 exit=-1073740791），
 *      且子进程句柄会让汇总结果不可靠。
 *   3. **端口避开 WHATWG 禁用端口**：5060/5061（SIP）在 fetch 里直接被判
 *      "bad port"（浏览器同样会拒），故默认用 5050/5051 与 5055/5056。
 *   4. **验证码取真值而非绕过**：走产品自己写入/校验的那条路径 —— 从 Redis
 *      读出 `easychat:checkcode:<key>` 的真值再喂回去。
 */
import { createRequire } from 'module'
import { fileURLToPath } from 'url'
import net from 'node:net'
import path from 'node:path'

/** 仓库根（scripts/smoke/lib/ → 上两级） */
export const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url))

const num = (v, d) => (v === undefined || v === '' ? d : Number(v))

/** 端口配置：全部可用环境变量覆盖，便于换机器 */
export const PORTS = {
  httpA: num(process.env.MI_HTTP_A, 5050),   // 实例 A 的 HTTP
  httpB: num(process.env.MI_HTTP_B, 5055),   // 实例 B 的 HTTP
  wsA: num(process.env.MI_WS_A, 5051),       // 实例 A 的 WS
  wsB: num(process.env.MI_WS_B, 5056),       // 实例 B 的 WS
  lbHttp: num(process.env.MI_LB_HTTP, 8088), // LB 的 HTTP 入口
  lbWs: num(process.env.MI_LB_WS, 8099)      // LB 的 WS 入口
}

export const URLS = {
  httpA: `http://127.0.0.1:${PORTS.httpA}/api`,
  httpB: `http://127.0.0.1:${PORTS.httpB}/api`,
  wsA: `ws://127.0.0.1:${PORTS.wsA}/ws`,
  wsB: `ws://127.0.0.1:${PORTS.wsB}/ws`,
  lbHttp: `http://127.0.0.1:${PORTS.lbHttp}/api`,
  lbWs: `ws://127.0.0.1:${PORTS.lbWs}/ws`
}

/** 种子脚本建的四对好友（发起方 a / 被叫方 b） */
export const PAIRS = [
  { a: 'U90000000001', b: 'U90000000002', mail: ['mi-a1@easychat.test', 'mi-b1@easychat.test'] },
  { a: 'U90000000003', b: 'U90000000004', mail: ['mi-a2@easychat.test', 'mi-b2@easychat.test'] },
  { a: 'U90000000005', b: 'U90000000006', mail: ['mi-a3@easychat.test', 'mi-b3@easychat.test'] },
  { a: 'U90000000007', b: 'U90000000008', mail: ['mi-a4@easychat.test', 'mi-b4@easychat.test'] }
]

/** 测试账号明文口令（一次性测试账号，可用 MI_TEST_PASSWORD 覆盖；对应种子 SQL 里的 MD5） */
export const TEST_PASSWORD = process.env.MI_TEST_PASSWORD || 'Test12345'

/** 取 `ws` 客户端库（复用 easychat-front 已装的依赖，不新增生产依赖） */
export function loadWs () {
  const req = createRequire(path.join(REPO_ROOT, 'easychat-front', 'package.json'))
  try {
    return req('ws')
  } catch (e) {
    throw new Error(
      '找不到 ws 客户端库。请先在 easychat-front 执行 `npm install`（本脚本复用其已有依赖，不新增依赖）。\n' +
      `原始错误：${e.message}`
    )
  }
}

// ── Redis（原生 RESP，无外部命令依赖）──────────────────────────
const REDIS_PORT = num(process.env.MI_REDIS_PORT, 6379)

/** GET 一个 key，返回原始 bulk 字符串（null = 不存在） */
export function redisGet (key) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(REDIS_PORT, '127.0.0.1')
    const payload = `*2\r\n$3\r\nGET\r\n$${Buffer.byteLength(key)}\r\n${key}\r\n`
    let buf = ''
    const done = (v) => { try { sock.destroy() } catch {} ; resolve(v) }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('Redis GET 超时')) }, 5000)
    sock.on('connect', () => sock.write(payload))
    sock.on('data', (d) => {
      buf += d.toString()
      if (buf.startsWith('$-1')) { clearTimeout(timer); return done(null) }
      const m = buf.match(/^\$(\d+)\r\n/)
      if (m && buf.length >= m[0].length + Number(m[1]) + 2) {
        clearTimeout(timer); done(buf.slice(m[0].length, m[0].length + Number(m[1])))
      }
    })
    sock.on('error', (e) => { clearTimeout(timer); reject(e) })
  })
}

/**
 * 执行任意一条 Redis 命令，返回**原始首行回复**。
 *
 * 为什么要它而不是只用 GET：`CallRoomStore` 的房间是 Redisson `RMap` → 底层是
 * **HASH** 类型，用 `GET` 会收到 `-WRONGTYPE` 错误回复（只认 bulk string 的客户端会一直等
 * 到超时，误判成「命令没执行」）。判定 key 是否存在要用 `EXISTS`（返回整数 `:1` / `:0`）。
 */
export function redisCmd (...parts) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(REDIS_PORT, '127.0.0.1')
    const payload = `*${parts.length}\r\n` +
      parts.map((p) => `$${Buffer.byteLength(String(p))}\r\n${p}\r\n`).join('')
    let buf = ''
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('Redis 命令超时')) }, 5000)
    sock.on('connect', () => sock.write(payload))
    sock.on('data', (d) => {
      buf += d.toString()
      const nl = buf.indexOf('\r\n')
      if (nl < 0) return
      clearTimeout(timer)
      try { sock.destroy() } catch { /* ignore */ }
      resolve(buf.slice(0, nl))
    })
    sock.on('error', (e) => { clearTimeout(timer); reject(e) })
  })
}

/**
 * 让 Redis 阻塞 N 秒（模拟「不可用」）。
 *
 * ⚠ 用 DEBUG SLEEP 而**不是**停服务：到期**自动恢复**，不需要管理员权限，
 *    也不会把开发机上的 Redis 留在停机状态（实测停 Windows 服务需管理员，
 *    且本会话非管理员 → 停得起来却起不回来）。
 * 阻塞期间 PING 无响应，命令超时即等价于「Redis 不可用」。
 */
export function redisBlock (seconds) {
  return new Promise((resolve) => {
    const sock = net.connect(REDIS_PORT, '127.0.0.1')
    const payload = `*3\r\n$5\r\nDEBUG\r\n$5\r\nSLEEP\r\n$${String(seconds).length}\r\n${seconds}\r\n`
    sock.on('connect', () => {
      sock.write(payload)
      // 不等回复：DEBUG SLEEP 的回复要等阻塞结束才回来
      setTimeout(() => { try { sock.destroy() } catch {} ; resolve() }, 300)
    })
    sock.on('error', () => resolve())
  })
}

/** PING：拿到 PONG 才算 Redis 可用（带超时，用于判定「是否被真的阻塞住」） */
export function redisPing (timeoutMs = 1500) {
  return new Promise((resolve) => {
    const sock = net.connect(REDIS_PORT, '127.0.0.1')
    let got = false
    const timer = setTimeout(() => { try { sock.destroy() } catch {} ; if (!got) resolve(false) }, timeoutMs)
    sock.on('connect', () => sock.write('*1\r\n$4\r\nPING\r\n'))
    sock.on('data', (d) => {
      if (d.toString().startsWith('+PONG')) { got = true; clearTimeout(timer); try { sock.destroy() } catch {} ; resolve(true) }
    })
    sock.on('error', () => { clearTimeout(timer); if (!got) resolve(false) })
  })
}

// ── 账号 / 连接 ────────────────────────────────────────────────
/** 登录取 token（验证码真值从 Redis 读，走产品自己的校验路径） */
export async function login (base, email, password = TEST_PASSWORD) {
  const r0 = await fetch(`${base}/account/checkCode`)
  const cc = await r0.json().catch(() => null)
  if (!cc || cc.code !== 0 || !cc.data) throw new Error(`取验证码失败 ${base}：${JSON.stringify(cc).slice(0, 160)}`)
  const raw = await redisGet(`easychat:checkcode:${cc.data.checkCodeKey}`)
  if (!raw) throw new Error(`Redis 里读不到验证码 ${cc.data.checkCodeKey}`)
  let code
  try { code = JSON.parse(raw) } catch { code = raw.replace(/^"|"$/g, '') }
  const body = new URLSearchParams({
    email, password, checkCodeKey: cc.data.checkCodeKey, checkCode: code
  })
  const r = await fetch(`${base}/account/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body
  })
  const j = await r.json().catch(() => null)
  if (!j || j.code !== 0 || !j.data || !j.data.token) {
    throw new Error(`登录失败 ${email}：code=${j && j.code} message=${j && j.message}`)
  }
  return j.data.token
}

/**
 * 连一个 WS，收帧入数组。
 * 返回 { label, ws, frames, send, wait, close }
 *   wait(pred, ms) —— 命中条件即返回该帧；超时返回 null
 */
export function connectWs (WebSocket, wsBase, token, label) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${wsBase}?token=${token}`)
    const frames = []
    const waiters = []
    const timer = setTimeout(() => reject(new Error(`WS 连接超时 ${label}（${wsBase}）`)), 20000)
    ws.on('open', () => {
      clearTimeout(timer)
      resolve({
        label,
        ws,
        frames,
        send: (o) => ws.send(JSON.stringify(o)),
        wait: (pred, ms = 6000) => new Promise((res) => {
          const hit = frames.find(pred)
          if (hit) return res(hit)
          const w = { pred, res }
          waiters.push(w)
          setTimeout(() => {
            const i = waiters.indexOf(w)
            if (i >= 0) waiters.splice(i, 1)
            res(null)
          }, ms)
        }),
        close: () => { try { ws.close() } catch {} }
      })
    })
    ws.on('message', (raw) => {
      let f = null
      try { f = JSON.parse(raw.toString()) } catch { return }
      frames.push(f)
      for (let i = waiters.length - 1; i >= 0; i--) {
        if (waiters[i].pred(f)) { const w = waiters.splice(i, 1)[0]; w.res(f) }
      }
    })
    ws.on('error', (e) => { clearTimeout(timer); reject(new Error(`WS 错误 ${label}：${e.message}`)) })
  })
}

// ── 结果汇报 ───────────────────────────────────────────────────
/** 统一的 PASS/FAIL 汇报器；汇总行同时给「通过数」与「失败数」 */
export function createReporter (title) {
  let pass = 0
  let fail = 0
  console.log(`\n${'═'.repeat(72)}`)
  console.log(title)
  console.log('═'.repeat(72))
  return {
    ok (label, cond, detail = '') {
      if (cond) { pass++; console.log(`  [PASS] ${label}${detail ? '  ' + detail : ''}`) } else { fail++; console.log(`  [FAIL] ${label}${detail ? '  ' + detail : ''}`) }
      return cond
    },
    /** 环境前提不成立 —— 必须是 exit 2，**不得**记为通过（§2.1 第 3 条） */
    fatal (msg) {
      console.error(`  [环境前提不成立] ${msg}`)
      process.exitCode = 2
      setTimeout(() => process.exit(2), 200)
    },
    info (msg) { console.log(`  [前提] ${msg}`) },
    head (msg) { console.log(`\n${msg}`) },
    summary (extra = '') {
      console.log(`\n${'═'.repeat(72)}`)
      console.log(`结果：${pass} PASS / ${fail} FAIL${extra ? '  ' + extra : ''}`)
      console.log('═'.repeat(72))
      return fail === 0
    },
    get pass () { return pass },
    get fail () { return fail }
  }
}

/** 起一个连接级 round-robin 的 TCP LB（真实 LB 的等价物：不改协议字节） */
export function startRoundRobinLb (listenPort, backends) {
  let idx = 0
  const assignments = []
  const server = net.createServer((client) => {
    const port = backends[idx % backends.length]
    idx++
    assignments.push(port)
    const upstream = net.connect(port, '127.0.0.1')
    upstream.on('error', () => client.destroy())
    client.on('error', () => upstream.destroy())
    client.pipe(upstream)
    upstream.pipe(client)
  })
  server.listen(listenPort, '127.0.0.1')
  return { server, assignments, port: listenPort }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
