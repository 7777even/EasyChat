#!/usr/bin/env node
/**
 * TURN / STUN 配置外置门禁（ADR-003 / ADR-004）
 *
 * 背景（2026-10-13，openspec/changes/2026-10-13-turn-deploy-and-key-mgmt）：
 *   此前 STUN 地址在**前后端各硬编码一份** stun.l.google.com:19302，两处必然漂移，
 *   且依赖境外公共服务。更严重的是：TURN 未配置时降级为纯 STUN，
 *   对称 NAT 下表现为「接通后无声」—— 无报错、无提示、服务端无日志。
 *
 * 断言：
 *   1. 前端源码**不得**再出现硬编码 STUN 地址（含 stun.l.google.com 等常见公共地址）
 *   2. 前端不得再回落到「内置 ICE」—— 缺省必须是空数组
 *   3. 后端 CallService 不得硬编码 STUN，必须经 TurnCredentialCore 取值
 *   4. prod profile 必须同时声明 turn.url 与 turn.stun-url 两个注入点
 *   5. 后端必须存在 TURN 配置启动自检（三类降级各打日志）
 *
 * ⚠ 扫描范围**必须**排除 scripts/ 与测试夹具：
 *   verify_call_core.mjs 自身就含 `stun:stun.l.google.com:19302` 作为 ICE 测试夹具，
 *   若把 scripts/ 扫进去，本门禁会**匹配到自己**并永久误报
 *   （与 AGENTS §2.1 第 7 条同源：那类坑曾让 verify_local_db_core 判自己的注释为违规）。
 *
 * 用法：node scripts/verify/verify_turn_config.mjs
 */
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

/** 收集目录下全部源码文件（递归），扩展名白名单 */
function collect (dir, exts) {
  const out = []
  if (!fs.existsSync(dir)) return out
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === 'dist' || e.name === 'out') continue
      out.push(...collect(p, exts))
    } else if (exts.some((x) => e.name.endsWith(x))) {
      out.push(p)
    }
  }
  return out
}

/**
 * 先剥注释再匹配。
 * 注释里**引用**某个反模式是应该的（那是解释），代码里出现才是问题。
 */
function stripComments (src, lang) {
  if (lang === 'js') {
    return src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
  }
  return src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
}

const FRONT_SRC = path.join(ROOT, 'easychat-front', 'src')
const BACK_SRC = path.join(ROOT, 'easychat-java', 'src', 'main', 'java')
const CALL_SERVICE = path.join(BACK_SRC, 'com', 'easychat', 'websocket', 'CallService.java')

console.log('===== TURN / STUN 配置外置验证 =====\n')

// ── 1 & 2. 前端：不得硬编码 STUN、不得回落内置 ICE ─────────────────
console.log('=== 1. 前端 STUN 去硬编码（ADR-003）===')
const frontFiles = collect(FRONT_SRC, ['.js', '.vue', '.mjs', '.ts'])
// 公共 STUN 地址的判定：stun: 后跟一个非 localhost 的域名/IP。
// ⚠ 不能只匹配 stun.l.google.com —— 换一家公共 STUN 同样跑题，且会漏。
const STUN_RE = /['"`]stun:(?!127\.0\.0\.1|localhost)[a-z0-9.-]+:\d+/i
let hardcoded = []
for (const f of frontFiles) {
  const raw = fs.readFileSync(f, 'utf8')
  const code = stripComments(raw, 'js')
  const m = code.match(STUN_RE)
  if (m) hardcoded.push(`${path.relative(ROOT, f)}: ${m[0]}`)
}
check('前端源码不含硬编码 STUN 地址', hardcoded.length === 0,
  hardcoded.length ? hardcoded.join(' | ') : `已扫 ${frontFiles.length} 个文件`)

const webrtc = fs.readFileSync(path.join(FRONT_SRC, 'renderer', 'src', 'utils', 'WebRTC.js'), 'utf8')
check('WebRTC 不再声明 defaultIce 之类的内置兜底',
  !/defaultIce|DEFAULT_ICE/.test(stripComments(webrtc, 'js')))
check('WebRTC 缺省 iceServers 为空数组（未下发时不连任何外部 STUN）',
  /iceServers:\s*Array\.isArray\(iceServers\)\s*\?\s*iceServers\s*:\s*\[\]/.test(stripComments(webrtc, 'js')),
  '判定边界：只看 buildConfig 的 iceServers 表达式，注释已被剥除')

// ── 3. 后端：CallService 不得硬编码 STUN ──────────────────────────
console.log('\n=== 2. 后端 STUN 去硬编码 ===')
const cs = fs.existsSync(CALL_SERVICE) ? fs.readFileSync(CALL_SERVICE, 'utf8') : ''
const csCode = stripComments(cs, 'java')
check('CallService.java 存在', cs.length > 0, CALL_SERVICE)
check('CallService 不硬编码 STUN 地址', !STUN_RE.test(csCode),
  STUN_RE.test(csCode) ? `命中 ${csCode.match(STUN_RE)[0]}` : '')
check('CallService 的 STUN 条目经 TurnCredentialCore.stunUrls 取值',
  /TurnCredentialCore\.stunUrls\(/.test(csCode))
check('CallService 的 TURN 条目经 TurnCredentialCore.turnUrls 取值',
  /TurnCredentialCore\.turnUrls\(/.test(csCode))

// ── 4. 时效凭据（C2）接线 ────────────────────────────────────────
console.log('\n=== 3. 时效凭据接线（ADR-001）===')
check('CallService 签发时效凭据（buildEphemeralUsername + sign）',
  /TurnCredentialCore\.buildEphemeralUsername\(/.test(csCode) &&
  /TurnCredentialCore\.sign\(/.test(csCode))
check('CallService 支持多密钥（parseSecrets）',
  /TurnCredentialCore\.parseSecrets\(/.test(csCode),
  '多密钥是不停机轮换的实现基础（coturn: Multiple shared secrets can be used）')

// ── 5. 降级可观测（C3）──
console.log('\n=== 4. 降级可观测（ADR-004 / C3）===')
check('CallService 存在启动自检 @PostConstruct', /@PostConstruct/.test(csCode))
check('启动自检把降级结果落到日志（logger.warn / logger.info）',
  /logger\.warn\(/.test(csCode) && /logger\.info\(/.test(csCode),
  '三类降级必须留痕：它们的用户表现都是「接通后无声」')

// ── 6. prod 注入点 ───────────────────────────────────────────────
console.log('\n=== 5. prod profile 注入点 ===')
const prodFile = path.join(ROOT, 'easychat-java', 'src', 'main', 'resources', 'application-prod.properties')
const prod = fs.existsSync(prodFile) ? fs.readFileSync(prodFile, 'utf8') : ''
for (const key of ['easychat.turn.url', 'easychat.turn.stun-url',
  'easychat.turn.static-secret', 'easychat.turn.ttl-seconds']) {
  check(`prod 声明 ${key}`, new RegExp(`^${key.replace(/\./g, '\\.')}=`, 'm').test(prod))
}

// ── 6. coturn 部署物 ─────────────────────────────────────────────
console.log('\n=== 6. coturn 部署物 ===')
const TEMPLATE = path.join(ROOT, 'deploy', 'coturn', 'turnserver.conf.template')
const COMPOSE = path.join(ROOT, 'docker-compose.yml')
check('coturn 配置模板存在', fs.existsSync(TEMPLATE), TEMPLATE)
check('docker-compose.yml 存在', fs.existsSync(COMPOSE))

const tpl = fs.existsSync(TEMPLATE) ? stripComments(fs.readFileSync(TEMPLATE, 'utf8'), 'ini') : ''
// 6.1 官方文档要求的 WebRTC 硬要求（缺任一项则部署出来也不工作）
check('模板启用 lt-cred-mech（缺了 WebRTC 中继直接不工作）', /^\s*lt-cred-mech\s*$/m.test(tpl))
check('模板设置 realm（lt-cred-mech 的前提，留空则认证无法完成）', /^\s*realm=\S+/m.test(tpl))
check('模板启用 use-auth-secret', /^\s*use-auth-secret\s*$/m.test(tpl))
check('模板未开启 allow-loopback-peers（官方：生产环境属安全漏洞）',
  /^\s*allow-loopback-peers\s*$/m.test(tpl) === false)
// 6.2 仓库公开 → 模板不得写死任何真实地址
for (const k of ['external-ip', 'cert', 'pkey']) {
  const re = new RegExp('^\\s*' + k + '=(?!\\$\\{)\\S', 'm')
  check(`模板 ${k} 未写死字面量（须经环境变量注入）`, !re.test(tpl),
    re.test(tpl) ? `命中 ${tpl.match(re)[0]}` : '')
}
// 6.3 compose 必须含 coturn，且不得写死地址
const compose = fs.existsSync(COMPOSE) ? fs.readFileSync(COMPOSE, 'utf8') : ''
check('docker-compose.yml 含 coturn service', /^\s{2}coturn:\s*$/m.test(compose))
check('coturn 有 healthcheck（区分「进程在」与「端口可用」）',
  /^\s{2}coturn:[\s\S]*?healthcheck:/m.test(compose))
check('compose 内不得出现公共中继地址', !/turn\.anyfirewall\.com|turn\.openrelay/.test(compose))
check('backend 不 depends_on coturn（中继故障不应阻断后端启动）',
  !/^\s{2}backend:[\s\S]*?depends_on:[\s\S]*?\bcoturn:/m.test(compose))

// ── 7. 防空转自检：正例必须为真、反例必须为假 ─────────────────────
// ⚠ 基线自检只证明「当前仓库不触发」，不证明这条门禁扫得到目标。
//   故正例（构造一段确实含硬编码 STUN 的源码）必须被本门禁的判据命中。
console.log('\n=== 6. 防空转自检（判据必须扫得到）===')
const probeSrc = 'const c = { iceServers: [{ urls: "stun:stun.l.google.com:19302" }] }'
check('正例：判据能命中硬编码 STUN', STUN_RE.test(stripComments(probeSrc, 'js')))
const probeComment = '// 这里曾经写死 stun:stun.l.google.com:19302，现已外置'
check('反例：注释中的引用不判为违规（先剥注释）',
  !STUN_RE.test(stripComments(probeComment, 'js')), '剥注释后应无残留')
const probeLocal = 'const s = "stun:127.0.0.1:3478"'
check('反例：localhost/127.0.0.1 不算公共 STUN（自建调试不算违规）',
  !STUN_RE.test(stripComments(probeLocal, 'js')))
check('反例：门禁脚本自身不在扫描范围内',
  !frontFiles.some((f) => f.includes('scripts')), '本门禁含 stun 夹具，被扫到会永久误报')

// ── 汇总 ────────────────────────────────────────────────────────
const fail = results.filter(([, ok]) => !ok).length
console.log(`\n===== 结论：${results.length - fail}/${results.length} 通过 =====`)
if (fail) {
  console.log(`失败 ${fail} 项`)
  process.exit(1)
}
