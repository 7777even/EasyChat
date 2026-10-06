#!/usr/bin/env node
/**
 * verify_sql_concat_guard.mjs —— Mapper XML 禁止 `${}` 字符串拼接
 *
 * 背景（2026-10-06 只读盘点实测）：
 *   AGENTS §6.2-3 明令「禁止字符串拼接 SQL，MyBatis 使用 `#{}` 参数绑定」，
 *   但这条规范**自始至终零机控** —— `verify_mapper_params.mjs` 只审计 `#{...}`
 *   根名与 `@Param` 是否匹配，对 `${}` 一条断言都没有。
 *   结果仓库里有 **17 处** `order by ${query.orderBy}`，其中 **2 个**管理端端点的
 *   `orderBy` 可由请求参数直达（`AdminGroupController#loadGroup` →
 *   `GroupInfoServiceImpl#findListByPage` → `groupInfoMapper.selectList`；
 *   `AdminUserInfoBeautyController#loadBeautyAccountList` →
 *   `UserInfoBeautyServiceImpl#findListByPage` → `userInfoBeautyMapper.selectList`；
 *   两条链上 Controller 与 Service **均未设排序**，而 `BaseParam.orderBy` 是裸 setter，
 *   全仓无 `@InitBinder` / 无白名单 / 无过滤器清洗）。
 *
 *   ⚠️ 端点数曾被高估为 3：`/admin/callLog/loadCallLog` 一度被算进去，
 *   逐层核实后它走的是 `AdminCallLogServiceImpl#loadCallLog` —— 内部
 *   `setOrderBy("cl.id desc")` 已覆盖，且用的是 `callLogReadMapper`
 *   （排序本就是 XML 字面量），**根本没触及 `CallLogMapper`**。
 *   「只读到 Controller 就断言可达」是 AGENTS §2.1 第 12 条点名的反模式，
 *   故在此写明结论的推导链，供后续复核。
 *
 *   佐证这不是过度解读：`CallLogReadMapper.xml` 的注释已写明「不引用 ${query.orderBy}」，
 *   即该类问题**项目自己已识别并单独修过一处**，其余 17 处留存至今。
 *
 * 本门禁做什么：
 *   ① 扫描全部 Mapper XML，剥掉 XML 注释后**不得出现任何 `${`**；
 *   ② 自检：门禁必须能扫到文件、必须能识别 `${}`（防止「解析器空转 → 恒通过」，
 *      AGENTS §2.1 第 14 条：断言通过 ≠ 断言在做事）。
 *
 * ⚠️ 已知不可静态检测项（三字段登记，AGENTS §2.1 第 3 条）：
 *   ① **盲区**：本门禁只看 XML 文本，**无法验证**「某个 `orderBy` 是否真的可从 HTTP 到达」。
 *      一个 `${}` 位于内部构造的 Query 之后（不可从 HTTP 到达）与位于请求绑定之后，
 *      在文本上完全一样 —— 本门禁**一律阻断**，不做「按可达性分级放行」。
 *      理由：分级需要跨 Controller/Service 的可达性推断，正是 §2.1 第 12 条
 *      「禁止用局部证据推断系统行为」点名的高危动作。
 *   ② **兜底手段**：`mutation_sql_concat_guard.cjs` 变异检验 + 活体注入实测
 *      （见 `openspec/changes/2026-10-06-mapper-orderby-sql-injection/tasks.md` 阶段四）。
 *   ③ **兜底实测证据**：待补 —— 门禁接 CI 前必须先有「基线自检通过 + 反例转红」两次实跑记录。
 *
 * ⚠️ 当前状态（2026-10-06）：**本门禁在当前 main 上 exit=1，如实报出 17 处**。
 *   按 AGENTS §2.1 第 1 条「门禁必须先在当前 main 上跑通，才允许接入」，
 *   **修复完成前不得接入 CI / pre-push**，两者必须同批。
 *
 * 用法：node scripts/verify/verify_sql_concat_guard.mjs
 * 退出码：0 全通过 / 1 有 `${}` 拼接或自检失败
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const MAPPER_DIR = resolve(ROOT, 'easychat-java/src/main/resources/com/easychat/mappers')

let pass = 0
let fail = 0
function check (name, cond, detail = '') {
  if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
    fail++
    console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
  }
}

console.log('===== Mapper SQL 拼接守护（禁止 ${}）=====\n')

// ── 0. 自检：解析器本身必须能识别 `${}` ───────────────────────
// 少了这一步，「扫不到任何 DDL → 无违规」会被当成「全部合规」，
// 即 AGENTS §2.1 第 14 条点名的「断言通过 ≠ 断言在做事」。
const PROBE = '<select id="x">order by ${query.orderBy}</select>'
const probeHits = PROBE.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' ')).match(/\$\{/g) || []
check('自检：解析器能识别 ${}（否则下面全是空转通过）', probeHits.length === 1,
  `探针命中 ${probeHits.length} 次`)

// 剥 XML 注释：注释里提到 ${} 是解释，不算违规（AGENTS §2.1 第 7 条）。
// ⚠️ 必须**保留换行**：首版直接 replace 成空串，导致注释后的行号整体前移
//   （实测 UserContactApplyMapper.xml 的违规行被报成 125，实际在 136），
//   报错指不到地方 = 报错没有可操作性。
function stripXmlComments (src) {
  return src.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
}

// ── 1. 扫描全部 Mapper XML ───────────────────────────────────
check('Mapper 目录存在', existsSync(MAPPER_DIR), MAPPER_DIR)

const files = existsSync(MAPPER_DIR)
  ? readdirSync(MAPPER_DIR).filter((f) => f.endsWith('.xml')).sort()
  : []

console.log(`扫描 ${files.length} 个 Mapper XML\n`)

check('至少扫到 10 个 Mapper XML（低于此数说明路径变了，本门禁会形同虚设）',
  files.length >= 10, `实扫 ${files.length} 个`)

const violations = []
let totalDollar = 0
for (const f of files) {
  const raw = readFileSync(join(MAPPER_DIR, f), 'utf8')
  const code = stripXmlComments(raw)
  const lines = code.split(/\r?\n/)
  lines.forEach((l, i) => {
    const hits = l.match(/\$\{/g)
    if (!hits) return
    totalDollar += hits.length
    violations.push(`${f}:${i + 1}  ${l.trim()}`)
  })
}

check(`Mapper XML 中零 ${'${}'} 字符串拼接（实扫到 ${totalDollar} 处）`,
  violations.length === 0,
  violations.length
    ? '以下位置用 ${} 把内容直接拼进 SQL（AGENTS §6.2-3 禁止；应改为 XML 内字面量或 #{}）：\n' +
      violations.map((v) => `           ${v}`).join('\n')
    : '')

// ── 2. 排序白名单 ↔ XML 分支 的双向对账 ──────────────────────
//
// 为什么需要这一节（对冲 ADR-001 的负面后果）：
//   排序能力保留后，「枚举 ↔ XML <when> 分支」成了一个**需要维护的契约**。
//   少一个分支的后果不是报错，而是**该排序项静默失效**（落到 <otherwise> 默认项）——
//   属最难发现的一类退化。故此处双向校验：
//     A. 无悬空引用：<when> 里的枚举名必须真实存在
//     B. 无遗漏分支：多选表的每个枚举项都必须被某个 <when> 引用
//     C. 片段一致：<when>/<otherwise>/裸字面量里的 order by 必须逐字等于枚举项的 sql
//     D. 不串表：<when> 所在 Mapper 必须属于该枚举项的 table
//   单选表（该表只有 1 个排序项）的 XML 是裸字面量而非 <choose>，故 B 不要求其被引用；
//   但 C 仍要求该字面量与枚举一致 —— 由 C3 覆盖。

const SORT_OPTION_JAVA = 'easychat-java/src/main/java/com/easychat/entity/enums/SortOption.java'
check('SortOption.java 存在（排序白名单唯一真源）', existsSync(resolve(ROOT, SORT_OPTION_JAVA)), SORT_OPTION_JAVA)

/** 解析 SortOption 枚举项：常量名 → { name, table, httpField, direction, sql } */
function parseSortOptions () {
  const src = readFileSync(resolve(ROOT, SORT_OPTION_JAVA), 'utf8')
  // 剥注释，避免注释里的示例被当成枚举项
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
  const out = new Map()
  const re = /^\s*([A-Z][A-Z_0-9]*)\("([a-z_]+)",\s*"([A-Za-z]+)",\s*"(asc|desc)",\s*"([^"]+)"\)/gm
  let m
  while ((m = re.exec(code)) !== null) {
    out.set(m[1], { name: m[1], table: m[2], httpField: m[3], direction: m[4], sql: m[5] })
  }
  return out
}

const options = parseSortOptions()
check('解析出 20 个以上排序白名单项（低于此数说明枚举格式变了，需重新评估本门禁）',
  options.size >= 20, `实解析 ${options.size} 个`)

const byTable = new Map()
for (const o of options.values()) {
  if (!byTable.has(o.table)) byTable.set(o.table, [])
  byTable.get(o.table).push(o)
}
const multiTables = new Set([...byTable.entries()].filter(([, v]) => v.length >= 2).map(([k]) => k))
console.log(`   （共 ${byTable.size} 张表：多选 ${multiTables.size} 张 / 单选 ${byTable.size - multiTables.size} 张）`)

const WHEN_RE = /<when[^>]*name\(\)\s*==\s*'([A-Z][A-Z_0-9]*)'[^>]*>\s*order by\s+([^<]+?)\s*<\/when>/g
const OTHERWISE_RE = /<otherwise>\s*order by\s+([^<]+?)\s*<\/otherwise>/g
const CHOOSE_RE = /<choose>[\s\S]*?<\/choose>/g
const BARE_RE = /order by\s+([a-z_]+(?:\s+[a-z_]+)*\s+(?:asc|desc))/gi
// 显式忽略大小写：XML 里既有 `ORDER BY x DESC` 也有 `order by x desc`，
//   大小写只是书写风格，不是两个不同的排序 —— 否则会把同一排序误判成「不在白名单」。
const BARE_RE_CI = /order by\s+([a-z_]+(?:\s+[a-z_]+)*\s+(?:asc|desc))/gi

const dangling = []
const sqlMismatch = []
const wrongTable = []
const referenced = new Set()
const badOtherwise = []
const bareMismatch = []

// 大小写 / 空白折叠：白名单统一小写，XML 里既有 `ORDER BY x DESC` 也有 `order by x desc`，
//   二者是同一个排序。⚠️ 必须定义在使用点之前 —— `const` 无提升，
//   否则门禁自己抛 ReferenceError 整体崩掉（output 无 [FAIL] 行 → 变异脚本会误判「已捕获」）。
const norm = (s) => s.toLowerCase().replace(/\s+/g, ' ').trim()

for (const f of files) {
  const code = stripXmlComments(readFileSync(join(MAPPER_DIR, f), 'utf8'))

  let m
  WHEN_RE.lastIndex = 0
  while ((m = WHEN_RE.exec(code)) !== null) {
    referenced.add(m[1])
    const sql = m[2].trim()
    const o = options.get(m[1])
    if (!o) { dangling.push(`${f}: ${m[1]}`); continue }
    if (o.sql !== sql) sqlMismatch.push(`${f}: ${m[1]} 的分支为「${sql}」，枚举声明为「${o.sql}」`)
    // D. 不串表：Mapper 文件名去后缀转 snake_case 应等于枚举的 table
    const expected = f.replace(/Mapper\.xml$/, '').replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()
    if (expected !== o.table) {
      wrongTable.push(`${f}: 分支 ${m[1]} 属于表 ${o.table}，但写在 ${f}（该文件对应 ${expected}）`)
    }
  }

  OTHERWISE_RE.lastIndex = 0
  while ((m = OTHERWISE_RE.exec(code)) !== null) {
    const sql = m[1].trim()
    if (![...options.values()].some((o) => norm(o.sql) === norm(sql))) {
      badOtherwise.push(`${f}: <otherwise> 的「${sql}」不是任何枚举项的 sql`)
    }
  }

  // 裸字面量：剔除所有 <choose> 块后仍存在的 order by（单选表形态）
  const withoutChoose = code.replace(CHOOSE_RE, '')
  BARE_RE_CI.lastIndex = 0
  while ((m = BARE_RE_CI.exec(withoutChoose)) !== null) {
    const sql = m[1].trim()
    if (![...options.values()].some((o) => norm(o.sql) === norm(sql))) {
      bareMismatch.push(`${f}: 裸字面量「${sql}」不在 SortOption 白名单内（新增排序须先加枚举）`)
    }
  }
}

check('A. 无悬空引用：<when> 里的枚举名都真实存在', dangling.length === 0,
  dangling.length ? dangling.join('\n           ') : '')
// B. 多选表的每个排序项都必须「被覆盖」——覆盖方式有两种：
//    出现在 <when> 里，或其 sql 与该表某个 <otherwise> 相同（即它就是默认项）。
//    ⚠️ 首版只认 <when>，结果把「默认项」误报成「未覆盖」——
//    默认项本来就由 <otherwise> 承担，**规则写错会制造假失败**，这比漏报更糟（会让人去改对的代码）。
const otherwiseSqls = new Set()
for (const f of files) {
  const code = stripXmlComments(readFileSync(join(MAPPER_DIR, f), 'utf8'))
  let mm
  OTHERWISE_RE.lastIndex = 0
  while ((mm = OTHERWISE_RE.exec(code)) !== null) otherwiseSqls.add(norm(mm[1].trim()))
}

const uncovered = []
for (const t of multiTables) {
  for (const o of byTable.get(t)) {
    if (!referenced.has(o.name) && !otherwiseSqls.has(norm(o.sql))) {
      uncovered.push(`${t}: ${o.name}（${o.sql}）既无 <when> 分支，也不是任何 <otherwise> 的值 → 该排序项会静默失效`)
    }
  }
}
check('B. 多选表的每个排序项都被 <when> 或 <otherwise> 覆盖',
  uncovered.length === 0, uncovered.join('\n           '))
check('C1. 分支 SQL 与枚举声明逐字一致（防文档/实现分家）', sqlMismatch.length === 0,
  sqlMismatch.length ? sqlMismatch.join('\n           ') : '')
check('C2. <otherwise> 的排序必须是某个真实枚举项的 sql', badOtherwise.length === 0,
  badOtherwise.length ? badOtherwise.join('\n           ') : '')
check('C3. 裸字面量排序都在 SortOption 白名单内（新增排序须先加枚举）', bareMismatch.length === 0,
  bareMismatch.length ? bareMismatch.join('\n           ') : '')
check('D. 分支不串表：<when> 所在 Mapper 属于该枚举项的表', wrongTable.length === 0,
  wrongTable.length ? wrongTable.join('\n           ') : '')

// ── 汇总 ────────────────────────────────────────────────────
console.log(`\n===== 结论：${pass}/${pass + fail} 通过 =====`)
if (fail > 0) {
  console.log(`失败 ${fail} 项`)
  console.log('提示：排序需求应写死为 XML 内字面量（单选表），或走 SortOption 白名单 +')
  console.log('      <choose> 枚举分支（多选表）；**不要**把调用方输入拼进 SQL。')
  console.log('      新增排序项的顺序：① SortOption 加枚举 → ② 对应 Mapper 加 <when> 分支。')
  process.exit(1)
}
process.exit(0)
