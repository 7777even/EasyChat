#!/usr/bin/env node
// 变异检验：故意破坏三处认证面契约，验证 verify_password_session.mjs 是否真的会失败。
//
// 目的：证明门禁**有判别力**，而不是「脚本存在所以有门禁」（AGENTS.md §2.1 第 1 条）。
//
// ⚠ 与 mutation_ws_frame_parity.cjs 的关键差异（刻意为之，不要改回改真文件）：
//   那个脚本临时改写真实源文件再还原，因此必须加「工作区必须干净」的前置守卫——
//   一旦忘�� stash，还原会静默覆盖未提交改动。本脚本改用**沙箱副本**：
//   把门禁脚本与它读取的 10 个文件复制到临时目录，门禁从副本自身位置解析 ROOT，
//   于是天然只读沙箱。代价是要维护下面的 FILES 清单，新增门禁读取文件时必须同步补进来。
//
// 用法：node scripts/verify/mutation_password_session.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_password_session.mjs'

// 门禁会读取的全部文件。少复制一个，沙箱里就会缺文件 → 断言恒红 → 假通过。
// 新增门禁读取文件时**必须**同步补进本清单。
const FILES = [
  'easychat-java/src/main/java/com/easychat/service/impl/UserInfoServiceImpl.java',
  'easychat-java/src/main/java/com/easychat/aspect/GlobalOperationAspect.java',
  'easychat-java/src/main/java/com/easychat/controller/AccountController.java',
  'easychat-java/src/main/java/com/easychat/service/MailService.java',
  'easychat-java/src/main/java/com/easychat/service/impl/MailServiceImpl.java',
  'easychat-java/pom.xml',
  'easychat-java/src/main/resources/application-dev.properties',
  'easychat-java/src/main/resources/application-prod.properties',
  '.env.example',
  '.github/workflows/ci.yml'
]

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-pwdgate-'))
let allCaught = true
let skipped = 0

/** 把 FILES 与门禁自身复制进沙箱 */
function resetSandbox () {
  fs.rmSync(sandbox, { recursive: true, force: true })
  fs.mkdirSync(sandbox, { recursive: true })
  const missing = []
  for (const rel of FILES) {
    const src = path.join(ROOT, rel)
    if (!fs.existsSync(src)) {
      missing.push(rel)
      continue
    }
    const dst = path.join(sandbox, rel)
    fs.mkdirSync(path.dirname(dst), { recursive: true })
    fs.copyFileSync(src, dst)
  }
  const gateDst = path.join(sandbox, GATE_REL)
  fs.mkdirSync(path.dirname(gateDst), { recursive: true })
  fs.copyFileSync(path.join(ROOT, GATE_REL), gateDst)
  return missing
}

/** 跑沙箱内的门禁副本。返回 { code, out }；code === null 表示命令没启动成功 */
function runSandboxGate () {
  try {
    execFileSync('node', [path.join(sandbox, GATE_REL)], {
      cwd: sandbox, encoding: 'utf8', stdio: 'pipe'
    })
    return { code: 0, out: '' }
  } catch (e) {
    return { code: e.status === undefined ? null : e.status, out: (e.stdout || '') + (e.stderr || '') }
  }
}

/** 跑真实仓库的门禁（用于比对沙箱基线是否与真实一致） */
function runRealGate () {
  try {
    execFileSync('node', [path.join(ROOT, GATE_REL)], { cwd: ROOT, encoding: 'utf8', stdio: 'pipe' })
    return { code: 0 }
  } catch (e) {
    return { code: e.status === undefined ? null : e.status }
  }
}

/** 在沙箱内对某文件做若干处精确替换。任一锚点未命中 → 返回原因（不算捕获） */
function mutateSandboxFile (rel, edits) {
  const p = path.join(sandbox, rel)
  if (!fs.existsSync(p)) return '文件不存在'
  let src = fs.readFileSync(p, 'utf8')
  // ⚠️ 锚点换行不敏感（AGENTS §2.1 第 10 条 ④）：被改文件在 Windows checkout 下多为 CRLF，
  //   锚点里的字面量 `\n` 会恒不命中 → 变异静默空转而汇总行仍显示已捕获。
  const eol = src.includes('\r\n') ? '\r\n' : '\n'
  let srcLf = src.replace(/\r\n/g, '\n')
  for (let i = 0; i < edits.length; i++) {
    const findLf = edits[i].find.replace(/\r\n/g, '\n')
    const replLf = edits[i].repl.replace(/\r\n/g, '\n')
    if (!srcLf.includes(findLf)) return `第 ${i + 1} 处锚点未命中`
    const mutated = srcLf.replace(findLf, replLf)
    if (mutated === srcLf) return `第 ${i + 1} 处变异后内容未变化`
    srcLf = mutated
  }
  fs.writeFileSync(p, srcLf.replace(/\n/g, eol), 'utf8')
  return null
}

const SERVICE = FILES[0]
const ASPECT = FILES[1]
const CONTROLLER = FILES[2]
const MAIL_IMPL = FILES[4]
const POM = FILES[5]
const PROD_PROPS = FILES[7]
const ENV_EXAMPLE = FILES[8]

const mutations = [
  {
    name: '变异1 改密成功后不再吊销会话（回退本次核心改造）',
    rel: SERVICE,
    edits: [
      {
        find: '        redisComponet.cleanUserTokenByUserId(userId);\r\n        forceOffLine(userId);',
        repl: '        // MUTATION-1 会话吊销被移除'
      }
    ]
  },
  {
    name: '变异2 把会话吊销挪到密码写入之前（破坏「失败不吊销」语义）',
    rel: SERVICE,
    edits: [
      {
        // 先删掉写入之后的吊销
        find: '        redisComponet.cleanUserTokenByUserId(userId);\r\n        forceOffLine(userId);',
        repl: '        // MUTATION-2 吊销被前移'
      },
      {
        // 再塞到写库之前
        find: '        UserInfo updateInfo = new UserInfo();\r\n        updateInfo.setPassword(com.easychat.utils.PasswordEncoder.encode(newPassword));\r\n        userInfoMapper.updateByUserId(updateInfo, userId);',
        repl: '        redisComponet.cleanUserTokenByUserId(userId);\r\n        forceOffLine(userId);\r\n        UserInfo updateInfo = new UserInfo();\r\n        updateInfo.setPassword(com.easychat.utils.PasswordEncoder.encode(newPassword));\r\n        userInfoMapper.updateByUserId(updateInfo, userId);'
      }
    ]
  },
  {
    name: '变异3 找回密码成功后不再吊销会话',
    rel: SERVICE,
    edits: [
      {
        find: '        redisComponet.cleanUserTokenByUserId(userInfo.getUserId());\r\n        forceOffLine(userInfo.getUserId());',
        repl: '        // MUTATION-3 会话吊销被移除'
      }
    ]
  },
  {
    name: '变异4 验证码重新写回日志（回退 fail-closed）',
    rel: SERVICE,
    edits: [
      {
        find: '        mailService.sendVerifyCode(email, code, type == null ? 0 : type);',
        repl: '        logger.info("邮箱验证码已生成 email={}, code={}", email, code);'
      }
    ]
  },
  {
    // 2026-10-03 更新锚点：checkRateLimit 的 IP 取值已从私有 resolveClientIp(request)
    //   改为委托 IpTools.getClientIp()（与审计日志共用同一实现）。
    //   变异形态随之改为「整段 IP 降级分支换回 token 缺失即 return」。
    name: '变异5 未登录端点限流退回「token 为空直接 return」',
    rel: ASPECT,
    edits: [
      {
        find: '        String key = "rate_limit:";\r\n        if (!StringTools.isEmpty(token)) {\r\n            key = key + token;\r\n        } else {\r\n            key = key + "ip:" + IpTools.getClientIp();\r\n        }',
        repl: '        if (token == null) {\r\n            return;\r\n        }\r\n        String key = "rate_limit:" + token;'
      }
    ]
  },
  {
    name: '变异5b 限流键前缀被改名（既有 Redis 计数失效 = 静默全员清零）',
    rel: ASPECT,
    edits: [
      {
        find: '        String key = "rate_limit:";',
        repl: '        String key = "rl:";'
      }
    ]
  },
  {
    name: '变异6 摘掉 /account/resetPassword 的 checkRateLimit 注解',
    rel: CONTROLLER,
    edits: [
      {
        find: '    @GlobalInterceptor(checkLogin = false, checkRateLimit = true)\r\n    public Result<Void> resetPassword(',
        repl: '    @GlobalInterceptor(checkLogin = false)\r\n    public Result<Void> resetPassword('
      }
    ]
  },
  {
    name: '变异7 未配置邮件时改为打日志放行（fail-closed 纪律被回退）',
    rel: MAIL_IMPL,
    edits: [
      {
        find: '            throw new BusinessException(ResponseCodeEnum.CODE_1002, "邮件服务未配置，无法发送验证码");',
        repl: '            logger.info("邮件未配置，验证码 {}", safeCode);\r\n            return;'
      }
    ]
  },
  {
    name: '变异8 邮件主题拼入用户 email（邮件头注入面）',
    rel: MAIL_IMPL,
    edits: [
      {
        find: '        helper.setSubject(SUBJECT);',
        repl: '        helper.setSubject(SUBJECT + " - " + safeEmail);'
      }
    ]
  },
  {
    name: '变异9 移除 spring-boot-starter-mail 依赖',
    rel: POM,
    edits: [
      {
        find: '<artifactId>spring-boot-starter-mail</artifactId>',
        repl: '<artifactId>spring-boot-starter-mail-removed</artifactId>'
      }
    ]
  },
  {
    name: '变异10 prod 的 spring.mail.password 给了默认可用值',
    rel: PROD_PROPS,
    edits: [
      {
        find: 'spring.mail.password=${SPRING_MAIL_PASSWORD:}',
        repl: 'spring.mail.password=changeme'
      }
    ]
  },
  {
    name: '变异11 .env.example 撤掉 SPRING_MAIL_HOST',
    rel: ENV_EXAMPLE,
    edits: [
      {
        find: 'SPRING_MAIL_HOST=',
        repl: '# SPRING_MAIL_HOST removed'
      }
    ]
  }
]

console.log('=== 变异检验：verify_password_session.mjs ===')
console.log('（沙箱副本，不触碰工作区任何文件）')
console.log('沙箱：' + sandbox + '\n')

// ── 前置：沙箱基线必须与真实基线一致 ──
// 不一致说明 FILES 清单有遗漏（沙箱缺文件 → 门禁在沙箱里恒红 → 后续「全部捕获」全是假通过）。
const real = runRealGate()
const missingFiles = resetSandbox()
if (missingFiles.length) {
  console.log('  [FAIL ] FILES 清单有缺失，沙箱文件不全（不算通过）：')
  missingFiles.forEach((f) => console.log('            - ' + f))
  allCaught = false
}
const sandboxBase = runSandboxGate()
const sandboxMatches = sandboxBase.code === real.code
if (!sandboxMatches) allCaught = false
console.log(`  [${sandboxMatches ? 'PASS' : 'FAIL'}] 沙箱基线与真实基线一致：真实 exit=${real.code} / 沙箱 exit=${sandboxBase.code}`)
console.log(`         （当前门禁应为绿=0；若为红说明本批改造尚未完成，此时跑本脚本无意义）\n`)

for (const m of mutations) {
  const missing = resetSandbox()
  if (missing.length) {
    // 沙箱缺文件会让门禁恒红，后续「全部捕获」就成了假通过，必须当场判失败
    console.log(`  [FAIL ] ${m.name} —— FILES 清单缺 ${missing.join(', ')}（不算通过）`)
    allCaught = false
    skipped++
    continue
  }
  const problem = mutateSandboxFile(m.rel, m.edits)
  if (problem) {
    console.log(`  [FAIL ] ${m.name} —— ${problem}（脚本需更新，不算通过）`)
    allCaught = false
    skipped++
    continue
  }
  const r = runSandboxGate()
  // 纪律：code === null（命令没启动）一律判失败，不能当「非零即捕获」
  const caught = r.code !== 0 && r.code !== null
  if (!caught) allCaught = false
  console.log(`  [${caught ? 'CAUGHT' : 'MISSED'}] ${m.name} → exit=${r.code}`)
  if (!caught) {
    console.log('            门禁未能识破该变异 → 无判别力')
  }
}

// 收尾：清沙箱
fs.rmSync(sandbox, { recursive: true, force: true })

const executed = mutations.length - skipped
console.log('')
if (skipped > 0) console.log(`注意：有 ${skipped} 条变异因锚点未命中而未执行（不算通过）。`)
console.log(allCaught
  ? `结论：${executed}/${mutations.length} 条变异均被捕获 + 基线一致，门禁有判别力`
  : '结论：存在未被捕获的变异或沙箱基线不一致，门禁无判别力，需修复')
process.exit(allCaught ? 0 : 1)