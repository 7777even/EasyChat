/**
 * preflight-baseline-check 的**纯逻辑核心**（无 fs / child_process / process 依赖）
 *
 * 抽离理由（docs/system-facts.md §14 遗留 #11，人工决策「投 L3 抽纯核心 + 行为测试」）：
 *   此前 `verify_migration_flyway.mjs` 对 preflight 的断言**全是读源码找字面量**。
 *   静态断言看不出**条件分支的方向**——把
 *     `if (!missingTables.length && !missingCols.length) { 放行 }`
 *   反写成
 *     `if (missingTables.length || missingCols.length) { 放行 }`
 *   四条断言**全部照样通过**，而语义已完全颠倒（结构不符反而放行 = 静默跳过迁移）。
 *
 *   把判定抽成纯函数后，**行为测试不受此限**：方向写反必然导致用例转红。
 *
 * 本文件只做「纯计算」：不读文件、不起进程、不 exit。
 * IO 接线（fs / child_process / process.exit）全部留在 preflight-baseline-check.mjs。
 */

/**
 * 判定的有限种类。新增判定时**必须**同时在此登记，
 * 否则门禁的「kind 穷举自检」会报未登记，避免某条分支被悄悄加了却没被测。
 */
export const DECISION_KINDS = Object.freeze({
  LIB_EMPTY: '库为空',
  HAS_HISTORY: '由版本表记账',
  LEGACY_UPGRADE: '合法的存量库待升级',
  STRUCTURE_MATCH: '结构一致',
  STRUCTURE_MISMATCH: '结构与基线不一致'
})

/**
 * 闸门判定：本脚本唯一职责所在。
 *
 * 语义（与抽离前**逐条等价**，由行为测试锁定）：
 *   · 库为空          → 放行（Flyway 会从零跑迁移，不归本脚本管）
 *   · 已有历史表      → 放行（由版本表负责记账）
 *   · 版本号 != 最大号 → 放行（合法的存量库待升级，由人声明决定跑哪些迁移）
 *   · 声称最新版：
 *       结构一致 → 放行
 *       结构不符 → **拒绝**（否则 Flyway baseline 会静默跳过未执行的迁移）
 *
 * ⚠ 第 4 条最后一行是本文件存在的**全部理由**，其方向性由门禁的方向敏感用例锁定。
 *
 * @param {object} input
 * @param {number} input.tableCount 活库表数量
 * @param {number} input.hasHistory  是否已有 flyway_schema_history
 * @param {number} input.configuredVersion 配置的 baseline-version
 * @param {number} input.maxVersion      仓库内最大迁移编号
 * @param {string[]} input.missingTables 结构比对得出的缺失表
 * @param {string[]} input.missingCols   结构比对得出的缺失列（`表.列`）
 * @returns {{exitCode:number, kind:string, reason:string}}
 */
export function decidePreflight (input) {
  const {
    tableCount,
    hasHistory,
    configuredVersion,
    maxVersion,
    missingTables = [],
    missingCols = []
  } = input || {}

  if (Number(tableCount) === 0) {
    return {
      exitCode: 0,
      kind: DECISION_KINDS.LIB_EMPTY,
      reason: '库为空 → 无需纳管校验，放行。'
    }
  }
  if (Number(hasHistory) > 0) {
    return {
      exitCode: 0,
      kind: DECISION_KINDS.HAS_HISTORY,
      reason: '已有 Flyway 历史表 → 由版本表负责记账，跳过结构比对。'
    }
  }
  if (Number(configuredVersion) !== Number(maxVersion)) {
    return {
      exitCode: 0,
      kind: DECISION_KINDS.LEGACY_UPGRADE,
      reason: `baseline-version(${configuredVersion}) != 最大迁移号(${maxVersion}) → ` +
        '这是「合法的存量库待升级」：由人声明的版本号决定要跑哪些迁移，不做结构比对、不阻断。'
    }
  }
  // 声称自己是最新版 → 结构必须真的与基线一致
  if (!missingTables.length && !missingCols.length) {
    return {
      exitCode: 0,
      kind: DECISION_KINDS.STRUCTURE_MATCH,
      reason: '结构与基线一致 → 放行。'
    }
  }
  return {
    exitCode: 1,
    kind: DECISION_KINDS.STRUCTURE_MISMATCH,
    reason: '该库声称自己是最新版，但结构与 easychat.sql 不一致。' +
      '若此时让 Flyway baseline，它会把未执行的迁移**静默跳过**——正是本项目吃过两次的坑。'
  }
}

/** 缺失明细的展示文案（IO 层用 console.error 逐行打印） */
export function formatMissingReport (missingTables, missingCols) {
  const out = []
  if (missingTables.length) {
    out.push(`缺失的表（${missingTables.length}）：`)
    missingTables.forEach((t) => out.push('  - ' + t))
  }
  if (missingCols.length) {
    out.push(`缺失的列（${missingCols.length}）：`)
    missingCols.forEach((c) => out.push('  - ' + c))
  }
  return out
}

/**
 * 本次要校验的 baseline-version。
 *
 * ⚠ **必须优先读环境变量**（方向性判断，2026-10-04 自查发现）：
 *   运维声明存量库真实版本号的唯一途径就是 `SPRING_FLYWAY_BASELINE_VERSION=N`
 *   （application.properties 里写的是 `${SPRING_FLYWAY_BASELINE_VERSION:12}` 形式）。
 *   若只读文件默认值，一个「实际只到 011」的存量库用 env 声明 9 之后，
 *   本脚本仍会按 12 判定它「声称自己是最新版」并**拒绝启动**——
 *   恰好堵死自己在报错里建议的那条处置路径 a。
 *
 * @param {string|undefined} env      环境变量值
 * @param {string} propsText          application.properties 全文
 * @returns {{version:number, source:string}}
 */
export function configuredBaselineVersion (env, propsText) {
  if (env !== undefined && env !== null && /^\d+$/.test(String(env).trim())) {
    return { version: Number(String(env).trim()), source: '环境变量 SPRING_FLYWAY_BASELINE_VERSION' }
  }
  const m = String(propsText || '').match(/SPRING_FLYWAY_BASELINE_VERSION:(\d+)/)
  return { version: m ? Number(m[1]) : 12, source: 'application.properties 默认值' }
}

/**
 * 仓库内最大迁移编号。
 * @param {string[]} fileNames 仓库根目录的文件名列表
 */
export function maxMigrationVersion (fileNames) {
  const nums = (fileNames || [])
    .filter((f) => /^easychat-migration-\d{3}-.*\.sql$/.test(f))
    .map((f) => Number(f.match(/^easychat-migration-(\d{3})-/)[1]))
  return nums.length ? Math.max(...nums) : 0
}

/**
 * 解析 easychat.sql 基线 → Map<表, Set<列>>
 * 与 verify_schema_drift.mjs 同款解析思路（只取表/列，不比对索引与注释）。
 */
export function parseBaseline (sqlText) {
  const tables = new Map()
  const re = /CREATE\s+TABLE\s+`([^`]+)`\s*\(([\s\S]*?)\n\)\s*ENGINE/gi
  let m
  while ((m = re.exec(String(sqlText || ''))) !== null) {
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

/**
 * 解析 mysql `-N -B` 输出的 `table_name\tcolumn_name` 多行文本 → Map<表, Set<列>>
 */
export function parseLiveColumns (queryOutput) {
  const live = new Map()
  for (const line of String(queryOutput || '').split(/\r?\n/)) {
    const t = line.split('\t')
    if (t.length < 2) continue
    if (!live.has(t[0])) live.set(t[0], new Set())
    live.get(t[0]).add(t[1])
  }
  return live
}

/**
 * 基线 vs 活库 → 缺失的表 / 列。
 * ⚠ 只报「基线有、活库没有」，不报反向（活库多出表不算不一致）。
 */
export function computeDiff (baseline, live) {
  const missingTables = []
  const missingCols = []
  for (const [t, cols] of baseline || new Map()) {
    const lc = (live || new Map()).get(t)
    if (!lc) { missingTables.push(t); continue }
    for (const c of cols) if (!lc.has(c)) missingCols.push(t + '.' + c)
  }
  return { missingTables, missingCols }
}