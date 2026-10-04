#!/usr/bin/env node
/**
 * verify_local_db_core.mjs —— 本地 SQLite 访问层的纯 SQL 构造核心校验
 *
 * 背景（docs/system-facts.md §14 遗留 #7 第三段）：
 *   db/ADB.js 在**模块加载时** require('sqlite3') 并调用 init() 建表，
 *   node 中无法 import → 本地缓存层的 SQL 拼装与列映射逻辑**完全不可测**。
 *   而这一层是「静默失效」高发区：列名拼错 / where 条件漏项都不抛异常，
 *   只表现为「数据没写进去」或「写到了不该写的行」。
 *
 *   本脚本 import 抽离出的 dbSqlCore.mjs 做断言。
 *   抽离过程**修正了两个既有缺陷**（由本门禁锁定，否则下次重构会改回去）：
 *     ① where 条件用 `if (paramData[item])` 过滤 → 假值条件被静默丢弃
 *        → 拼出的 WHERE 少条件 → 更新命中范围扩大（改到了别人的行）
 *     ② 字段不在列映射里时被静默丢弃 → 调用方无从得知「写入根本没生效」
 *
 * 用法：node scripts/verify/verify_local_db_core.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { readFileSync } from 'node:fs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

// ⚠️ Windows 上必须转 file:// URL，否则 ESM loader 抛
//    ERR_UNSUPPORTED_ESM_URL_SCHEME，门禁整体崩溃
//    （而只判 exit != 0 的变异脚本会把这误记成「全部捕获」，2026-10-04 踩中）
const imp = async (rel) => import(pathToFileURL(join(ROOT, rel)).href)

const {
  toCamelCase,
  convertDbObj2BizObj,
  convertDbRows2BizObjs,
  buildInsertSql,
  buildUpdateSql,
  buildColumnsMap,
  selectMissingAlters
} = await imp('easychat-front/src/main/db/dbSqlCore.mjs')

let pass = 0
let fail = 0
function check (name, cond, detail = '') {
  if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
    fail++
    console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
  }
}

/**
 * 断言「不抛异常，且结果符合预期」。
 *
 * ⚠ 不能直接 `check('x 不抛', fn() === false)`：
 *   被测代码一旦抛异常，整个门禁进程会在顶层 await 处**死掉**，
 *   后面所有断言都不执行 → 输出里没有任何 [FAIL] 行 →
 *   变异脚本只能判「门禁崩溃」而非「断言失败」，检验随之作废。
 *   实测正是如此（2026-10-04）：「列映射缺失不再安全跳过」这个变异
 *   把门禁整个打崩，丢失了本可获得的判定信号。
 *   故必须 try/catch 兜住，把异常转成一条 FAIL。
 *
 * @param {Function} fn 返回 true 表示断言成立
 */
function checkNoThrow (name, fn) {
  let ok
  let err = null
  try {
    ok = fn() === true
  } catch (e) {
    err = e
    ok = false
  }
  check(name, ok,
    err !== null ? `抛出异常：${String((err && err.message) || err).slice(0, 120)}` : '')
}

console.log('===== 本地 SQLite SQL 构造核心校验 =====\n')

// 真实列映射（对齐 Tables.js 的 chat_session_user）
const CS_COLS = {
  userId: 'user_id',
  contactId: 'contact_id',
  contactType: 'contact_type',
  sessionId: 'session_id',
  status: 'status',
  contactName: 'contact_name',
  lastMessage: 'last_message',
  lastReceiveTime: 'last_receive_time',
  noReadCount: 'no_read_count',
  memberCount: 'member_count',
  topType: 'top_type',
  noDisturb: 'no_disturb',
  draft: 'draft'
}

// ── 1. 列名大小写转换 ────────────────────────────────────────
console.log('=== 1. 列名转换 ===')
check('snake → camel', toCamelCase('no_read_count') === 'noReadCount')
check('单下划线', toCamelCase('user_id') === 'userId')
check('无下划线原样返回', toCamelCase('status') === 'status')
check('连续下划线逐段转换', toCamelCase('a_b_c') === 'aBC')
check('非字符串输入不抛异常', toCamelCase(123) === '123')

const row = { user_id: 'U1', contact_id: 'U2', no_read_count: 3 }
const biz = convertDbObj2BizObj(row)
check('整行转 camelCase', biz.userId === 'U1' && biz.contactId === 'U2' && biz.noReadCount === 3)
check('null 输入返回 null', convertDbObj2BizObj(null) === null)
check('undefined 输入返回 null', convertDbObj2BizObj(undefined) === null)
check('批量转换', convertDbRows2BizObjs([row, row]).length === 2)
check('批量转换非数组返回空数组（防御）', convertDbRows2BizObjs(null).length === 0)
check('批量转换保留原值类型', convertDbRows2BizObjs([{ no_read_count: 0 }])[0].noReadCount === 0)

check('buildColumnsMap 建立 camel→真实列名', buildColumnsMap([{ name: 'no_read_count' }]).noReadCount === 'no_read_count')
check('buildColumnsMap 空输入返回空对象', Object.keys(buildColumnsMap([])).length === 0)
check('buildColumnsMap 跳过畸形行', Object.keys(buildColumnsMap([null, {}, { name: 'user_id' }])).length === 1)

// ── 2. INSERT 构造 ───────────────────────────────────────────
console.log('\n=== 2. INSERT 构造 ===')
const ins = buildInsertSql('insert or ignore into', 'chat_session_user',
  { userId: 'U1', contactId: 'U2', draft: '你好' }, CS_COLS)
check('insert 前缀正确', ins.sql.startsWith('insert or ignore into chat_session_user('))
check('列名为真实 snake_case', ins.sql.includes('user_id,contact_id,draft'))
check('占位符数量与列数一致',
  (ins.sql.match(/\?/g) || []).length === 3, `实际 ${ins.sql}`)
check('参数顺序与列顺序一致', ins.params[0] === 'U1' && ins.params[1] === 'U2' && ins.params[2] === '你好')

// 缺陷②：字段不在列映射中必须被报出来
const insDrop = buildInsertSql('insert or ignore into', 'chat_session_user',
  { userId: 'U1', 不存在的字段: 1, typoField: 2 }, CS_COLS)
check('【缺陷②】不在列映射的字段被列入 dropped（不再静默丢弃）',
  insDrop.dropped.length === 2 && insDrop.dropped.includes('typoField'),
  `实际 dropped=${JSON.stringify(insDrop.dropped)}；旧实现静默丢弃，调用方无从得知写入未生效`)
check('被丢弃的字段不出现在 SQL 中', !insDrop.sql.includes('typoField'))
check('被丢弃的字段不出现在参数中', insDrop.params.length === 1)

// undefined 视为未提供；null 视为显式有效值
const insUndef = buildInsertSql('insert or ignore into', 't', { a: undefined, b: null }, { a: 'a', b: 'b' })
// ⚠ 断言必须**解析列清单**，不能对整条 SQL 做子串匹配：
//   `!sql.includes('a')` 恒为 false —— `insert` 与 `into` 里都有字母 a。
//   （初版就是这么写的，三处 FAIL 中的一处。）
const insCols = (sql) => {
  const m = sql.match(/\(([^)]*)\)\s*values/i)
  return m ? m[1].split(',').map((s) => s.trim()).filter(Boolean) : []
}
check('undefined 字段不参与写入（列清单只含 b）',
  JSON.stringify(insCols(insUndef.sql)) === JSON.stringify(['b']),
  `实际列清单=${JSON.stringify(insCols(insUndef.sql))}`)
check('null 视为显式有效值参与写入', insUndef.params.length === 1 && insUndef.params[0] === null)

const insEmpty = buildInsertSql('insert or ignore into', 't', {}, CS_COLS)
check('空数据不产生悬空 values()', insEmpty.sql.endsWith('values()'))
check('空数据参数数为 0', insEmpty.params.length === 0)

// ── 3. UPDATE 构造 ───────────────────────────────────────────
console.log('\n=== 3. UPDATE 构造 ===')
const upd = buildUpdateSql('chat_session_user',
  { draft: '草稿内容', noDisturb: 1 },
  { userId: 'U1', contactId: 'U2' }, CS_COLS)
check('update 前缀正确', upd.sql.startsWith('update chat_session_user set '))
check('set 子句列名为真实 snake_case', upd.sql.includes('draft = ?') && upd.sql.includes('no_disturb = ?'))
check('where 子句存在', upd.sql.includes('where'))
check('where 用 and 连接两个条件',
  (upd.sql.match(/ where .* and .*/) || []).length === 1, upd.sql)
check('未跳过', upd.skipped === false)
check('参数顺序：先 set 后 where', upd.params.length === 4 &&
  upd.params[0] === '草稿内容' && upd.params[1] === 1 &&
  upd.params[2] === 'U1' && upd.params[3] === 'U2')

// 缺陷①：where 条件用假值时被静默丢弃
const updFalsy = buildUpdateSql('chat_session_user',
  { draft: 'x' },
  { userId: 'U1', contactId: '' }, CS_COLS)
check('【缺陷①】contactId 为空串时 where 条件不得被丢弃',
  updFalsy.sql.includes('contact_id = ?'),
  '旧实现用 `if (paramData[item])` 过滤，空串/0/false 会被静默丢弃，' +
  '拼出的 WHERE 少条件 → 本该命中一行的更新变成改写该用户所有会话')
check('【缺陷①】空串作为条件值时 params 仍包含它',
  updFalsy.params.length === 3 && updFalsy.params[2] === '')

const updZero = buildUpdateSql('chat_session_user', { status: 0 }, { userId: 'U1', contactId: 0 }, CS_COLS)
check('【缺陷①】数值 0 作为条件值不得被丢弃', updZero.sql.includes('contact_id = ?'))

// 短路保护：空 set / 空 where 都必须拒绝执行
const updNoSet = buildUpdateSql('chat_session_user', {}, { userId: 'U1' }, CS_COLS)
check('空 set 子句被跳过（否则拼出 `update t  where` 触发 SQLITE_ERROR 并弹原生框）',
  updNoSet.skipped === true && updNoSet.sql === null)
const updNoWhere = buildUpdateSql('chat_session_user', { draft: 'x' }, {}, CS_COLS)
check('空 where 被跳过（否则退化成全表更新）',
  updNoWhere.skipped === true && updNoWhere.sql === null)
const updAllUndefined = buildUpdateSql('chat_session_user', { draft: undefined }, { userId: 'U1' }, CS_COLS)
check('全部字段为 undefined 时跳过（等同空 set）', updAllUndefined.skipped === true)
check('跳过时给出可读原因',
  updNoSet.reason.includes('set') && updNoWhere.reason.includes('where'))

// where 条件字段不在映射中 → 不得拼出 `undefined = ?`
const updBadCol = buildUpdateSql('chat_session_user', { draft: 'x' }, { userId: 'U1', 乱写字段: 'v' }, CS_COLS)
check('where 字段不在映射时不产生 `undefined = ?`',
  !updBadCol.sql.includes('undefined = ?'), updBadCol.sql)
check('被丢弃的 where 字段列入 dropped',
  updBadCol.dropped.includes('乱写字段'))

// 列映射整体缺失时必须跳过而非崩溃
checkNoThrow('列映射为 undefined 时安全跳过（不抛异常）', () => {
  const r = buildUpdateSql('t', { a: 1 }, { b: 2 }, undefined)
  return r.skipped === true && r.sql === null
})

// set 值本身为 undefined 不参与，但 null 参与
const updNullVal = buildUpdateSql('chat_session_user', { draft: null, lastMessage: 'm' }, { userId: 'U1' }, CS_COLS)
// ⚠ 同样不能对整条 SQL 做子串匹配：`draft` 是**列名**，必然出现在 SQL 里。
//   该判的是「值 undefined 的字段其列名是否缺席」，故必须解析 SET 子句的列清单。
const setCols = (sql) => {
  const m = sql.match(/\bset\s+([\s\S]*?)\s+where\b/i)
  return m ? m[1].split(',').map((s) => s.replace(/=\s*\?$/, '').trim()) : []
}
const updUndefVal = buildUpdateSql('chat_session_user',
  { draft: undefined, lastMessage: 'm' }, { userId: 'U1' }, CS_COLS)
check('set 值为 undefined 的字段不出现在 SET 列清单中',
  !setCols(updUndefVal.sql).includes('draft'),
  `实际 SET 列清单=${JSON.stringify(setCols(updUndefVal.sql))}`)
check('set 值为 null 的字段出现在 SET 列清单中且值为 null',
  setCols(updNullVal.sql).includes('draft') && updNullVal.params[0] === null,
  `实际 SET 列清单=${JSON.stringify(setCols(updNullVal.sql))}，params=${JSON.stringify(updNullVal.params)}`)

// SQL 注入面：列名只允许来自列映射（值一律走 ? 占位）
const updInject = buildUpdateSql('chat_session_user', { draft: "x'; drop table chat_session_user; --" },
  { userId: 'U1' }, CS_COLS)
check('列名不可由值注入（值全部走 ? 占位）',
  updInject.sql.includes('draft = ?') && !updInject.sql.includes('drop table'))
check('注入串作为参数而非 SQL 拼接', updInject.params[0].includes('drop table'))

// ── 4. alter 判定 ────────────────────────────────────────────
console.log('\n=== 4. 存量库补列判定 ===')
const ALTER = [
  { tableName: 'chat_session_user', field: 'no_disturb', sql: 'alter table chat_session_user add column no_disturb integer default 0' },
  { tableName: 'chat_session_user', field: 'draft', sql: 'alter table chat_session_user add column draft varchar' }
]
// 场景：no_disturb 已存在、draft 缺失 → 只补 draft
const qa1 = async (sql) => {
  if (sql.includes('no_disturb') || sql === 'pragma table_info(chat_session_user)') {
    return [{ name: 'user_id' }, { name: 'no_disturb' }, { name: 'status' }]
  }
  return []
}
const pending1 = await selectMissingAlters(ALTER, qa1)
check('只补真正缺失的列（sqlite add column 不幂等，多补即报错）',
  pending1.length === 1 && pending1[0].field === 'draft',
  `实际 pending=${JSON.stringify(pending1.map(p => p.field))}`)

// 场景：pragma 返回空（表尚未建好）→ 全部判定为缺失，但须返回清单而非逐条立即执行
const qaEmpty = async () => []
const pending2 = await selectMissingAlters(ALTER, qaEmpty)
check('pragma 返回空时全部列入待执行（交由调用方统一执行并记录）',
  pending2.length === 2)
check('pragma 返回非数组时不抛异常',
  (await (async () => {
    try {
      return (await selectMissingAlters(ALTER, async () => null)).length === 2
    } catch (e) {
      console.log(`   [FAIL] pragma 返回非数组时不抛异常 | 抛出异常：${String(e && e.message || e).slice(0, 100)}`)
      fail++
      return false
    }
  })()))
check('空 alter 清单返回空数组', (await selectMissingAlters([], qa1)).length === 0)
check('畸形 alter 条目被跳过', (await selectMissingAlters([{}, { tableName: 't' }], qa1)).length === 0)

// ── 5. ADB.js 确实用上了纯核心 ───────────────────────────────
console.log('\n=== 5. ADB.js 确实复用纯核心 ===')
const adbSrc = readFileSync(join(ROOT, 'easychat-front/src/main/db/ADB.js'), 'utf8')
// ⚠ 判「代码里**不该**有某写法」时，必须先剥注释。
//   ADB.js 顶部注释正是在解释缺陷①，原话就含 `if (paramData[item])`——
//   初版直接对全文做子串匹配，于是自己的注释把断言打红了（初版三处 FAIL 之一）。
//   注释里提到某个反模式是**应该的**，代码里出现才是问题。
const adbCode = adbSrc.split('\n').filter((l) => !l.trim().startsWith('//')).join('\n')
check('ADB.js import 了 dbSqlCore', /from ["']\.\/dbSqlCore\.mjs["']/.test(adbSrc))
check('ADB.js 用 buildInsertSql 而非自行拼 INSERT', /buildInsertSql\(/.test(adbSrc))
check('ADB.js 用 buildUpdateSql 而非自行拼 UPDATE', /buildUpdateSql\(/.test(adbSrc))
check('ADB.js 用 buildColumnsMap 建列映射', /buildColumnsMap\(/.test(adbSrc))
check('ADB.js 用 toCamelCase', /toCamelCase/.test(adbSrc))
check('ADB.js 用 convertDbObj2BizObj', /convertDbObj2BizObj/.test(adbSrc))
// 防回归：缺陷①的原始写法不得复活（对**去注释后**的代码判定）
check('ADB.js 不再内联 `if (paramData[item])` 真值过滤',
  !/if\s*\(\s*paramData\[item\]\s*\)/.test(adbCode),
  '真值过滤即缺陷①本身，复活则空串/0 的 where 条件又被静默丢弃')
// 防回归：不得再在循环里逐条立即执行 ALTER
check('ADB.js 不再「查完 pragma 立刻执行 ALTER」（add column 不幂等）',
  !/fieldList\.some\([^)]*\)[\s\S]{0,120}run\(item\.sql/.test(adbCode),
  '查完立即执行会在 pragma 返回空时对每张表都执行 ALTER，第二次启动即 duplicate column')

// ── 汇总 ────────────────────────────────────────────────────
console.log(`\n===== 结论：${pass}/${pass + fail} 通过 =====`)
if (fail) {
  console.log(`失败 ${fail} 项`)
  process.exit(1)
}
process.exit(0)