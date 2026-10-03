#!/usr/bin/env node
/**
 * verify_audit_and_at_all.mjs —— 审计 IP 补齐 + @所有人 服务端鉴权 契约验证
 *
 * 背景（openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth）：
 *   两处「表/字段早就备好、行为却从未落地」的缺口：
 *   ① `operation_log.ip_address` 列存在、Service 接口带 ipAddress 参数，但全仓 6 处调用点
 *      无一例外传 null → LOGIN_FAILED / FORCE_OFFLINE / UPDATE_PASSWORD 这些**最需要 IP
 *      溯源**的事件全部没有 IP，审计日志存在却无法用于审计；
 *   ② `@所有人` 权限仅在客户端生效，Java 侧 `atAll` 零命中 → 普通成员手工构造
 *      `extraData={"atAll":true}` 即可冒用群主/管理员身份。
 *
 * 两者都不抛异常、不打警告，属「静默失效」，常规冒烟与 Service 层单测都照不到，故固化为机控断言。
 *
 * 用法：node scripts/verify/verify_audit_and_at_all.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const read = (p) => readFileSync(resolve(ROOT, p), 'utf8')
const has = (p) => existsSync(resolve(ROOT, p))

const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

/**
 * 剥离注释与字符串字面量后再做结构分析。
 * 与 verify_password_session.mjs 同款：实参类判定必须剥，否则模板串里的 "code={}" 会被误判。
 * 字面量内容类判定请用 methodBody() 的原始返回。
 */
function stripCommentsAndStrings (src) {
  let out = ''
  let i = 0
  const n = src.length
  while (i < n) {
    const c = src[i]
    const d = src[i + 1]
    if (c === '/' && d === '/') { while (i < n && src[i] !== '\n') i++; continue }
    if (c === '/' && d === '*') { i += 2; while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++; i += 2; continue }
    if (c === '"' || c === "'") { const q = c; i++; while (i < n && !(src[i] === q && src[i - 1] !== '\\')) i++; i++; out += q + q; continue }
    out += c
    i++
  }
  return out
}

/** 抽取 Java 方法体（跳过字符串与注释，保证大括号配对正确），保留原文 */
function methodBody (rawSrc, signaturePattern) {
  const m = rawSrc.match(signaturePattern)
  if (!m) return null
  let i = rawSrc.indexOf('{', m.index + m[0].length)
  if (i < 0) return null
  let depth = 0
  const n = rawSrc.length
  while (i < n) {
    const c = rawSrc[i]
    const d = rawSrc[i + 1]
    if (c === '/' && d === '/') { while (i < n && rawSrc[i] !== '\n') i++; continue }
    if (c === '/' && d === '*') { i += 2; while (i < n && !(rawSrc[i] === '*' && rawSrc[i + 1] === '/')) i++; i += 2; continue }
    if (c === '"' || c === "'") { const q = c; i++; while (i < n && !(rawSrc[i] === q && rawSrc[i - 1] !== '\\')) i++; i++; continue }
    if (c === '{') depth++
    else if (c === '}') { depth--; if (depth === 0) return rawSrc.slice(m.index, i + 1) }
    i++
  }
  return null
}

/**
 * 取 `name(...)` 的实参原文，做括号配平。
 *
 * ⚠ 不能用正则 `setIpAddress\s*\(([^()]*)\)`：实参里常含方法调用
 * （如 `cond ? IpTools.getClientIp() : x`），`[^()]*` 匹配不到，会得到空串 → 假失败。
 */
function callArgs (rawSrc, name) {
  if (!rawSrc) return ''
  const m = rawSrc.match(new RegExp(`\\b${name}\\s*\\(`))
  if (!m) return ''
  let i = m.index + m[0].length
  const open = i
  let depth = 0
  const n = rawSrc.length
  while (i < n) {
    const c = rawSrc[i]
    if (c === '(') depth++
    else if (c === ')') {
      if (depth === 0) return rawSrc.slice(open, i)
      depth--
    }
    i++
  }
  return ''
}

const OP_LOG = 'easychat-java/src/main/java/com/easychat/service/impl/OperationLogServiceImpl.java'
const ASPECT = 'easychat-java/src/main/java/com/easychat/aspect/GlobalOperationAspect.java'
const CHAT_MSG = 'easychat-java/src/main/java/com/easychat/service/impl/ChatMessageServiceImpl.java'
const IP_TOOLS = 'easychat-java/src/main/java/com/easychat/utils/IpTools.java'
const EXTRA_TOOLS = 'easychat-java/src/main/java/com/easychat/utils/ExtraDataTools.java'
const AT_ALL_SPEC = 'openspec/specs/at-all/spec.md'

console.log('===== 审计 IP 补齐 + @所有人 鉴权 契约验证 =====\n')

// ── 1. IpTools 存在且实现统一取值规则 ────────────────────────
console.log('=== 1. 客户端 IP 取值规则统一 ===')
check('IpTools 存在', has(IP_TOOLS))
if (has(IP_TOOLS)) {
  const ipRaw = read(IP_TOOLS)
  const ipClean = stripCommentsAndStrings(ipRaw)
  const getClientIpBody = methodBody(ipRaw, /public\s+static\s+String\s+getClientIp\s*\(/)
  check('定位到 IpTools#getClientIp 方法体', getClientIpBody !== null)
  // ⚠ 字面量类断言必须用 ipRaw（原文），不能用 ipClean：
  //   stripCommentsAndStrings 会把字符串内容清空，XFF 头名 / ',' / "-" 全都查不到 → 恒红假失败。
  //   实参类断言才用 ipClean。
  check('IpTools 定义了 X-Forwarded-For 头名', /X-Forwarded-For/.test(ipRaw))
  check('IpTools 读取该请求头', !!getClientIpBody && /getHeader\s*\(\s*HEADER_FORWARDED_FOR\s*\)|getHeader\s*\(\s*"X-Forwarded-For"/.test(getClientIpBody))
  check('IpTools 截取逗号前首段', /indexOf\s*\(\s*['"]?,['"]?\s*\)/.test(ipRaw))
  check('IpTools 回退 getRemoteAddr()', !!getClientIpBody && /getRemoteAddr\s*\(\s*\)/.test(getClientIpBody))
  // 占位值以常量声明 + return 常量 两处出现，方法体里只会看到常量名
  check('IpTools 定义了不可得时的占位值', /String\s+UNKNOWN\s*=\s*"-"/.test(ipRaw))
  check('IpTools 在兜底分支返回占位值', !!getClientIpBody &&
    new RegExp(`return\\s+(UNKNOWN|"-")\\s*;`).test(getClientIpBody) &&
    (getClientIpBody.match(new RegExp(`return\\s+(UNKNOWN|"-")\\s*;`, 'g')) || []).length >= 2,
    'getClientIp 中占位值返回点少于 2 处：XFF 回退链与异常兜底各需一处')
  // 无请求上下文时必须兜住，不能让 RequestContextHolder.getRequestAttributes() 的 null 直接 NPE
  check('IpTools 兜住「无请求上下文」（非 HTTP 线程不 NPE）',
    !!getClientIpBody && /getRequestAttributes\s*\(\s*\)/.test(getClientIpBody) &&
    /catch\s*\(|==\s*null/.test(getClientIpBody))
  check('IpTools 内部有异常兜底（getClientIp 不得向上抛）', /catch\s*\(\s*Throwable|catch\s*\(\s*Exception/.test(ipClean))
}

// ── 2. 限流与审计共用同一 IP 实现 ───────────────────────────
console.log('\n=== 2. 限流与审计共用同一实现 ===')
const aspectRaw = has(ASPECT) ? read(ASPECT) : ''
const rateLimitBody = methodBody(aspectRaw, /private\s+void\s+checkRateLimit\s*\(/)
check('定位到 checkRateLimit 方法体', rateLimitBody !== null)
check('GlobalOperationAspect 不再自带私有 IP 解析（已抽出，避免两套规则）',
  !/private\s+String\s+resolveClientIp\s*\(/.test(aspectRaw),
  '仍存在私有 resolveClientIp —— 说明 IP 取值规则有两份实现')
check('checkRateLimit 委托 IpTools.getClientIp()',
  !!rateLimitBody && /IpTools\.getClientIp\s*\(\s*\)/.test(rateLimitBody))
check('限流键仍含 ip 维度（行为不变）', !!rateLimitBody && /"ip:"/.test(rateLimitBody))
check('限流键前缀仍为 "rate_limit:"（改名会使既有计数失效）', !!rateLimitBody && /"rate_limit:"/.test(rateLimitBody))

// ── 3. recordLog 自动补齐 IP ────────────────────────────────
console.log('\n=== 3. 审计日志自动补齐 IP ===')
const opLogRaw = has(OP_LOG) ? read(OP_LOG) : ''
const opRecordBody = methodBody(opLogRaw, /public\s+void\s+recordLog\s*\(/)
check('定位到 OperationLogServiceImpl#recordLog 方法体', opRecordBody !== null)
check('recordLog 调用 IpTools.getClientIp() 补齐 IP',
  !!opRecordBody && /IpTools\.getClientIp\s*\(\s*\)/.test(opRecordBody))
// 关键：补齐得到的 IP 必须**真正进入 setIpAddress 的实参**，否则修复形同虚设。
//
// ⚠ 不能用「IpTools 出现位置 < setIpAddress 位置」这种下标比较：
//   常见的紧凑写法 `setIpAddress(cond ? IpTools.getClientIp() : x)` 里
//   IpTools 出现在 setIpAddress **之后**，会被误判为 FAIL；
//   而 IpTools 完全缺失时下标为 -1，又会被误判为 PASS（假绿）。
//   故改为解析 setIpAddress 的实参，判断其表达式里是否含补齐调用。
{
  const setArgs = callArgs(opRecordBody, 'setIpAddress')
  const inline = /IpTools\.getClientIp\s*\(\s*\)/.test(setArgs)
  // 或两段式：先赋值给局部变量，再 setIpAddress(该变量)
  const localVarMatch = opRecordBody
    ? opRecordBody.match(/String\s+(\w+)\s*=\s*[^;]*IpTools\.getClientIp\s*\(\s*\)\s*;/) : null
  const localVar = localVarMatch ? localVarMatch[1] : ''
  const viaLocal = !!localVar && new RegExp(`setIpAddress\\s*\\(\\s*${localVar}\\s*\\)`).test(opRecordBody || '')
  check('IP 补齐结果真正进入 setIpAddress 实参（否则等于没补）', inline || viaLocal,
    inline ? '内联三元表达式' : viaLocal ? `两段式经局部变量 ${localVar}` : `setIpAddress 实参为「${setArgs.trim()}」，不含补齐调用`)
}
// 显式传入时不得被覆盖
check('recordLog 保留显式传入 ipAddress 的能力（仅空值才补）',
  !!opRecordBody && /StringTools\.isEmpty\s*\(|ipAddress\s*==\s*null|!StringTools\.isEmpty\s*\(\s*ipAddress/.test(opRecordBody))
check('recordLog 不因 IP 采集失败而中断（仍在 try 内）',
  !!opRecordBody && /catch\s*\(\s*Exception/.test(opRecordBody))

// ── 4. 调用点不得硬编造 IP ─────────────────────────────────
console.log('\n=== 4. recordLog 调用点 ===')
const callSites = []
for (const f of ['UserInfoServiceImpl.java', 'ChatMessageServiceImpl.java']) {
  const p = `easychat-java/src/main/java/com/easychat/service/impl/${f}`
  if (!has(p)) continue
  const src = read(p)
  for (const m of src.matchAll(/recordLog\s*\(([^;]*?)\)\s*;/g)) callSites.push({ f, args: m[1] })
}
check('找到 recordLog 调用点', callSites.length > 0, `${callSites.length} 处`)
// 第 4 个参数必须是 null（由实现补齐）或 IpTools.getClientIp()，
// 绝不允许硬编码字符串 IP —— 那会让审计日志看起来有 IP、实际全是同一个假值
const hardcoded = callSites.filter((c) => {
  const parts = c.args.split(',')
  const ipArg = parts[parts.length - 1] || ''
  return /"[^"]*\d+\.\d+\.\d+\.\d+[^"]*"/.test(ipArg)
})
check('无调用点硬编码 IP 字符串', hardcoded.length === 0,
  hardcoded.map((c) => c.f).join(', '))

// ── 5. @所有人 服务端鉴权 ───────────────────────────────────
console.log('\n=== 5. @所有人 服务端鉴权 ===')
check('ExtraDataTools 存在', has(EXTRA_TOOLS))
if (has(EXTRA_TOOLS)) {
  const edRaw = read(EXTRA_TOOLS)
  const isAtAllBody = methodBody(edRaw, /public\s+static\s+boolean\s+isAtAll\s*\(/)
  check('定位到 ExtraDataTools#isAtAll 方法体', isAtAllBody !== null)
  // fail-safe：解析异常/非法输入一律 false，绝不能因解析失败而打断正常发消息
  check('isAtAll 对解析异常兜底为 false',
    !!isAtAllBody && /catch\s*\(/.test(isAtAllBody) && /return\s+false\s*;/.test(isAtAllBody))
  // 只认**顶层布尔** atAll：getBoolean 对 "1"/1 会宽松转成 true 而误伤普通成员，
  // 故实现要么显式取原始值再判类型，要么用 getBoolean 并另有类型守卫。
  check('isAtAll 只读顶层 atAll 字段（不接受嵌套同名字段）',
    !!isAtAllBody && (/getBoolean\s*\(\s*"atAll"/.test(isAtAllBody) ||
      (/get\s*\(\s*"atAll"\s*\)/.test(isAtAllBody) && /instanceof\s+Boolean/.test(isAtAllBody))))
  check('isAtAll 对空/超长输入返回 false',
    !!isAtAllBody && /isEmpty\s*\(\s*extraData/.test(isAtAllBody) && /length\s*\(\s*\)/.test(isAtAllBody))
}
const chatRaw = has(CHAT_MSG) ? read(CHAT_MSG) : ''
const saveMsgBody = methodBody(chatRaw, /public\s+MessageSendDto\s+saveMessage\s*\(/)
check('定位到 ChatMessageServiceImpl#saveMessage 方法体', saveMsgBody !== null)
check('saveMessage 依据 atAll 标记触发鉴权',
  !!saveMsgBody && /ExtraDataTools\.isAtAll\s*\(/.test(saveMsgBody))
check('saveMessage 调用 checkGroupRole 校验群角色',
  !!saveMsgBody && /checkGroupRole\s*\(/.test(saveMsgBody))
check('checkGroupRole 以 ADMIN 为门槛（群主/管理员方可 @所有人）',
  !!saveMsgBody && /checkGroupRole\s*\([^;]*GroupMemberRoleEnum\.ADMIN/.test(saveMsgBody))
// 顺序：isAll 判断必须先于 checkGroupRole，否则等于对所有群消息都查角色。
// ⚠ 同上：两个下标都必须 >= 0，否则 -1 < 真实下标会造成假绿。
{
  const atIdx = saveMsgBody ? saveMsgBody.indexOf('isAtAll') : -1
  const roleIdx = saveMsgBody ? saveMsgBody.indexOf('checkGroupRole') : -1
  const ordered = atIdx >= 0 && roleIdx >= 0 && atIdx < roleIdx
  check('atAll 判定先于角色校验（否则所有群消息都会被拦）', ordered,
    ordered ? '' : `isAtAll@${atIdx} checkGroupRole@${roleIdx}`)
}
// 机器人自回复路径必须跳过鉴权，否则机器人无法发消息
const robotIdx = saveMsgBody ? saveMsgBody.indexOf('ROBOT_UID') : -1
const atAllIdx = saveMsgBody ? saveMsgBody.indexOf('isAtAll') : -1
check('atAll 鉴权位于 ROBOT_UID 判断之内（机器人自回复不触发）',
  robotIdx >= 0 && atAllIdx > robotIdx,
  robotIdx >= 0 ? `robot@${robotIdx} atAll@${atAllIdx}` : '未找到 ROBOT_UID')

// ── 6. 文档不得再声称「服务端未实现」 ───────────────────────
console.log('\n=== 6. 规格文档一致性 ===')
if (has(AT_ALL_SPEC)) {
  const spec = read(AT_ALL_SPEC)
  // 先剥掉删除线标记再判定：规格里常以「~~旧结论~~ → 新结论」的方式记录作废项，
  // 若不剥，会把「已作废的旧结论」误判成「文档仍在声称」，从而产生恒红的假失败。
  const specLive = spec.replace(/~~[\s\S]*?~~/g, '')
  check('at-all spec 不再声称「服务端权限校验本期未实现」',
    !/服务端权限校验：?\*\*?本期未实现/.test(specLive) && !/权限仅在客户端生效/.test(specLive),
    '若仍存在，说明文档与实现已漂移')
  check('at-all spec 记录了服务端鉴权行为（角色不足 → 2305）', /2305/.test(spec))
  check('at-all spec 记录了「失败路径不影响普通群消息」的不变量', /CODE_2304/.test(spec) || /单聊/.test(spec))
} else {
  check('openspec/specs/at-all/spec.md 存在', false)
}

// ── 7. 门禁接入 CI ──────────────────────────────────────────
console.log('\n=== 7. 门禁接入 CI ===')
const ci = read('.github/workflows/ci.yml')
check('ci.yml 已接入 verify_audit_and_at_all.mjs', /verify_audit_and_at_all\.mjs/.test(ci))

// ── 汇总 ────────────────────────────────────────────────────
const failed = results.filter(([, ok]) => !ok)
console.log(`\n===== 结论：${results.length - failed.length}/${results.length} 通过 =====`)
if (failed.length) {
  console.log('失败项：')
  failed.forEach(([n]) => console.log(`  - ${n}`))
  process.exit(1)
}
process.exit(0)