#!/usr/bin/env node
/**
 * preflight-baseline-check.mjs —— 启用 Flyway 自动纳管前的结构一致性前置校验
 *
 * 为什么需要（openspec/changes/2026-10-04-flyway-migration-automation design.md §0 实测 3）：
 *   Flyway 的 `baseline-on-migrate` **不比对 schema 内容**——它只看
 *   「schema 非空 且 没有历史表」，然后把 ≤ baseline-version 的迁移**一律视为已应用**。
 *   于是一个实际只跑到 011 的库，会被 baseline 到 12 并**静默跳过 012**。
 *   「假定即事故」——本项目已因「只改基线忘迁移」吃过两次 500。
 *
 * 本脚本只做一件事（刻意不做更多，因为无法可靠判断存量库的真实版本号）：
 *   **若配置的 baseline-version 等于「仓库内最大迁移编号」= 声称自己是最新版，
 *     但活库结构与 easychat.sql 基线不一致，则拒绝启动。**
 *
 * 不做的事：
 *   · 不判断存量库的真实版本号（那需要历史信息，只能由人声明一次）；
 *   · 不在 baseline-version < 最大编号 时阻断（那是「合法的存量库待升级」，由人声明即可）。
 *
 * 退出码：0 放行 / 1 拒绝（并打印差异清单）
 */
import { readFileSync, readdirSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

const CFG = {
  host: process.env.SCHEMA_DB_HOST || '127.0.0.1',
  port: process.env.SCHEMA_DB_PORT || '3306',
  user: process.env.SCHEMA_DB_USER || 'root',
  password: process.env.SCHEMA_DB_PASSWORD || 'root',
  db: process.env.SCHEMA_DB_NAME || 'easychat'
}

/**
 * 本次要校验的 baseline-version。
 *
 * ⚠ 必须**优先读环境变量**：运维声明存量库真实版本号的唯一途径就是
 * `SPRING_FLYWAY_BASELINE_VERSION=N`（application.properties 里写的是
 * `${SPRING_FLYWAY_BASELINE_VERSION:12}` 形式，默认值 12）。
 * 若只读文件默认值，一个「实际只到 011」的存量库用 env 声明 9 之后，
 * 本脚本仍会按 12 判定它「声称自己是最新版」并**拒绝启动**——
 * 恰好堵死自己在报错里建议的那条处置路径 a。2026-10-04 自查发现。
 */
function configuredBaselineVersion() {
  const env = process.env.SPRING_FLYWAY_BASELINE_VERSION
  if (env !== undefined && /^\d+$/.test(env.trim())) {
    return { version: Number(env.trim()), source: '环境变量 SPRING_FLYWAY_BASELINE_VERSION' }
  }
  const props = readFileSync(
    resolve(ROOT, 'easychat-java/src/main/resources/application.properties'), 'utf8')
  const m = props.match(/SPRING_FLYWAY_BASELINE_VERSION:(\d+)/)
  return { version: m ? Number(m[1]) : 12, source: 'application.properties 默认值' }
}

function maxMigrationVersion() {
  const files = readdirSync(ROOT).filter((f) => /^easychat-migration-\d{3}-.*\.sql$/.test(f))
  const nums = files.map((f) => Number(f.match(/^easychat-migration-(\d{3})-/)[1]))
  return nums.length ? Math.max(...nums) : 0
}

function mysqlAvailable() {
  const candidates = [
    process.env.SCHEMA_MYSQL_BIN,
    'C:\\Program Files\\MySQL\\MySQL Server 5.7\\bin\\mysql.exe',
    'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
    '/usr/bin/mysql',
    '/usr/local/bin/mysql'
  ].filter(Boolean)
  for (const c of candidates) {
    try {
      execFileSync(c, ['--version'], { stdio: 'pipe' })
      return c
    } catch (_) { /* 试下一个 */ }
  }
  return null
}

function query(bin, sql) {
  return execFileSync(bin,
    [`--host=${CFG.host}`, `--port=${CFG.port}`, `--user=${CFG.user}`,
     `--password=${CFG.password}`, '--default-character-set=utf8mb4',
     '-N', '-B', '-e', sql],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 32 * 1024 * 1024 })
}

/**
 * 解析 easychat.sql 基线 → Map<表, Set<列>>
 * 与 verify_schema_drift.mjs 同款解析思路（只取表/列，不比对索引与注释）。
 */
function parseBaseline() {
  const sql = readFileSync(resolve(ROOT, 'easychat.sql'), 'utf8')
  const tables = new Map()
  const re = /CREATE\s+TABLE\s+`([^`]+)`\s*\(([\s\S]*?)\n\)\s*ENGINE/gi
  let m
  while ((m = re.exec(sql)) !== null) {
    const cols = new Set()
    for (const raw of m[2].split(/\r?\n/)) {
      const line = raw.trim()
      if (!line) continue
      if (/^(PRIMARY\s+KEY|UNIQUE|KEY|INDEX|CONSTRAINT|FULLTEXT)\b/i.test(line)) continue
      const cm = line.match(/^`([^`]+)`\s+[a-z]/i)
      if (cm) cols.add(cm[1])
    }
    tables.set(m[1], cols)
  }
  return tables
}

function main() {
  console.log('[preflight] 存量库纳管前置校验 —— 「声称最新」时结构必须真的最新\n')

  const configured = configuredBaselineVersion()
  const maxVer = maxMigrationVersion()
  console.log(`  baseline-version 配置 = ${configured.version}（来源：${configured.source}）`)
  console.log(`  仓库内最大迁移编号 = ${maxVer}`)

  // 库为空 → Flyway 会从零跑迁移（虽然本项目因 @PostConstruct 自举问题走不通，
  // 但那属于另一件事），不归本脚本管
  const bin = mysqlAvailable()
  if (!bin) {
    console.error('[preflight] 找不到 mysql 客户端，无法校验。fail-closed。')
    process.exit(1)
  }

  let tableCount
  let hasHistory
  try {
    tableCount = Number(query(bin,
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + CFG.db + "';").trim())
    hasHistory = Number(query(bin,
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + CFG.db +
      "' AND table_name='flyway_schema_history';").trim())
  } catch (e) {
    console.error('[preflight] 连库失败：' + String(e.stderr || e.message || e).slice(0, 300))
    console.error('[preflight] fail-closed：不校验就放行 = 放行了一次可能的静默跳过。')
    process.exit(1)
  }

  if (tableCount === 0) {
    console.log('  库为空 → 无需纳管校验，放行。')
    process.exit(0)
  }
  if (hasHistory > 0) {
    console.log('  已有 Flyway 历史表 → 由版本表负责记账，跳过结构比对。')
    process.exit(0)
  }
  if (configured.version !== maxVer) {
    console.log(`  baseline-version(${configured.version}) != 最大迁移号(${maxVer}) → ` +
      '这是「合法的存量库待升级」：由人声明的版本号决定要跑哪些迁移，不做结构比对、不阻断。')
    process.exit(0)
  }

  // 声称自己是最新版 → 结构必须真的与基线一致
  console.log('  该库声称自己是最新版（baseline-version == 最大迁移号），比对结构…\n')

  const baseline = parseBaseline()
  const live = new Map()
  const out = query(bin,
    "SELECT table_name, column_name FROM information_schema.columns " +
    "WHERE table_schema='" + CFG.db + "' ORDER BY table_name, ordinal_position;")
  for (const line of out.split(/\r?\n/)) {
    const t = line.split('\t')
    if (t.length < 2) continue
    if (!live.has(t[0])) live.set(t[0], new Set())
    live.get(t[0]).add(t[1])
  }

  const missingTables = []
  const missingCols = []
  for (const [t, cols] of baseline) {
    const lc = live.get(t)
    if (!lc) { missingTables.push(t); continue }
    for (const c of cols) if (!lc.has(c)) missingCols.push(t + '.' + c)
  }

  if (!missingTables.length && !missingCols.length) {
    console.log(`  ✅ 结构与基线一致（${baseline.size} 张表全对齐）→ 放行。`)
    process.exit(0)
  }

  console.error('  ❌ 该库声称自己是最新版，但结构与 easychat.sql 不一致。')
  console.error('     若此时让 Flyway baseline，它会把未执行的迁移**静默跳过**——正是本项目吃过两次的坑。')
  if (missingTables.length) {
    console.error(`\n  缺失的表（${missingTables.length}）：`)
    missingTables.forEach((t) => console.error('    - ' + t))
  }
  if (missingCols.length) {
    console.error(`\n  缺失的列（${missingCols.length}）：`)
    missingCols.forEach((c) => console.error('    - ' + c))
  }
  console.error('\n  处置（二选一）：')
  console.error('   a) 若该库其实只到某个旧版本：把 SPRING_FLYWAY_BASELINE_VERSION 设为它的真实版本号，')
  console.error('      Flyway 会据此执行其后的迁移；')
  console.error('   b) 若该库确实该是最新版：先补执行缺失的迁移，或从 easychat.sql 重新导库。')
  process.exit(1)
}

main()