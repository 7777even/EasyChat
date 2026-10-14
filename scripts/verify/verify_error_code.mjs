#!/usr/bin/env node
/**
 * 错误码拆分的前端行为验证（2026-10-14，error-code-split T4）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>{@code ErrorCode} 枚举与后端同步（含新增 1004/1005）</li>
 *   <li>拒绝对象带上 {@code code}，限流时额外带 {@code rateLimitSeconds}</li>
 *   <li><b>向后兼容</b>：老后端只回 1001 时，行为与变更前**完全一致</b></li>
 * </ol>
 *
 * <p>用法：node scripts/verify/verify_error_code.mjs
 */
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const REQ = path.join(ROOT, 'easychat-front', 'src', 'renderer', 'src', 'utils', 'Request.js')
const ENUM = path.join(ROOT, 'easychat-java', 'src', 'main', 'java', 'com', 'easychat', 'entity', 'enums', 'ResponseCodeEnum.java')
const ASPECT = path.join(ROOT, 'easychat-java', 'src', 'main', 'java', 'com', 'easychat', 'aspect', 'GlobalOperationAspect.java')

const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1')

console.log('===== 错误码拆分：前端行为与两端同步 =====\n')

const req = strip(fs.readFileSync(REQ, 'utf8'))
const aspect = fs.existsSync(ASPECT) ? strip(fs.readFileSync(ASPECT, 'utf8')) : ''
const enumSrc = fs.existsSync(ENUM) ? strip(fs.readFileSync(ENUM, 'utf8')) : ''

// ── 1. 枚举双向对账（**真做**，不是只查新增的两个码）─────────────────
console.log('=== 1. ErrorCode 枚举与后端双向对账 ===')

/** 后端：CODE_XXXX(XXXX, → 取数值集合 */
const backCodes = new Set(
  [...enumSrc.matchAll(/CODE_(\d{4})\s*\(\s*(\d{4})/g)].map((m) => m[2])
)
/** 前端：KEY: NUMBER → 取数值集合（排除 SUCCESS 与键名映射） */
const frontCodes = new Set(
  [...req.matchAll(/^\s*[A-Z_]+:\s*(\d{3,4}),?\s*$/gm)].map((m) => m[1])
)

check('后端错误码枚举可解析', backCodes.size > 0, `解析到 ${backCodes.size} 个码`)
check('前端 ErrorCode 枚举可解析', frontCodes.size > 0, `解析到 ${frontCodes.size} 个码`)

const onlyFront = [...frontCodes].filter((c) => !backCodes.has(c))
// ⚠ **单向对账，不是双向** —— 这是 2026-10-14 实测纠正的**规格错误**：
//   spec-delta 初版写「两个方向都要查」，实测前端枚举只有 11 个码而后端 40+ 个，
//   因为前端**只声明「用得上的」子集**（2202、2301~2307、2401…2705 均未声明）。
//   按双向门禁将**永久报红** —— 那不是缺陷在修复，是门禁口径写错了。
//   正确口径：**前端不得引用后端不存在的码**（防「幽灵码」，那会让调用永不命中）；
//   后端有而前端没有 = 前端暂时用不到，属正常，**不报红**。
check('前端不得引用后端不存在的码（防幽灵码）', onlyFront.length === 0,
  onlyFront.length ? `前端多出：${onlyFront.join(', ')} —— 调用将永不命中` : '')
check('（信息）前端为「用得到的子集」，后端多出的码不算错误', true,
  `后端 ${backCodes.size} 个 / 前端 ${frontCodes.size} 个`)
check('ErrorCode 枚举含 RATE_LIMITED(1004)', /RATE_LIMITED:\s*1004/.test(req))
check('ErrorCode 枚举含 CAPTCHA_ERROR(1005)', /CAPTCHA_ERROR:\s*1005/.test(req))

// ── 2. 拒绝对象带 code，且限流带窗口秒数 ──────────────────────────
console.log('\n=== 2. 拒绝对象携带结构化信息 ===')
// 判定边界：**只取 buildReject 函数体**判断，不扫全文 ——
// 否则请求层里任何一处出现 code 字样都会让本断言通过（AGENTS §2.1 第 8 条）。
// 初版即踩中：假设 reject 是内联对象字面量，而实现已抽成辅助函数 → 断言恒假。
const fnBody = (() => {
  const i = req.indexOf('const buildReject')
  if (i < 0) return null
  const end = req.indexOf('\n};', i)
  return end < 0 ? null : req.slice(i, end)
})()
check('buildReject 辅助函数存在', fnBody !== null)
check('拒绝对象带上 code（调用方才能按码分流）',
  fnBody !== null && /\bcode\b\s*[:,]/.test(fnBody),
  '判定边界：只看 buildReject 函数体，不扫全文')
check('限流分支带 rateLimitSeconds',
  fnBody !== null && /rateLimitSeconds/.test(fnBody))
check('限流倒计时常量与服务端窗口对账的注释在位',
  /RATE_LIMIT_WINDOW_SECONDS/.test(req), '常量名与后端同名，便于门禁静态对账')

// ── 3. 向后兼容 ───────────────────────────────────────────────────
console.log('\n=== 3. 向后兼容（老后端回 1001 时行为不变）===')
check('默认分支仍按 message 提示（老码走这里）',
  /msg:\s*responseData\.message\s*\|\|\s*responseData\.info\s*\|\|\s*'请求失败'/.test(req))
// ⚠ 判定边界：**PARAM_ERROR 在剥注释后的代码里完全不应出现**。
//   初版只查 `code == ErrorCode.PARAM_ERROR` 一种写法，
//   探针换成 `responseData.code === ErrorCode.PARAM_ERROR` 就溜过去了 ——
//   即「一换写法就失效」的断言，等于没有断言。
//   理由：1001 是**老后端也会返回**的码，一旦被特殊处理，
//   所有旧服务的流量都会被误路由（向后兼容被破坏）。
check('PARAM_ERROR 不得出现在任何条件分支中（向后兼容）',
  !req.includes('ErrorCode.PARAM_ERROR'),
  '剥注释后仍出现 → 说明 1001 被特殊处理了，老后端流量会被误路由')

// ── 4. 防空转自检：判据必须扫得到 ─────────────────────────────────
console.log('\n=== 4. 防空转自检 ===')
check('正例：RATE_LIMITED 判据能命中', /RATE_LIMITED:\s*1004/.test(req))
check('反例：注释中的引用不误命中（已剥注释）',
  !strip('// const X = 1 // RATE_LIMITED: 9999').includes('RATE_LIMITED: 1004'))

// ── 5. 服务端限流窗口常量（供 ADR-002 对账）───────────────────────
console.log('\n=== 5. 服务端限流窗口 ===')
check('GlobalOperationAspect 仍声明 RATE_LIMIT_WINDOW_SECONDS',
  /RATE_LIMIT_WINDOW_SECONDS/.test(aspect))
const m = aspect.match(/RATE_LIMIT_WINDOW_SECONDS\s*=\s*(\d+)/)
const front = req.match(/RATE_LIMIT_WINDOW_SECONDS\s*[:=]\s*(\d+)/)
check('前后端窗口秒数相等（ADR-002 防倒计时脱节）',
  !!m && !!front && m[1] === front[1],
  `后端=${m ? m[1] : '未取到'} 前端=${front ? front[1] : '未取到'}`)

// ── 汇总 ────────────────────────────────────────────────────────
const fail = results.filter(([, ok]) => !ok).length
console.log(`\n===== 结论：${results.length - fail}/${results.length} 通过 =====`)
if (fail) { console.log(`失败 ${fail} 项`); process.exit(1) }
