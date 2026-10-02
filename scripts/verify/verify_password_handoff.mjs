#!/usr/bin/env node
/**
 * 密码传递口径契约验证（回归守卫，防 BCrypt 改造引入的登录失败复发）
 *
 * 背景：2026-09-30 password-bcrypt 把服务端存储升级为 BCrypt，但前端登录仍发送
 * md5(明文)，而注册/改密/找回发送明文，导致两条口径分裂、登录必然失败。
 * 该缺陷之所以能逃过 CI，是因为当时既无覆盖 BCrypt 路径的单测，
 * 也无任何对「客户端不得自行哈希」的机控断言。
 *
 * 本脚本把契约固化为可执行断言，覆盖三处：
 *   1. 前端四条链路（登录/注册/改密/找回）一律发明文，不做客户端哈希；
 *   2. 服务端保持 BCrypt 验证 + MD5 双验证 + 自动升级；
 *   3. 冒烟脚本登录处发送明文（否则改造后冒烟会误判为"登录又坏了"）。
 *
 * 用法：node scripts/verify/verify_password_handoff.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const read = (p) => readFileSync(resolve(ROOT, p), 'utf8')

const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

const LOGIN_VUE = 'easychat-front/src/renderer/src/views/Login.vue'
const PWD_VUE = 'easychat-front/src/renderer/src/views/setting/UserInfoPassword.vue'
const SERVICE = 'easychat-java/src/main/java/com/easychat/service/impl/UserInfoServiceImpl.java'

console.log('===== 密码传递口径契约验证 =====\n')

// ── 1. 前端：不得对密码做客户端哈希 ──
console.log('=== 1. 前端链路（发明文） ===')
const loginVue = read(LOGIN_VUE)
const pwdVue = read(PWD_VUE)

check('Login.vue 不再 import js-md5', !/from\s+['"]js-md5['"]/.test(loginVue))
check(
  'Login.vue 登录/注册不再对 password 调 md5()',
  !/md5\s*\(\s*formData\.value\.password\s*\)/.test(loginVue)
)
check(
  'Login.vue 登录/注册 password 直接取明文',
  /password:\s*formData\.value\.password\s*[,}]/.test(loginVue)
)
check(
  'Login.vue 找回密码 newPassword 直接取明文',
  /newPassword:\s*formData\.value\.newPassword\s*[,}]/.test(loginVue)
)
check('UserInfoPassword.vue 无 md5 依赖', !/js-md5|md5\s*\(/.test(pwdVue))
check(
  'UserInfoPassword.vue 密码字段取自表单原值',
  // 允许两种写法：显式字段，或整体透传 formData
  (/oldPassword:\s*formData\.value\.oldPassword\s*[,}]/.test(pwdVue) &&
    /password:\s*formData\.value\.password\s*[,}]/.test(pwdVue)) ||
    /Object\.assign\(\s*params,\s*formData\.value\s*\)/.test(pwdVue)
)

// ── 2. 服务端：BCrypt 验证 + MD5 双验证 + 自动升级 ──
console.log('\n=== 2. 服务端双验证与自动升级 ===')
const service = read(SERVICE)
check('登录走 isBCrypt 分支验证', /isBCrypt\s*\(userInfo\.getPassword\(\)\)/.test(service))
check('登录 BCrypt 分支调用 PasswordEncoder.matches', /PasswordEncoder\.matches\(password,/.test(service))
check('登录 MD5 分支比对 encodeByMD5(password)', /getPassword\(\)\.equals\(StringTools\.encodeByMD5\(password\)\)/.test(service))
check('MD5 老账号登录成功后自动升级为 BCrypt', /PasswordEncoder\.encode\(password\)/.test(service))
check('注册使用 PasswordEncoder.encode', /userInfo\.setPassword\(com\.easychat\.utils\.PasswordEncoder\.encode\(password\)\)/.test(service))
check('改密旧密码支持 MD5/BCrypt 双验证', /oldPasswordValid = dbInfo\.getPassword\(\)\.equals\(StringTools\.encodeByMD5\(oldPassword\)\)/.test(service))

// ── 3. 冒烟脚本：登录处发送明文 ──
console.log('\n=== 3. 冒烟脚本登录口径 ===')
const smokeFiles = [
  'scripts/smoke/probe_admin.py',
  'scripts/smoke/smoke_admin_msg_delete.py',
  'scripts/smoke/smoke_admin_report.py',
  'scripts/smoke/smoke_call_log.py',
  'scripts/smoke/smoke_chat_backup.py',
  'scripts/smoke/smoke_group_file.py',
  'scripts/smoke/smoke_nudge.py',
  'scripts/smoke/smoke_sensitive_word.py',
]

/**
 * 找出一个脚本里所有 "password": <值> 字段，并判断该值是否源自 md5。
 * 两种写法都要覆盖：
 *   内联   "password": hashlib.md5(PWD_RAW.encode()).hexdigest()
 *   常量   PWD = hashlib.md5(b"...").hexdigest()  →  "password": PWD
 * 直插 DB 的 SQL fixture 用的是位置参数 %s，取不到标识符，
 * 因此不会被误判——那是有意保留的「存量 MD5 老账号」样本。
 */
function passwordValueIsHashed(src) {
  const reasons = []
  const lines = src.split(/\r?\n/)
  lines.forEach((line, i) => {
    const m = line.match(/["']password["']\s*:\s*([^,}]+)/)
    if (!m) return
    const value = m[1].trim()
    if (/hashlib\.md5/i.test(value)) {
      reasons.push(`L${i + 1} 内联 md5`)
      return
    }
    const ident = value.match(/^[A-Za-z_]\w*$/)
    if (ident) {
      // 回溯该标识符的赋值行
      const assign = new RegExp(`^\\s*${ident[0]}\\s*=.*md5`, 'im')
      if (assign.test(src)) reasons.push(`L${i + 1} 取自 ${ident[0]}（其赋值含 md5）`)
    }
  })
  return reasons
}

let violations = 0
for (const f of smokeFiles) {
  const reasons = passwordValueIsHashed(read(f))
  if (reasons.length) violations++
  check(`${f.split('/').pop()} 密码字段发送明文`, reasons.length === 0, reasons.join('; '))
}
check('无脚本发送 MD5 密码', violations === 0, `违规 ${violations} 个`)

// ── 汇总 ──
const failed = results.filter(([, ok]) => !ok)
console.log(`\n===== 结论：${results.length - failed.length}/${results.length} 通过 =====`)
if (failed.length) {
  console.log('失败项：')
  failed.forEach(([n]) => console.log(`  - ${n}`))
  process.exit(1)
}
process.exit(0)
