#!/usr/bin/env node
/**
 * verify_schema_drift.mjs —— 基线与活库的表结构漂移检测
 *
 * 为什么需要（AGENTS §6.4 早已点名「尚未实现」，至今未做）：
 *   `easychat.sql` 是**最新基线**，不代表**存量库**已对齐。这一条已经造成两次真实事故：
 *     ① 2026-10-02 BCrypt 改造只把 `user_info.password` 列宽从 32 改成 60，
 *        存量库仍是 varchar(32) → BCrypt 哈希被截断 → 登录直接 500；
 *     ② `emoji` / `favorite` / `user_status` / `operation_log` 四张表在基线里存在，
 *        存量库根本没有 → 表不存在类 500，且一度掩盖了另外两个真实缺陷。
 *   两者都不是「写错了代码」，而是「改了基线但没迁移、且没人对账」。
 *
 * 判定：
 *   [ERROR] 基线有的表 / 列，活库没有 → 迁移未执行（最典型、最致命）
 *   [ERROR] 列类型不一致（如基线 varchar(60) vs 活库 varchar(32)）→ 列宽/类型漂移
 *   [ERROR] 关键列的「硬不变量」被破坏（如 user_info.password 必须能装下 60 字符 BCrypt）
 *   [WARN] 活库有基线没有的列 → 可能是手工改过库；基线才是真源，需同步或回滚
 *
 * 设计取舍（fail-closed）：
 *   连不上库 → **exit 1**，而不是「跳过算通过」。理由与 MailService 的 fail-closed 同源：
 *   一个「连不上库就悄悄放行」的漂移门禁，等于没有门禁。
 *   需要离线跑时显式加 `--no-live`，此时只跑不依赖活库的静态断言（迁移编号连续性等）。
 *
 * 用法：
 *   node scripts/verify/verify_schema_drift.mjs              # 默认连本机 127.0.0.1:3306/easychat
 *   node scripts/verify/verify_schema_drift.mjs --no-live    # 只跑静态断言
 *   SCHEMA_DB_HOST=127.0.0.1 SCHEMA_DB_PORT=3306 \
 *   SCHEMA_DB_USER=root SCHEMA_DB_PASSWORD=root SCHEMA_DB_NAME=easychat \
 *     node scripts/verify/verify_schema_drift.mjs
 *
 * 退出码：0 无 ERROR；1 有 ERROR（含「连不上库」）
 */
import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { tmpdir } from 'node:os'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const BASELINE = join(ROOT, 'easychat.sql')

const argv = process.argv.slice(2)
const NO_LIVE = argv.includes('--no-live')

const CFG = {
  host: process.env.SCHEMA_DB_HOST || '127.0.0.1',
  port: process.env.SCHEMA_DB_PORT || '3306',
  user: process.env.SCHEMA_DB_USER || 'root',
  password: process.env.SCHEMA_DB_PASSWORD || 'root',
  db: process.env.SCHEMA_DB_NAME || 'easychat'
}

const results = []
const err = (msg, detail) => results.push({ level: 'ERROR', msg, detail })
const warn = (msg, detail) => results.push({ level: 'WARN', msg, detail })
const pass = (msg) => results.push({ level: 'PASS', msg })

// ── 1. 解析 easychat.sql 基线 ─────────────────────────────────
/**
 * 只提取「表 → 列 → 类型」，刻意不比对索引与注释：
 * 索引缺失不影响运行（性能问题），而 Navicat 导出的索引写法千差万别，
 * 一并比对只会制造大量假红。列与类型才是「写了代码却跑不起来」的根因所在。
 */
function parseBaseline(sql) {
  const tables = new Map()
  const re = /CREATE\s+TABLE\s+`([^`]+)`\s*\(([\s\S]*?)\n\)\s*ENGINE/gi
  let m
  while ((m = re.exec(sql)) !== null) {
    const table = m[1]
    const body = m[2]
    const cols = new Map()
    for (const raw of body.split(/\r?\n/)) {
      const line = raw.trim()
      if (!line) continue
      // 跳过约束行：PRIMARY KEY / UNIQUE / KEY / INDEX / CONSTRAINT / FULLTEXT
      if (/^(PRIMARY\s+KEY|UNIQUE|KEY|INDEX|CONSTRAINT|FULLTEXT)\b/i.test(line)) continue
      const cm = line.match(/^`([^`]+)`\s+([a-z]+(?:\s*\([^)]*\))?(?:\s+unsigned)?)/i)
      if (!cm) continue
      cols.set(cm[1], normalizeType(cm[2]))
    }
    tables.set(table, cols)
  }
  return tables
}

/**
 * 类型归一化：抹掉「整数显示宽度」这类**版本差异**造成的假差异。
 *
 * 为什么必须归一：`bigint(20)` 是 MySQL 5.7 的写法，MySQL 8 已移除显示宽度，
 * 同一张表在 5.7（本机 / 开发）与 8.0（docker-compose / CI）上 column_type 天然不同。
 * 不归一就会得到一堆「漂移」，久了没人再看这个门禁的输出——**假红和假绿一样致命**。
 *
 * 保留 `tinyint(1)`：它是 MySQL 对 boolean 的约定写法，8.0 仍保留，抹掉会丢失语义。
 */
function normalizeType(raw) {
  let t = String(raw).trim().toLowerCase().replace(/\s+/g, ' ')
  t = t.replace(/\b(tinyint|smallint|mediumint|int|integer|bigint)\s*\(\s*\d+\s*\)/, (s, k) =>
    k === 'tinyint' && /\(\s*1\s*\)/.test(s) ? 'tinyint(1)' : k === 'integer' ? 'int' : k
  )
  t = t.replace(/\bbit\s*\(\s*\d+\s*\)/, 'bit')
  return t
}

// ── 2. 连库取活库结构 ─────────────────────────────────────────
function mysqlAvailable() {
  // Windows 上 mysql.exe 常不在 PATH；按既有冒烟脚本的约定路径找一次
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

/**
 * 刻意走 mysql CLI 而非引入 node 驱动：
 *   既有 scripts/smoke/*.py 已全部用 mysql CLI，零新依赖是这个仓的一贯取向；
 *   而为一次性结构对账往仓库里加 mysql2 属于 AGENTS §8「生产依赖新增」L4，得不偿失。
 * 用 execFileSync + 参数数组（不是 shell 字符串）可绕开一切引号/代码页问题。
 */
function queryLive(bin) {
  const sql =
    "SELECT table_name, column_name, column_type " +
    "FROM information_schema.columns " +
    `WHERE table_schema='${CFG.db}' ORDER BY table_name, ordinal_position;`
  const out = execFileSync(
    bin,
    [`--host=${CFG.host}`, `--port=${CFG.port}`, `--user=${CFG.user}`,
     `--password=${CFG.password}`, '--default-character-set=utf8mb4',
     '--batch', '--skip-column-names', CFG.db, `--execute=${sql}`],
    { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }
  )
  const tables = new Map()
  for (const line of out.split(/\r?\n/)) {
    const t = line.split('\t')
    if (t.length < 3) continue
    const [table, column, type] = t
    if (!tables.has(table)) tables.set(table, new Map())
    tables.get(table).set(column, normalizeType(type))
  }
  return tables
}

// ── 3. 硬不变量（历史事故点，单独钉死） ──────────────────────
/**
 * 这些不是「漂移」，而是「漂移会造成已发生过的 500」。
 * 单列比对能报出 password 的类型差异，但不会告诉你「这个列必须多宽」——
 * 所以这里把结论直接写成断言，让门禁的输出自带处置建议。
 */
const HARD_INVARIANTS = [
  {
    table: 'user_info',
    column: 'password',
    mustMatch: /^varchar\((\d+)\)$/,
    minWidth: 60,
    why: 'BCrypt 哈希固定 60 字符（$2a$ 开头）。列宽不足会被截断 → 登录抛 Data too long → 500（2026-10-02 真实事故）'
  }
]

// ── 主流程 ───────────────────────────────────────────────────
console.log('=== 基线 ⇄ 活库 表结构漂移检测 ===\n')

if (!existsSync(BASELINE)) {
  console.error(`[schema-drift] 找不到基线文件 ${BASELINE}`)
  process.exit(1)
}

const baselineSql = readFileSync(BASELINE, 'utf8')
const baseline = parseBaseline(baselineSql)
console.log(`基线 easychat.sql：${baseline.size} 张表 / ` +
  `${[...baseline.values()].reduce((n, c) => n + c.size, 0)} 个列\n`)

if (baseline.size === 0) {
  err('未能从 easychat.sql 解析出任何表', '解析器可能与基线文件结构不匹配，请人工核对')
}

/**
 * 解析器自检：`DROP TABLE IF EXISTS` 的每个表名都必须出现在解析结果里。
 *
 * 为什么必须做（这是本门禁最危险的失败模式）：
 *   一旦解析器与基线文件格式失配（关键字拼写、引用符变化等），它会**静默少认表**。
 *   少认的表会变成「活库有、基线没有」→ 降级为 WARN → 门禁照样 exit=0，
 *   却给出「N 张表全对齐」这种**假的全清结论**。
 *   变异检验的变异5 就是这个场景：把一张表的 `CREATE TABLE` 改坏后门禁当时仍报绿。
 *
 * 为什么按**表名**对账而不是按数量对账：
 *   「CREATE TABLE 出现次数 == 解析出的表数」抓不住变异5——破坏关键字的同时
 *   被解析的表也少了一个，两边仍然相等。
 *   而 Navicat 导出的每个 `CREATE TABLE` 前都有一一配对的 `DROP TABLE IF EXISTS`，
 *   DROP 的表名不受 CREATE 关键字损坏影响，因此它能独立地暴露「少认了一张表」。
 */
const dropNames = [...baselineSql.matchAll(/DROP\s+TABLE\s+IF\s+EXISTS\s+`([^`]+)`\s*;/gi)].map((m) => m[1])
const missingFromParse = dropNames.filter((n) => !baseline.has(n))
if (dropNames.length === 0) {
  err('基线中未找到任何 `DROP TABLE IF EXISTS`，无法做解析器自检',
    '若基线改用了其他导出格式，请同步修改 parseBaseline 与本自检')
} else if (missingFromParse.length) {
  err(
    `解析器漏表：以下表出现在基线中但未被解析：${missingFromParse.join(', ')}`,
    '解析器与基线文件格式失配。这会让「漏掉的表」降级成 WARN 而非 ERROR，从而给出假的全清结论。' +
    '请修解析器（verify_schema_drift.mjs 的 parseBaseline）或核对基线文件。'
  )
} else {
  pass(`解析器自检：${dropNames.length} 张表全部由 DROP/CREATE 配对识别，无静默漏表`)
}

// 3.1 硬不变量（不依赖活库）
for (const inv of HARD_INVARIANTS) {
  const cols = baseline.get(inv.table)
  if (!cols) {
    err(`硬不变量：基线缺少表 ${inv.table}`, inv.why)
    continue
  }
  const t = cols.get(inv.column)
  if (!t) {
    err(`硬不变量：基线缺少列 ${inv.table}.${inv.column}`, inv.why)
    continue
  }
  const m = inv.mustMatch.exec(t)
  if (!m || Number(m[1]) < inv.minWidth) {
    err(`硬不变量：${inv.table}.${inv.column} 类型为 ${t}，需 varchar(≥${inv.minWidth})`, inv.why)
  } else {
    pass(`硬不变量：基线 ${inv.table}.${inv.column} = ${t}（≥${inv.minWidth}）`)
  }
}

// 3.2 活库比对
if (NO_LIVE) {
  warn('已指定 --no-live：跳过活库比对', '该模式下只能发现「基线自身的问题」，无法发现「迁移未执行」')
} else {
  const bin = mysqlAvailable()
  if (!bin) {
    err(
      '找不到 mysql 客户端，无法比对活库',
      '本门禁按 fail-closed 处理：连不上库就放行等于没有门禁。' +
      '可用 SCHEMA_MYSQL_BIN 指定 mysql 可执行文件路径，或加 --no-live 显式跳过。'
    )
  } else {
    let live
    try {
      live = queryLive(bin)
    } catch (e) {
      const detail = ((e.stderr || '') + (e.stdout || '') || e.message || '').toString().trim().slice(0, 300)
      err(
        `连库失败（${CFG.user}@${CFG.host}:${CFG.port}/${CFG.db}）`,
        `${detail}\n本门禁按 fail-closed 处理。确认库在跑且账号可读 information_schema；` +
        '或用 SCHEMA_DB_* 环境变量指定连接参数，或加 --no-live 显式跳过。'
      )
      live = null
    }

    if (live) {
      console.log(`活库 ${CFG.db}@${CFG.host}:${CFG.port}：${live.size} 张表 / ` +
        `${[...live.values()].reduce((n, c) => n + c.size, 0)} 个列\n`)

      let missingTable = 0
      let missingCol = 0
      let typeDiff = 0

      for (const [table, baseCols] of baseline) {
        const liveCols = live.get(table)
        if (!liveCols) {
          missingTable++
          err(`活库缺少表 ${table}`, '基线已定义但未建表 → 对应功能一律报「表不存在」类错误')
          continue
        }
        for (const [col, baseType] of baseCols) {
          const liveType = liveCols.get(col)
          if (liveType === undefined) {
            missingCol++
            err(`活库缺少列 ${table}.${col}`, '基线已定义但未 ALTER 出来 → 写入该列的接口会 500')
          } else if (liveType !== baseType) {
            typeDiff++
            err(`列类型漂移 ${table}.${col}`, `基线 ${baseType} vs 活库 ${liveType}`)
          }
        }
      }

      // 活库多出来的列：基线是真源，需同步或回滚
      let extraCol = 0
      for (const [table, liveCols] of live) {
        const baseCols = baseline.get(table)
        if (!baseCols) {
          extraCol++
          warn(`活库存在基线中没有的表 ${table}`, '若为临时表可忽略；否则基线漏了，需同步 easychat.sql')
          continue
        }
        for (const col of liveCols.keys()) {
          if (!baseCols.has(col)) {
            extraCol++
            warn(`活库存在基线中没有的列 ${table}.${col}`, '基线是真源：确认是废弃列（应回滚）还是漏同步（应补基线+迁移）')
          }
        }
      }

      if (missingTable === 0 && missingCol === 0 && typeDiff === 0) {
        pass(`活库与基线一致（${baseline.size} 张表全对齐，无缺表/缺列/类型漂移）`)
      }
      if (extraCol === 0) {
        pass('活库无基线之外的表/列')
      }
    }
  }
}

// ── 4. 迁移脚本编号连续性（静态，离线也能跑） ─────────────────
/**
 * 编号缺口会诱发「撞号」：新迁移随手用一个已存在的号，两份脚本被当成同一份，
 * 运维按编号顺序执行时其中一份被跳过。故静态查一遍。
 */
try {
  const files = readdirSync(ROOT).filter((f) => /^easychat-migration-\d{3}-.*\.sql$/.test(f))
  const nums = files.map((f) => Number(f.match(/^easychat-migration-(\d{3})-/)[1])).sort((a, b) => a - b)
  if (nums.length === 0) {
    // 目录读不到迁移脚本时必须显式 WARN：
    // 若沉默地按「无缺口」处理，本项就成了永远为绿的摆设。
    warn('未找到任何 easychat-migration-<NNN>-*.sql', 'ROOT 解析异常或迁移脚本命名不符，无法校验编号连续性')
  } else {
    const gaps = []
    for (let i = 1; i < nums.length; i++) {
      if (nums[i] !== nums[i - 1] + 1) gaps.push(`${nums[i - 1]} → ${nums[i]}`)
    }
    if (gaps.length) {
      warn(`迁移脚本编号有缺口：${gaps.join(', ')}`,
        '003 为有意保留的退役占位（见 easychat-migration-003-retired.sql），其余缺口需确认是遗漏还是合并')
    } else {
      pass(`迁移脚本编号连续（001 ~ ${String(nums[nums.length - 1]).padStart(3, '0')}，共 ${nums.length} 份）`)
    }
  }
} catch (e) {
  warn('迁移脚本编号检查未能执行', String(e.message || e))
}

// ── 汇总 ─────────────────────────────────────────────────────
const errorCount = results.filter((r) => r.level === 'ERROR').length
const warnCount = results.filter((r) => r.level === 'WARN').length

console.log('')
for (const r of results) {
  console.log(`   [${r.level}] ${r.msg}${r.detail ? `\n          → ${r.detail}` : ''}`)
}
console.log(`\n===== 结论：${errorCount} ERROR / ${warnCount} WARN =====`)
if (errorCount) {
  console.log('')
  console.log('处置指引：')
  console.log('  · 缺表 / 缺列 → 找出对应的 easychat-migration-<NNN>-*.sql 并在目标库执行；')
  console.log('               若找不到，说明该变更「只改了基线没写迁移」，需补写迁移脚本（AGENTS §6.4）。')
  console.log('  · 类型漂移   → 同上；列宽类漂移须评估存量数据（ALTER 前确认不会截断已有值）。')
  console.log('  · 硬不变量   → 直接对应已发生过的线上 500，优先级最高。')
}
process.exit(errorCount ? 1 : 0)