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
 * ⚠️ SKIP 不等于通过：本门禁在 CI（ubuntu）上会跳过依赖 Windows `mvn.cmd` 绝对路径的脚本，
 *   这是**已知覆盖缺口**，不是「它们没问题」。已按三字段登记（见文件末尾）。
 *
 * ⚠️ 耗时：全部 9 个约 3~6 分钟（含 mvn test / npm run test 循环）。
 *   故**只接 CI，不接 pre-push** —— 推送前的快速闸门不该等这么久。
 *
 * 用法：node scripts/verify/verify_mutation_scripts.mjs [--only=<子串>]
 * 退出码：0 全部可跑者通过 / 1 有可跑者失败
 */
import { readdirSync, existsSync } from 'node:fs'
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
 * 每个脚本的运行前提。缺前提则报 SKIP 而不是 FAIL ——
 * 「本机跑不了」与「校验不通过」是两件事，混为一谈会让人误以为已覆盖。
 */
function prerequisites (name) {
  if (name === 'mutation_channel_online_status.cjs') {
    // 脚本内部：process.env.MVN_BIN || 'D:\\apache-maven-3.9.11\\...\\mvn.cmd'
    // 该绝对路径是 Windows 专属，CI（ubuntu）上必然失败 → 必须跳过而非误报 FAIL
    const bin = process.env.MVN_BIN
    if (!bin && process.platform === 'win32') {
      const guess = 'D:\\apache-maven-3.9.11\\apache-maven-3.9.11\\bin\\mvn.cmd'
      if (!existsSync(guess)) {
        return { ok: false, why: `需 Windows 的 mvn.cmd（未设 MVN_BIN，且默认路径 ${guess} 不存在）` }
      }
      return { ok: true }
    }
    if (!bin && process.platform !== 'win32') {
      return { ok: false, why: '依赖 Windows 专属的 mvn.cmd 绝对路径，CI(ubuntu) 上不可运行' }
    }
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
//   故断言目录里的脚本数量下限（2026-10-06 实扫为 9 个）。
//   仅在**未指定 --only** 时生效 —— 带 --only 时本来就只跑子集。
if (!only) {
  const total = readdirSync(DIR).filter((f) => /^mutation_.*\.(cjs|mjs)$/.test(f)).length
  console.log(`   [CHECK] 目录内变异脚本共 ${total} 个（应 ≥9；少于则说明被误删或改名）`)
  if (total < 9) {
    fail++
    console.log(`   [FAIL ] 变异脚本数量不足 9 个（实扫 ${total}）—— ` +
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
  console.log('⚠️ 已登记的覆盖缺口（SKIP ≠ 通过）：')
  console.log('   盲区：`mutation_channel_online_status.cjs` 依赖 Windows 专属 mvn.cmd 绝对路径，')
  console.log('        在 CI(ubuntu) 上无法运行 → 其 8 条变异在 CI 中**无人校验**。')
  console.log('   兜底手段：本机（Windows）手动运行该脚本；或给 CI 增设 `MVN_BIN` 并改脚本用 `mvn`')
  console.log('        而非绝对路径（属改动该脚本，需另开任务）。')
  console.log('   兜底实测证据：本门禁在本机（Windows）运行时该脚本为 PASS，')
  console.log('        且 `engineering/qa/evidence/` 下留有其完整输出。')
}

if (fail > 0) {
  console.log('')
  console.log('阻断：请先修好失败的变异脚本再推送 —— 它现在要么判定门禁无判别力，')
  console.log('      要么锚点已与源码漂移（此时「已验证 N/N 捕获」这句话是假的）。')
  process.exit(1)
}
process.exit(0)
