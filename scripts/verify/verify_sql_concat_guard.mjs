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

// ── 汇总 ────────────────────────────────────────────────────
console.log(`\n===== 结论：${pass}/${pass + fail} 通过 =====`)
if (fail > 0) {
  console.log('提示：排序需求应写死为 XML 内字面量（先例 CallLogReadMapper.xml），')
  console.log('      或改走「列名枚举白名单」，**不要**把调用方输入拼进 SQL。')
  process.exit(1)
}
process.exit(0)
