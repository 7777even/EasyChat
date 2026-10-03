#!/usr/bin/env node
/**
 * 密码会话失效 + 验证码投递契约验证（回归守卫）
 *
 * 背景：2026-10-03 盘点发现三处认证面缺陷构成可利用的账号接管链，且全部无自动化覆盖：
 *   1. 改密 / 找回密码成功后只写库，既不清 Redis Token 也不推 FORCE_OFF_LINE，
 *      而 RedisComponet#cleanUserTokenByUserId 已实现却全仓零调用
 *      → 账号被盗后受害者改密码，攻击者凭旧 Token（TTL 2 天）照常在线；
 *   2. sendEmailCode 把 6 位验证码以明文写进应用日志
 *      → 日志读权限（容器 stdout / 日志聚合 / CI 归档）= 任意账号改密权限，含 admin；
 *   3. GlobalOperationAspect#checkRateLimit 在 token == null 时直接 return，
 *      而登录页 localStorage 无 token → axios 丢弃 null header
 *      → login / register / sendEmailCode / resetPassword 四个端点限流形同虚设。
 *
 * 这三条都属于「不抛异常、不打警告、功能静默失效」型缺陷，
 * 常规冒烟与单测（Service 层 mock 掉 Redis）都照不到，故固化为机控断言。
 *
 * 用法：node scripts/verify/verify_password_session.mjs
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
 * 剥离注释与字符串字面量后再做结构分析。**仅用于「实参」类判定。**
 *
 * 为什么需要：判定「有没有把验证码交给日志」要看**实参**，
 * 而模板串里写 "code={}" 是无害的；不剥会把字符串内容误判成实参。
 * 剥离后 logger.info("...", email, type, code) → logger.info("", email, type, code)，
 * 实参 code 仍在，判定精确。
 *
 * ⚠️ 不要用它做**字面量内容**类判定（如 `value = "/login"`、`"rate_limit:ip:"`）——
 * 内容被清空后一律查不到，会造成恒红的假失败。字面量类判定请用 methodBody() 的原始返回。
 */
function stripCommentsAndStrings(src) {
  let out = ''
  let i = 0
  const n = src.length
  while (i < n) {
    const c = src[i]
    const d = src[i + 1]
    if (c === '/' && d === '/') {
      while (i < n && src[i] !== '\n') i++
      continue
    }
    if (c === '/' && d === '*') {
      i += 2
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++
      i += 2
      continue
    }
    if (c === '"' || c === "'") {
      const quote = c
      i++
      while (i < n && !(src[i] === quote && src[i - 1] !== '\\')) i++
      i++
      out += quote + quote
      continue
    }
    out += c
    i++
  }
  return out
}

/**
 * 抽取 Java 方法体（含大括号），**保留字符串与注释原文**，用于把断言限定在单个方法内，
 * 避免「A 方法调了 cleanUserTokenByUserId 就让 B 方法的断言也变绿」这类假绿。
 *
 * 大括号配对时跳过字符串与注释：方法体内出现字符串字面量含 `{`（如 "格式 {} 不对"）
 * 或嵌套注释时，朴素配对会数错层级、切出半个方法体。
 */
function methodBody(rawSrc, signaturePattern) {
  const m = rawSrc.match(signaturePattern)
  if (!m) return null
  let i = rawSrc.indexOf('{', m.index + m[0].length)
  if (i < 0) return null
  let depth = 0
  const n = rawSrc.length
  while (i < n) {
    const c = rawSrc[i]
    const d = rawSrc[i + 1]
    // 跳过行注释
    if (c === '/' && d === '/') {
      while (i < n && rawSrc[i] !== '\n') i++
      continue
    }
    // 跳过块注释
    if (c === '/' && d === '*') {
      i += 2
      while (i < n && !(rawSrc[i] === '*' && rawSrc[i + 1] === '/')) i++
      i += 2
      continue
    }
    // 跳过字符串字面量
    if (c === '"' || c === "'") {
      const q = c
      i++
      while (i < n && !(rawSrc[i] === q && rawSrc[i - 1] !== '\\')) i++
      i++
      continue
    }
    if (c === '{') depth++
    else if (c === '}') {
      depth--
      if (depth === 0) return rawSrc.slice(m.index, i + 1)
    }
    i++
  }
  return null
}

const SERVICE_PATH = 'easychat-java/src/main/java/com/easychat/service/impl/UserInfoServiceImpl.java'
const ASPECT_PATH = 'easychat-java/src/main/java/com/easychat/aspect/GlobalOperationAspect.java'
const MAIL_SERVICE_PATH = 'easychat-java/src/main/java/com/easychat/service/MailService.java'
const MAIL_IMPL_PATH = 'easychat-java/src/main/java/com/easychat/service/impl/MailServiceImpl.java'

console.log('===== 密码会话与验证码投递契约验证 =====\n')

const serviceSrc = read(SERVICE_PATH)
const aspectSrc = read(ASPECT_PATH)

// ── 1. 改密 / 找回密码成功后吊销全部端会话 ──
console.log('=== 1. 密码变更后会话失效 ===')
const updatePasswordBody = methodBody(serviceSrc, /public\s+void\s+updatePassword\s*\(/)
const resetPasswordBody = methodBody(serviceSrc, /public\s+void\s+resetPasswordByEmail\s*\(/)
const sendEmailCodeBody = methodBody(serviceSrc, /public\s+void\s+sendEmailCode\s*\(/)

check('定位到 updatePassword 方法体', updatePasswordBody !== null)
check('定位到 resetPasswordByEmail 方法体', resetPasswordBody !== null)
check(
  'updatePassword 成功后调用 cleanUserTokenByUserId（全端 Token 吊销）',
  !!updatePasswordBody && /cleanUserTokenByUserId\s*\(/.test(updatePasswordBody),
  updatePasswordBody ? '' : '方法体未定位'
)
check(
  'updatePassword 成功后调用 forceOffLine（推 FORCE_OFF_LINE 帧）',
  !!updatePasswordBody && /forceOffLine\s*\(/.test(updatePasswordBody)
)
check(
  'resetPasswordByEmail 成功后调用 cleanUserTokenByUserId',
  !!resetPasswordBody && /cleanUserTokenByUserId\s*\(/.test(resetPasswordBody)
)
check(
  'resetPasswordByEmail 成功后调用 forceOffLine',
  !!resetPasswordBody && /forceOffLine\s*\(/.test(resetPasswordBody)
)
// 防「把吊销写在方法开头」——必须发生在密码写入成功之后
check(
  'updatePassword 中会话吊销发生在密码写入之后（非方法开头）',
  !!updatePasswordBody &&
    updatePasswordBody.indexOf('cleanUserTokenByUserId') >
      updatePasswordBody.indexOf('updateByUserId'),
  updatePasswordBody ? '' : '方法体未定位'
)
check(
  'resetPasswordByEmail 中会话吊销发生在密码写入之后（非方法开头）',
  !!resetPasswordBody &&
    resetPasswordBody.indexOf('cleanUserTokenByUserId') >
      resetPasswordBody.indexOf('updateByUserId'),
  resetPasswordBody ? '' : '方法体未定位'
)

// ── 2. 验证码不经日志 ──
console.log('\n=== 2. 验证码不经日志 ===')
// 任何把标识符 code 传给 logger 的写法都算违规。
// 这里对**整份文件**剥字符串后再扫，否则模板串里的 "code={}" 会被误判成实参。
const loggerCallPattern = /logger\s*\.\s*(info|debug|warn|error|trace)\s*\(([^;]*?)\)/g
const codeInLog = []
let lm
while ((lm = loggerCallPattern.exec(stripCommentsAndStrings(serviceSrc))) !== null) {
  if (/\bcode\b/.test(lm[2])) {
    codeInLog.push(`${lm[1]}(...) @ offset ${lm.index}`)
  }
}
check('UserInfoServiceImpl 不把验证码 code 传给 logger', codeInLog.length === 0, codeInLog.join('; '))
check(
  'sendEmailCode 通过 mailService 投递验证码',
  !!sendEmailCodeBody && /mailService\s*\.\s*\w+\s*\(/.test(sendEmailCodeBody),
  sendEmailCodeBody ? '' : '方法体未定位'
)

// ── 3. 未登录端点按 IP 限流 ──
console.log('\n=== 3. 未登录端点限流 ===')
const rateLimitBody = methodBody(aspectSrc, /private\s+void\s+checkRateLimit\s*\(/)
check('定位到 checkRateLimit 方法体', rateLimitBody !== null)
check(
  'checkRateLimit 不存在「token == null 直接 return」早退',
  !!rateLimitBody && !/if\s*\(\s*token\s*==\s*null\s*\)\s*\{\s*return\s*;/.test(rateLimitBody),
  rateLimitBody ? '' : '方法体未定位'
)
check(
  'checkRateLimit 在 token 缺失时降级为按客户端 IP 计数（调用 IpTools.getClientIp）',
  !!rateLimitBody && /IpTools\.getClientIp\s*\(\s*\)/.test(rateLimitBody),
  rateLimitBody ? '' : '方法体未定位'
)
check(
  'checkRateLimit 存在 IP 维度的键字面量（"ip:"）',
  !!rateLimitBody && /"ip:"/.test(rateLimitBody)
)
// 2026-10-03 更新：IP 取值规则已抽到 utils/IpTools（与审计日志共用同一实现，
// 见 openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth）。
// 原先这里断言「切面自带私有 resolveClientIp」，架构调整后该断言已过期并会恒红——
// 门禁断言必须随架构演进更新，否则会被当成「故障」而误导排查方向。
check(
  'GlobalOperationAspect 不再自带私有 IP 解析（避免与审计各写一份规则）',
  !/private\s+String\s+resolveClientIp\s*\(/.test(aspectSrc),
  '仍存在私有 resolveClientIp —— IP 取值规则有两份实现'
)
// 限流键前缀不得改名：改了会让既有 Redis 键失效，等于静默把所有用户计数清零
check(
  '限流键前缀仍为 "rate_limit:"（改名会使既有计数失效）',
  !!rateLimitBody && /"rate_limit:"/.test(rateLimitBody)
)

// 四个未登录端点仍须标注 checkRateLimit（防止有人图省事删注解）。
// 这里刻意用**原始**源码：端点路径与限流键都是字符串字面量，剥掉就查不到了。
// 注意注解顺序为 @PostMapping → @GlobalInterceptor，故须向**前**找，且不得越过方法签名。
const accountController = read('easychat-java/src/main/java/com/easychat/controller/AccountController.java')
for (const ep of ['/login', '/register', '/sendEmailCode', '/resetPassword']) {
  const idx = accountController.indexOf(`value = "${ep}"`)
  let detail = idx < 0 ? '端点未找到' : ''
  let ok = false
  if (idx >= 0) {
    const sigIdx = accountController.indexOf('public ', idx)
    const between = sigIdx < 0 ? '' : accountController.slice(idx, sigIdx)
    ok = /@GlobalInterceptor\s*\([^)]*checkRateLimit\s*=\s*true[^)]*\)/.test(between)
    if (!ok) detail = `注解区段未含 checkRateLimit = true：${between.replace(/\s+/g, ' ').trim() || '(空)'}`
  }
  check(`/account${ep} 标注 checkRateLimit = true`, ok, detail)
}

// ── 4. 邮件服务真实投递 ──
console.log('\n=== 4. 邮件服务真实投递 ===')
check('MailService 接口存在', has(MAIL_SERVICE_PATH))
check('MailServiceImpl 实现存在', has(MAIL_IMPL_PATH))
if (has(MAIL_IMPL_PATH)) {
  const mailClean = stripCommentsAndStrings(read(MAIL_IMPL_PATH))
  check('MailServiceImpl 未配置 host 时 fail-closed（CODE_1002）', /CODE_1002/.test(mailClean))
  // 上一条只查 CODE_1002 是否出现，抓不到「把 throw 换成 logger+return」这种回退。
  // 这里锚定 fail-closed 分支的措辞：必须是一句 throw，且消息点明「未配置」。
  check(
    'MailServiceImpl 的未配置分支是 throw 而非 log/return（fail-closed 纪律本体）',
    /throw\s+new\s+BusinessException\s*\(\s*ResponseCodeEnum\.CODE_1002\s*,\s*"邮件服务未配置[^"]*"\s*\)/.test(
      read(MAIL_IMPL_PATH)
    ),
    '未找到「throw CODE_1002 + 邮件服务未配置」的语句'
  )
  // 兜底：整个实现类不得出现把验证码交给 logger 的写法
  const mailLoggerLeak = []
  let mlm
  const mailLoggerRe = /logger\s*\.\s*(info|debug|warn|error|trace)\s*\(([^;]*?)\)/g
  while ((mlm = mailLoggerRe.exec(mailClean)) !== null) {
    if (/\bcode\b/.test(mlm[2])) mailLoggerLeak.push(`${mlm[1]}(...) @ offset ${mlm.index}`)
  }
  check('MailServiceImpl 不把验证码 code 传给 logger', mailLoggerLeak.length === 0, mailLoggerLeak.join('; '))
  check(
    'MailServiceImpl 用 SpringMailSender 发送（未自造 SMTP 客户端）',
    /JavaMailSender|getJavaMailSender/.test(mailClean)
  )
  // 主题必须是固定字面量：不得把用户输入的 email 拼进主题（邮件头注入面）
  const subjects = mailClean.match(/setSubject\s*\(([^)]*)\)/g) || []
  const subjectLeaks = subjects.filter((s) => /email/i.test(s))
  check(
    '邮件主题不含用户输入的 email（防邮件头注入）',
    subjects.length > 0 && subjectLeaks.length === 0,
    subjectLeaks.join('; ')
  )
}
check(
  'pom.xml 引入 spring-boot-starter-mail',
  /<artifactId>spring-boot-starter-mail<\/artifactId>/.test(read('easychat-java/pom.xml'))
)
check(
  'spring-boot-starter-mail 未硬写版本号（由 parent 2.6.1 管理）',
  !/<artifactId>spring-boot-starter-mail<\/artifactId>\s*<version>/.test(read('easychat-java/pom.xml'))
)

// ── 5. 运行时配置：mail 键全为占位符、零裸值 ──
console.log('\n=== 5. 运行时配置分层 ===')
const devProps = read('easychat-java/src/main/resources/application-dev.properties')
const prodProps = read('easychat-java/src/main/resources/application-prod.properties')
const envExample = read('.env.example')

check('application-prod.properties 含 spring.mail.host 占位', /spring\.mail\.host\s*=\s*\$\{/.test(prodProps))
check('application-prod.properties 含 spring.mail.password 占位', /spring\.mail\.password\s*=\s*\$\{/.test(prodProps))
check('application-dev.properties 含 spring.mail.host 配置位', /spring\.mail\.host\s*=/.test(devProps))
check('.env.example 含 SPRING_MAIL_HOST 占位', /SPRING_MAIL_HOST\s*=/.test(envExample))
check(
  'prod 的 spring.mail.password 不带默认可用值（与 DB 密码同一纪律）',
  /spring\.mail\.password\s*=\s*\$\{[A-Z_]*SPRING_MAIL_PASSWORD:\}/.test(prodProps)
)

// ── 6. 门禁自身接入 ──
console.log('\n=== 6. 门禁接入 CI ===')
const ci = read('.github/workflows/ci.yml')
check('ci.yml 已接入 verify_password_session.mjs', /verify_password_session\.mjs/.test(ci))

// ── 汇总 ──
const failed = results.filter(([, ok]) => !ok)
console.log(`\n===== 结论：${results.length - failed.length}/${results.length} 通过 =====`)
if (failed.length) {
  console.log('失败项：')
  failed.forEach(([n]) => console.log(`  - ${n}`))
  process.exit(1)
}
process.exit(0)