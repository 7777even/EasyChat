#!/usr/bin/env node
/**
 * verify_mutation_scripts.mjs —— 变异脚本「防腐」门禁（闭环遗留 #27）
 *
 * 背景（2026-10-06）：
 *   `scripts/verify/` 下 9 个 `mutation_*.{cjs,mjs}` 是**反向验证**（故意把代码改回缺陷实现，
 *   确认门禁真的会转红）。单次要起子进程、跑门禁甚至跑 mvn test，耗时 3~6 分钟，
 *   故**不接 pre-push**；2026-10-06 起由**本门禁**在 CI 独立 job 中统一驱动
 *   （此前它们既不进 CI 也无人手动跑 → 静默腐烂，实测曾有 2 个空转）。2026-10-06 实测发现 2 个已空转
 *   （call_store_core 1/11、local_db_core 11/12），而台账里写的仍是「11/11」「12/12」。
 *
 * 为什么本门禁是「跑它们」而不是「静态分析它们」：
 *   曾尝试写静态门禁判定「锚点匹配是否换行安全」，在 9 个脚本上产生 **2 类假阳性**
 *   （把锚点文本里的 `.includes(` 当成匹配代码；跨脚本变量数据流推断取错赋值）而否决。
 *   **报错 ≠ 断言正确** —— 不交付一个自己都判不准的断言。
 *   既然这 9 个脚本**本来就自带锚点未命中自报**（`[无效]` / `[FAIL ]` 三态计数，
 *   且未命中即 exit 非 0），缺的只是「有人真的跑一遍」，那就直接跑。
 *
 * 本门禁做什么：
 *   逐个 `execFileSync` 运行变异脚本，按退出码判定；
 *   **能跑的就必须过**（exit 非 0 即阻断），**跑不了的如实报 SKIP 并写明原因**。
 *
 * ⚠️ SKIP 不等于通过：本门禁**如实报**哪些脚本在当前环境跑不了（缺 Maven 等前提），
 *   跑不了的一律登记在文件末尾。2026-10-13 已消除 `mutation_channel_online_status.cjs`
 *   的跨平台缺口（该脚本原先硬编码 Windows `mvn.cmd` 绝对路径，ubuntu 上恒 SKIP，
 *   其 8 条变异无人校验）—— 现按平台分支启动 `mvn`，CI(ubuntu) 上会真正执行。
 *
 * ⚠️ 耗时：全部 9 个约 3~6 分钟（含 mvn test / npm run test 循环）。
 *   故**只接 CI，不接 pre-push** —— 推送前的快速闸门不该等这么久。
 *
 * 用法：node scripts/verify/verify_mutation_scripts.mjs [--only=<子串>]
 * 退出码：0 全部可跑者通过 / 1 有可跑者失败
 */
import { readdirSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const DIR = resolve(ROOT, 'scripts/verify')

const only = (process.argv.find((a) => a.startsWith('--only=')) || '').replace('--only=', '')

let pass = 0
let fail = 0
let skip = 0

/**
 * mvn 是否真的可用（2026-10-13 新增）。
 *
 * 背景：本门禁此前对 `mutation_channel_online_status.cjs` 在**非 win32 上一律 SKIP**
 * （理由「它硬编码 Windows 的 mvn.cmd 绝对路径」）。该脚本已于 2026-10-13 改为
 * 「MVN_BIN > PATH 上的 mvn」+ 按平台分支启动，故 SKIP 的前提已不成立 ——
 * 继续 SKIP 就等于**把「有判别力」和「没人跑过」混为一谈**。
 *
 * 判据用「真的把 mvn 跑起来」而不是「看某个路径存不存在」：
 * 只看路径会重蹈本仓多次「断言通过 ≠ 断言在做事」的覆辙。
 */
function mvnAvailable () {
  const bin = process.env.MVN_BIN || 'mvn'
  try {
    const opts = { encoding: 'utf8', stdio: 'pipe', timeout: 120000 }
    if (process.platform === 'win32') execFileSync('cmd', ['/c', bin, '-v'], opts)
    else execFileSync(bin, ['-v'], opts)
    return { ok: true }
  } catch (e) {
    return {
      ok: false,
      why: `mvn 不可用（${process.env.MVN_BIN ? `MVN_BIN=${process.env.MVN_BIN} → ` : ''}` +
        `${e.code || 'spawn failed'}）；装好 Maven 或把 MVN_BIN 指向**可执行**的完整路径后重跑。` +
        'SKIP 不等于通过'
    }
  }
}

/**
 * 每个脚本的运行前提。缺前提则报 SKIP 而不是 FAIL ——
 * 「本机跑不了」与「校验不通过」是两件事，混为一谈会让人误以为已覆盖。
 */
function prerequisites (name) {
  if (name === 'mutation_channel_online_status.cjs') {
    const mvn = mvnAvailable()
    if (!mvn.ok) return { ok: false, why: mvn.why }
    return { ok: true }
  }
  return { ok: true }
}

console.log('===== 变异脚本防腐（反向验证是否仍然有判别力）=====\n')
console.log(`平台：${process.platform} / node ${process.version}\n`)

const scripts = readdirSync(DIR)
  .filter((f) => /^mutation_.*\.(cjs|mjs)$/.test(f))
  .filter((f) => !only || f.includes(only))
  .sort()

console.log(`待跑 ${scripts.length} 个变异脚本（耗时约 3~6 分钟）\n`)

// ⚠️ 防「空跑即通过」：变异脚本被误删 / 改名 / 移走时，本门禁会「0 个通过」并 exit 0，
//   那正是 AGENTS §2.1 第 14 条点名的「断言通过 ≠ 断言在做事」。
//   故断言目录里的脚本数量下限（2026-10-13 实扫为 16 个：本日新增
//   mutation_local_file_guard_core.cjs / mutation_local_file_token_usage.cjs /
//   mutation_export_chat_core.cjs / mutation_db_model_core.cjs /
//   mutation_call_core.cjs / mutation_virtual_core.cjs / mutation_preflight_baseline_core.cjs
//   —— 即由 9 增至 16）。
//   仅在**未指定 --only** 时生效 —— 带 --only 时本来就只跑子集。
if (!only) {
  const total = readdirSync(DIR).filter((f) => /^mutation_.*\.(cjs|mjs)$/.test(f)).length
  console.log(`   [CHECK] 目录内变异脚本共 ${total} 个（应 ≥16；少于则说明被误删或改名）`)
  if (total < 16) {
    fail++
    console.log(`   [FAIL ] 变异脚本数量不足 16 个（实扫 ${total}）—— ` +
      '本门禁在「一个都没跑」的情况下也会 exit 0，属典型的假通过')
  } else {
    pass++
    console.log('   [PASS ] 变异脚本数量下限达标')
  }
  console.log('')
}

for (const name of scripts) {
  const pre = prerequisites(name)
  if (!pre.ok) {
    skip++
    console.log(`   [SKIP ] ${name}\n           ${pre.why}`)
    continue
  }

  const started = Date.now()
  let code = null
  let out = ''
  let crashed = false
  try {
    out = execFileSync('node', [join(DIR, name)], {
      cwd: ROOT,
      encoding: 'utf8',
      stdio: 'pipe',
      maxBuffer: 64 * 1024 * 1024,
      timeout: 10 * 60 * 1000
    })
    code = 0
  } catch (e) {
    // ⚠️ 必须区分「脚本自己判失败」与「脚本起不来」——
    //   只看 exit != 0 会把「命令启动失败」误记成「有变异漏网」，与本仓多次踩过的坑同源。
    code = e.status === undefined || e.status === null ? null : e.status
    out = `${e.stdout || ''}${e.stderr || ''}`
    if (e.killed) crashed = true
  }
  const secs = Math.round((Date.now() - started) / 1000)

  if (code === 0) {
    pass++
    console.log(`   [PASS ] ${name}  (${secs}s)`)
  } else {
    fail++
    const lines = out.split(/\r?\n/)
    const summary = (lines.find((l) => /结论/.test(l)) || '').trim()
    // ⚠️ 只摘**失败态**标记行：`[无效]`（锚点未命中）/ `[漏网]`（门禁仍 exit 0）/ `[FAIL ]`。
    //   首版把「失败」两字也当关键词，而**捕获用例的输出里满是**
    //   「exit=1，N 项失败，首项：…」—— 那是「成功捕获」的证据，
    //   却全被摘成失败行，噪音把真正的问题淹没了。
    const failed = lines
      .filter((l) => /\[无效\]|\[漏网\]|\[FAIL \]|✗|存在无判别力/.test(l))
      .slice(0, 5).map((l) => l.trim())
    const why = code === null
      ? `脚本未能启动（exit=null${crashed ? '，疑似超时' : ''}）—— 这是「环境问题」，不是「变异漏网」`
      : `exit=${code}`
    console.log(`   [FAIL ] ${name}  (${secs}s)  ${why}`)
    if (summary) console.log(`           ${summary}`)
    for (const f of failed) console.log(`           ${f}`)
    if (failed.length === 0) {
      console.log('           （脚本未输出失败态标记行，请手动运行该脚本查看完整输出）')
    }
    console.log('           → 变异脚本自身判定门禁无判别力，或锚点已与源码漂移。')
    console.log('             按 AGENTS §2.1 第 1 条处理：先确认门禁能跑，再谈判别力。')
  }
}

// ── 汇总 ────────────────────────────────────────────────────
console.log(`\n===== 结论：${pass} 通过 / ${fail} 失败 / ${skip} 跳过（共 ${scripts.length} 个）=====`)

if (skip > 0) {
  console.log('')
  console.log('⚠️ 本次运行中被跳过的脚本（SKIP ≠ 通过）：')
  console.log('   上方每个 [SKIP] 行已写明具体原因（多为缺少 Maven 等运行前提）。')
  console.log('   已消除的历史缺口：`mutation_channel_online_status.cjs` 原依赖 Windows 专属')
  console.log('   `mvn.cmd` 绝对路径 → ubuntu 上恒跳过；2026-10-13 起改为按平台启动 `mvn`。')
  console.log('   ⚠ 其「ubuntu 上真的能跑通」需以 CI 首次实跑为准（本地无法运行 GitHub Actions）。')
}

if (fail > 0) {
  console.log('')
  console.log('阻断：请先修好失败的变异脚本再推送 —— 它现在要么判定门禁无判别力，')
  console.log('      要么锚点已与源码漂移（此时「已验证 N/N 捕获」这句话是假的）。')
  process.exit(1)
}
process.exit(0)
