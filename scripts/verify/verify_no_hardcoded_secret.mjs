/**
 * verify_no_hardcoded_secret.mjs
 *
 * 门禁：运行时配置外置（openspec/changes/2026-10-02-config-externalization）
 *
 * 断言 easychat-java/src/main/resources/ 下三份配置文件的分层纪律：
 *   1. application.properties（公共基线）不出现真实凭据、不出现环境专属的 TURN 三连
 *   2. application-dev.properties 提供本地联调值（含公共 TURN）
 *   3. application-prod.properties 不含公共 TURN 凭据、DB 密码无默认可用值
 *   4. 仓库根 .env 不入库、.env.example 入库
 *
 * 退出码：0 = 全部通过；1 = 有失败项
 */
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const RES_DIR = join(ROOT, 'easychat-java', 'src', 'main', 'resources')
const BASELINE = join(RES_DIR, 'application.properties')
const DEV = join(RES_DIR, 'application-dev.properties')
const PROD = join(RES_DIR, 'application-prod.properties')

const results = []
const pass = (msg) => results.push({ ok: true, msg })
const fail = (msg, detail) => results.push({ ok: false, msg, detail })

/** 把 properties 解析成 Map（忽略注释与空行，key= 后原样保留） */
function parseProps (file) {
  const map = new Map()
  const raw = readFileSync(file, 'utf8')
  for (const line of raw.split(/\r?\n/)) {
    const t = line.trim()
    if (!t || t.startsWith('#') || t.startsWith('!')) continue
    const i = t.indexOf('=')
    if (i < 0) continue
    map.set(t.slice(0, i).trim(), t.slice(i + 1).trim())
  }
  return map
}

/** 占位符形式：${ENV_VAR} 或 ${ENV_VAR:默认值} */
const isPlaceholder = (v) => /^\$\{[A-Z_][A-Z0-9_]*(:.*)?\}$/.test(v)

/**
 * 「敏感」的定义（先定义边界再写断言，避免误报困扰后来人）：
 *   判为敏感 → 必须写成 ${ENV_VAR:默认值} 占位符
 *     - 任何口令 / 密钥 / 令牌：password / secret / credential / token
 *     - TURN 中继账号：easychat.turn.username（与 credential 成对出现，单列也不安全）
 *   判为非敏感 → 允许裸值，不参与本门禁
 *     - 数据库用户名（spring.datasource.username）：默认 root 无泄露价值，
 *       真要收紧请显式加进下方白名单，而不是放宽正则
 *     - 端口 / 库名 / 池参数 / 日志级别
 */
const SECRET_KEY_RE = /^(?!spring\.datasource\.username$).*(password|secret|credential|token|turn\.username)$/i

// ── 1. 公共基线 ───────────────────────────────────────────────
if (!existsSync(BASELINE)) {
  fail('公共基线 application.properties 存在', '文件缺失')
} else {
  const base = parseProps(BASELINE)
  pass('公共基线 application.properties 存在')

  // 1.1 默认 profile 为 dev，保证零配置 clone 可启动
  if (base.get('spring.profiles.active') === 'dev') {
    pass('公共基线默认 profile = dev')
  } else {
    fail('公共基线默认 profile = dev', `实际为 ${base.get('spring.profiles.active')}`)
  }

  // 1.2 TURN 三连不得出现在公共基线（环境专属，移入 profile）
  for (const k of ['easychat.turn.url', 'easychat.turn.username', 'easychat.turn.credential']) {
    if (base.has(k)) {
      fail(`公共基线不含 ${k}`, `实际为 ${base.get(k)}`)
    } else {
      pass(`公共基线不含 ${k}`)
    }
  }

  // 1.3 敏感键必须是占位符形式，不得裸值
  let bareSecret = 0
  for (const [k, v] of base) {
    if (SECRET_KEY_RE.test(k) && !isPlaceholder(v) && v !== '') {
      fail(`公共基线敏感键为占位符形式：${k}`, `实际为 ${v}`)
      bareSecret++
    }
  }
  if (bareSecret === 0) pass('公共基线无裸凭据（全部敏感键为 ${ENV:默认值} 占位符）')

  // 1.4 管理员邮箱不得硬编码
  const mail = base.get('easychat.admin-emails')
  if (mail !== undefined && isPlaceholder(mail)) {
    pass('公共基线 easychat.admin-emails 为占位符')
  } else {
    fail('公共基线 easychat.admin-emails 为占位符', `实际为 ${mail}`)
  }
}

// ── 2. dev profile ────────────────────────────────────────────
if (!existsSync(DEV)) {
  fail('application-dev.properties 存在（承载本地联调值）', '文件缺失')
} else {
  const dev = parseProps(DEV)
  pass('application-dev.properties 存在')

  if ((dev.get('easychat.turn.url') || '').startsWith('turn:')) {
    pass('dev profile 提供公共 TURN 中继（本机双实例联调用）')
  } else {
    fail('dev profile 提供公共 TURN 中继', `实际为 ${dev.get('easychat.turn.url')}`)
  }
  if ((dev.get('spring.datasource.password') || '') !== '') {
    pass('dev profile 提供本地 DB 密码（零配置可启动）')
  } else {
    fail('dev profile 提供本地 DB 密码', '为空会导致零配置启动失败（违反 ADR-002）')
  }
}

// ── 3. prod profile ───────────────────────────────────────────
if (!existsSync(PROD)) {
  fail('application-prod.properties 存在（生产模板入库）', '文件缺失')
} else {
  const prod = parseProps(PROD)
  pass('application-prod.properties 存在')

  // 3.1 TURN 不得指向公共中继
  const pTurn = prod.get('easychat.turn.url')
  if (pTurn === undefined || pTurn === '' || pTurn === '${EASYCHAT_TURN_URL:}') {
    pass('prod profile TURN 留空（降级纯 STUN，不经第三方中继）')
  } else {
    fail('prod profile TURN 留空', `实际为 ${pTurn}`)
  }

  // 3.2 不得残留公共 TURN 凭据
  for (const [k, bad] of [['easychat.turn.username', 'guest'], ['easychat.turn.credential', 'guess']]) {
    if ((prod.get(k) || '') === bad) {
      fail(`prod profile 不含公共 TURN 凭据（${k}）`, `实际为 ${bad}`)
    } else {
      pass(`prod profile 不含公共 TURN 凭据（${k}）`)
    }
  }

  // 3.3 DB 密码无默认可用值 → 必须注入
  const pPwd = prod.get('spring.datasource.password')
  if (pPwd === '${SPRING_DATASOURCE_PASSWORD:}' || pPwd === '') {
    pass('prod profile DB 密码无默认可用值（必须注入）')
  } else {
    fail('prod profile DB 密码无默认可用值', `实际为 ${pPwd}`)
  }

  // 3.4 管理员邮箱不得含真实地址
  const pMail = prod.get('easychat.admin-emails') || ''
  if (pMail === '' || pMail === '${EASYCHAT_ADMIN_EMAILS:}' || !/@/.test(pMail)) {
    pass('prod profile 管理员邮箱不含真实地址')
  } else {
    fail('prod profile 管理员邮箱不含真实地址', `实际为 ${pMail}`)
  }
}

// ── 4. .env 载体 ──────────────────────────────────────────────
const gitignore = existsSync(join(ROOT, '.gitignore'))
  ? readFileSync(join(ROOT, '.gitignore'), 'utf8')
  : ''
if (/^\s*\.env\s*$/m.test(gitignore)) {
  pass('.gitignore 已忽略 .env')
} else {
  fail('.gitignore 已忽略 .env', '缺少 .env 规则，真实凭据可能被误提交')
}

if (existsSync(join(ROOT, '.env.example'))) {
  pass('.env.example 存在（模板入库）')
} else {
  fail('.env.example 存在（模板入库）', '文件缺失')
}

if (existsSync(join(ROOT, '.env'))) {
  fail('.env 未被提交', '工作区存在 .env，若已入库即为凭据泄露')
} else {
  pass('.env 未被提交')
}

// ── 输出 ──────────────────────────────────────────────────────
console.log('=== 运行时配置外置门禁 ===\n')
for (const r of results) {
  console.log(`   [${r.ok ? 'PASS' : 'FAIL'}] ${r.msg}${r.ok || !r.detail ? '' : `\n          → ${r.detail}`}`)
}
const total = results.length
const passed = results.filter((r) => r.ok).length
console.log(`\n===== 结论：${passed}/${total} 通过 =====`)
if (passed !== total) process.exit(1)
