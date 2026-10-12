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
import {
  decidePreflight, configuredBaselineVersion, maxMigrationVersion,
  parseBaseline, parseLiveColumns, computeDiff, formatMissingReport
} from './preflightBaselineCore.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

const CFG = {
  host: process.env.SCHEMA_DB_HOST || '127.0.0.1',
  port: process.env.SCHEMA_DB_PORT || '3306',
  user: process.env.SCHEMA_DB_USER || 'root',
  password: process.env.SCHEMA_DB_PASSWORD || 'root',
  db: process.env.SCHEMA_DB_NAME || 'easychat'
}

/**
 * 本次要校验的 baseline-version（IO 接线：读文件；判定在纯核心里）。
 *
 * ⚠ 2026-10-12（遗留 #11）：判定逻辑已抽到 `preflightBaselineCore.mjs`，
 *   由 `verify_preflight_baseline_core.mjs` 做**行为**测试覆盖。
 *   「env 优先于 properties 默认值」这条方向性判断此前**无法**被静态断言看出来，
 *   现由行为测试锁定。
 */
function configuredBaselineVersionIO() {
  const props = readFileSync(
    resolve(ROOT, 'easychat-java/src/main/resources/application.properties'), 'utf8')
  return configuredBaselineVersion(process.env.SPRING_FLYWAY_BASELINE_VERSION, props)
}

function maxMigrationVersionIO() {
  return maxMigrationVersion(readdirSync(ROOT))
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
 * 解析 easychat.sql 基线（IO 接线：读文件；解析在纯核心里）
 */
function parseBaselineIO() {
  return parseBaseline(readFileSync(resolve(ROOT, 'easychat.sql'), 'utf8'))
}

function main() {
  console.log('[preflight] 存量库纳管前置校验 —— 「声称最新」时结构必须真的最新\n')

  const configured = configuredBaselineVersionIO()
  const maxVer = maxMigrationVersionIO()
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

  // 「声称自己是最新版」时才需要比对结构
  const claimsLatest = configured.version === maxVer
  let diff = { missingTables: [], missingCols: [] }
  if (claimsLatest) {
    console.log('  该库声称自己是最新版（baseline-version == 最大迁移号），比对结构…\n')
    const baseline = parseBaselineIO()
    const out = query(bin,
      "SELECT table_name, column_name FROM information_schema.columns " +
      "WHERE table_schema='" + CFG.db + "' ORDER BY table_name, ordinal_position;")
    diff = computeDiff(baseline, parseLiveColumns(out))
  }

  // ⚠ 判定在纯核心里（`decidePreflight`）。此前判定与 IO 混写在 main()，
  //   导致 `verify_migration_flyway.mjs` 只能做读源码字面量的静态断言 ——
  //   那看不出**分支方向**，把「结构一致才放行」反写成「结构不一致才放行」照样全绿。
  //   现由 `verify_preflight_baseline_core.mjs` 的行为测试锁定。
  const verdict = decidePreflight({
    tableCount, hasHistory,
    configuredVersion: configured.version, maxVersion: maxVer,
    missingTables: diff.missingTables, missingCols: diff.missingCols
  })

  if (verdict.exitCode === 0) {
    console.log('  ' + verdict.reason)
    process.exit(0)
  }

  console.error('  ❌ ' + verdict.reason)
  formatMissingReport(diff.missingTables, diff.missingCols).forEach((l) => console.error('    ' + l))
  console.error('\n  处置（二选一）：')
  console.error('   a) 若该库其实只到某个旧版本：把 SPRING_FLYWAY_BASELINE_VERSION 设为它的真实版本号，')
  console.error('      Flyway 会据此执行其后的迁移；')
  console.error('   b) 若该库确实该是最新版：先补执行缺失的迁移，或从 easychat.sql 重新导库。')
  process.exit(1)
}

main()