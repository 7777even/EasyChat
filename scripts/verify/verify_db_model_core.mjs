#!/usr/bin/env node
/**
 * verify_db_model_core.mjs —— 主进程本地 SQLite 层的**查询构造**行为测试
 *
 * 背景（docs/system-facts.md §14 遗留 #7 剩余缺口之二）：
 *   `ADB.js` 在模块加载时 require('sqlite3') 并 init() 建表，
 *   故 4 个 Model 在 node 中 import 即建表 → 完全不可测。
 *   而这一层是「静默失效」高发区：**列名写错 / where 条件漏项 / 参数顺序颠倒都不抛异常**，
 *   只表现为「数据没写进去」或「写到了别人的行」。
 *
 * 本脚本 import 抽离出的 dbModelCore.mjs 做断言，**零新依赖**。
 *
 * 断言的重点刻意放在三类「写错了也不会报错」的形态：
 *   ① 参数数组的**顺序**（颠倒 → 改到别人的行）
 *   ② WHERE 条件**是否齐全**（漏项 → 命中范围扩大）
 *   ③ 动态 SET 子句的**列名映射**（驼峰直接当列名 → SQLite 不报错甚至凭空建列）
 *
 * 用法：node scripts/verify/verify_db_model_core.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { readFileSync } from 'node:fs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

// ⚠ Windows 上必须转 file:// URL，否则 ESM loader 抛 ERR_UNSUPPORTED_ESM_URL_SCHEME
const imp = async (rel) => import(pathToFileURL(join(ROOT, rel)).href)

const core = await imp('easychat-front/src/main/db/dbModelCore.mjs')

let pass = 0
let fail = 0
function check (name, cond, detail = '') {
  if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
    fail++; console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
  }
}

const U = 'U001'

console.log('== 1. chat_message：单条查询 ==')
{
  const e = core.buildExistsMessage(U, 9001)
  check('existsMessage SQL 含 user_id 与 message_id 两条件',
    e.sql.includes('user_id = ?') && e.sql.includes('message_id = ?'))
  check('existsMessage 参数顺序 [userId, messageId]',
    JSON.stringify(e.params) === JSON.stringify([U, 9001]), JSON.stringify(e.params))

  const d = core.buildDelMessage(U, 9001)
  check('delMessage SQL 以 delete 开头且带双条件',
    /^delete from chat_message/.test(d.sql) && d.sql.includes('user_id = ?') && d.sql.includes('message_id = ?'))
  check('delMessage 参数顺序 [userId, messageId]',
    JSON.stringify(d.params) === JSON.stringify([U, 9001]))

  const s = core.buildSelectByMessageId(U, 9001)
  check('selectByMessageId 参数顺序 [messageId, userId]（SQL 中 message_id 在前）',
    JSON.stringify(s.params) === JSON.stringify([9001, U]), JSON.stringify(s.params))
}

console.log('== 2. ★ 搜索条件：漏项即命中范围扩大 ==')
{
  const q = { keyword: 'hi', sendUserId: 'U002', messageType: 2, startTime: 1, endTime: 9 }
  const c = core.buildSearchConditions(q)
  check('五个条件全部生成子句', c.clauses.length === 5, `实际 ${c.clauses.length}`)
  check('子句顺序：content / send_user_id / message_type / send_time>= / send_time<=',
    /message_content like/.test(c.clauses[0]) &&
    /send_user_id = /.test(c.clauses[1]) &&
    /message_type = /.test(c.clauses[2]) &&
    /send_time >= /.test(c.clauses[3]) &&
    /send_time <= /.test(c.clauses[4]))
  check('keyword 被包成 %kw%', c.params[0] === '%hi%', JSON.stringify(c.params[0]))

  // 只给部分条件
  const c2 = core.buildSearchConditions({ messageType: 5 })
  check('只给 messageType → 仅 1 个子句', c2.clauses.length === 1)
  check('只给 messageType → 参数仅 1 项', c2.params.length === 1)

  // 全空
  const c3 = core.buildSearchConditions({})
  check('无任何条件 → 0 子句 0 参数', c3.clauses.length === 0 && c3.params.length === 0)
  check('null 查询不抛异常', core.buildSearchConditions(null).clauses.length === 0)
}

console.log('== 3. ★ 搜索：count 与分页语句 ==')
{
  const q = { keyword: 'hi', messageType: 2 }
  const cnt = core.buildSearchCount(U, 'S1', q)
  check('count 以 select count(1) 开头', cnt.sql.startsWith('select count(1) from chat_message'))
  check('count 参数顺序 [sessionId, userId, keyword, messageType]',
    JSON.stringify(cnt.params) === JSON.stringify(['S1', U, '%hi%', 2]), JSON.stringify(cnt.params))

  const pg = core.buildSearchPage(U, 'S1', q, 20, 10)
  check('分页以 order by send_time desc 结尾', pg.sql.endsWith('order by send_time desc limit ?,?'), pg.sql)
  // ★ 这一条是本节重点：limit ?,? 的参数顺序是 offset、limit，颠倒不报错只取错页
  check('★ 分页参数末尾顺序为 [offset, limit]',
    JSON.stringify(pg.params.slice(-2)) === JSON.stringify([20, 10]), JSON.stringify(pg.params))
  check('分页参数前段与 count 同构',
    JSON.stringify(pg.params.slice(0, 4)) === JSON.stringify(['S1', U, '%hi%', 2]))
  check('★ count 与分页的条件子句完全一致（防两份副本分叉）',
    cnt.sql.split(' where session_id = ? and user_id = ?')[1] ===
    pg.sql.split(' where session_id = ? and user_id = ?')[1].replace(' order by send_time desc limit ?,?', ''))
}

console.log('== 4. ★ chat_session_user：未读数自增 ==')
{
  const r = core.buildUpdateNoReadCount(U, 'C1', 3)
  check('SQL 含 no_read_count = no_read_count+? 自增',
    r.sql.includes('no_read_count = no_read_count+?'))
  check('SQL 含 user_id 与 contact_id 双条件',
    r.sql.includes('user_id=?') && r.sql.includes('contact_id=?'))
  // ★ 参数顺序颠倒 → 改到别人的会话，且不报错
  check('★ 参数顺序 [增量, userId, contactId]',
    JSON.stringify(r.params) === JSON.stringify([3, U, 'C1']), JSON.stringify(r.params))
}

console.log('== 5. chat_session_user：查询与更新 ==')
{
  const l = core.buildSelectUserSessionList(U)
  check('会话列表同时带 user_id 与 status=1',
    l.sql.includes('user_id = ?') && l.sql.includes('status = 1'), l.sql)
  check('★ 漏掉 status 会把已删除会话列出，故断言必须含 status', l.sql.includes('status = 1'))
  check('会话列表参数仅 [userId]', JSON.stringify(l.params) === JSON.stringify([U]))

  const one = core.buildSelectSessionByContactId(U, 'C1')
  check('按 contactId 查询参数 [userId, contactId]',
    JSON.stringify(one.params) === JSON.stringify([U, 'C1']))

  const read = core.buildReadAll(U, 'C1')
  check('标记已读 SET 为 0', read.sql.includes('set no_read_count = 0'))
  check('标记已读参数 [userId, contactId]',
    JSON.stringify(read.params) === JSON.stringify([U, 'C1']))

  const upd = core.buildSessionUpdateInfo({ userId: 'X', contactId: 'C1', contactName: '群A', topType: 1 })
  check('★ 会话更新剔除 userId（否则主键被改写）', upd.userId === null)
  check('★ 会话更新剔除 contactId', upd.contactId === null)
  check('其余字段保留', upd.contactName === '群A' && upd.topType === 1)
  check('不修改入参对象', (() => {
    const src = { userId: 'X', contactId: 'C1' }
    core.buildSessionUpdateInfo(src)
    return src.userId === 'X' && src.contactId === 'C1'
  })())
}

console.log('== 6. ★ 动态 SET 子句：列名映射 ==')
{
  const r = core.buildSessionAttrSet({ lastMessage: 'm', lastReceiveTime: 123, noReadCount: 0 })
  // ★ noReadCount = 0 必须**保留**：「=== undefined 才跳过」，不是真值过滤
  check('★ noReadCount=0 也生成子句（不得被真值过滤丢弃）',
    r.setClauses.some((s) => s.includes('no_read_count')), JSON.stringify(r.setClauses))
  check('三个字段全映射为 snake_case 列名',
    r.setClauses.length === 3 &&
    r.setClauses.some((s) => s.includes('last_message = ?')) &&
    r.setClauses.some((s) => s.includes('last_receive_time = ?')) &&
    r.setClauses.some((s) => s.includes('no_read_count = ?')))
  check('★ 不得出现驼峰列名（SQLite 不报错但会凭空建列）',
    !r.setClauses.some((s) => /[a-z][A-Z]/.test(s)), JSON.stringify(r.setClauses))
  check('参数顺序与子句顺序一致',
    JSON.stringify(r.params) === JSON.stringify(['m', 123, 0]), JSON.stringify(r.params))

  check('undefined 字段被跳过', core.buildSessionAttrSet({ lastMessage: 'm' }).setClauses.length === 1)
  check('空对象 → 0 子句', core.buildSessionAttrSet({}).setClauses.length === 0)
  check('null → 0 子句不抛', core.buildSessionAttrSet(null).setClauses.length === 0)
}

console.log('== 7. 会话属性白名单 ==')
{
  check('白名单恰为 topType/noDisturb/draft',
    JSON.stringify(core.SESSION_USER_ATTR_WHITELIST) === JSON.stringify(['topType', 'noDisturb', 'draft']))
  check('同步字段白名单恰为 3 项',
    core.SESSION_ATTR_FIELDS.length === 3)
}

console.log('== 8. ★ 本地服务端口分配 ==')
{
  check('无记录 → 起始值 10240', core.nextServerPort(null) === 10240)
  check('undefined → 起始值 10240', core.nextServerPort(undefined) === 10240)
  check('已有 10241 → 10242', core.nextServerPort(10241) === 10242)
  check('字符串 "10241" → 10242（DB 读回可能是字符串）', core.nextServerPort('10241') === 10242)
  check('★ 各用户端口互不相同（撞端口会导致 EADDRINUSE）',
    core.nextServerPort(10240) !== core.nextServerPort(10241))
}

console.log('== 9. LaterHandle ==')
{
  // ⚠ LaterHandle 的 SQL 原文是**大写**关键字（与其余 Model 的小写风格不同），
  //   断言必须与原文一致，否则测的是「我以为的样子」而非真实实现。
  const l = core.buildSelectLaterHandleList(U)
  check('按 userId 查列表', l.sql.includes('WHERE user_id = ?'), l.sql)
  check('查列表按 create_time DESC 排序（与提醒时间无关，看的是创建序）',
    l.sql.includes('ORDER BY create_time DESC'), l.sql)
  check('查列表参数 [userId]', JSON.stringify(l.params) === JSON.stringify([U]))
  check('按 id 查单条', core.buildSelectLaterHandleById(5).params[0] === 5)
  check('按 id 删除', /^DELETE FROM later_handle/.test(core.buildDeleteLaterHandle(5).sql))
  check('清理过期用 <（严格早于）', core.buildDeleteExpiredLaterHandle(100).sql.includes('remind_time < ?'))
  check('待提醒用 <=（含等于）', core.buildSelectNeedRemindLaterHandle(100).sql.includes('remind_time <= ?'))

  // ★ 8 个占位符与 8 个列的顺序必须逐一对应
  const row = {
    userId: U, messageId: 9001, sessionId: 'S1', contactId: 'C1',
    contactName: '张三', content: '内容', createTime: 100, remindTime: 200
  }
  const ins = core.buildInsertLaterHandle(row)
  check('插入语句有 8 个占位符',
    (ins.sql.match(/\?/g) || []).length === 8, `实际 ${(ins.sql.match(/\?/g) || []).length}`)
  check('★ 插入参数顺序与列顺序逐一对应',
    JSON.stringify(ins.params) ===
    JSON.stringify([U, 9001, 'S1', 'C1', '张三', '内容', 100, 200]),
    JSON.stringify(ins.params))
  check('★ 缺字段时参数为 undefined（不得静默写成 undefined 字符串）',
    core.buildInsertLaterHandle({ userId: U }).params.length === 8)
}

console.log('== 9b. UserSetting 构造 ==')
{
  check('取全表最大端口不带 where user_id',
    /^select max\(server_port\)/.test(core.buildSelectMaxServerPort().sql) &&
    !core.buildSelectMaxServerPort().sql.includes('where'), core.buildSelectMaxServerPort().sql)
  check('按 userId 取设置行参数 [userId]',
    JSON.stringify(core.buildSelectSettingByUserId(U).params) === JSON.stringify([U]))
  check('取设置参数 [userId]',
    JSON.stringify(core.buildSelectSettingInfo(U).params) === JSON.stringify([U]))

  const inc = core.buildContactNoReadCount(3, U)
  check('有增量时走自增语句', inc.sql.includes('contact_no_read = contact_no_read+?'))
  check('有增量时参数为原值', JSON.stringify(inc.params) === JSON.stringify([3, U]))
  const zero = core.buildContactNoReadCount(0, U)
  check('0 走「置 0」语句（真值判定，非 null 判定）',
    zero.sql.includes('set contact_no_read = ?') && !zero.sql.includes('+?'), zero.sql)
  check('0 时参数写 0', JSON.stringify(zero.params) === JSON.stringify([0, U]))
  const undef = core.buildContactNoReadCount(undefined, U)
  check('undefined 也走「置 0」（既有语义，非 null 判定）',
    JSON.stringify(undef.params) === JSON.stringify([0, U]))
}

console.log('== 10. 纯度：核心不得引入 sqlite3 / IO ==')
{
  const raw = readFileSync(join(ROOT, 'easychat-front/src/main/db/dbModelCore.mjs'), 'utf8')
  // ⚠ 先剥注释再匹配（AGENTS §2.1 第 7 条）：核心顶部注释里解释了「不放执行」，会提到 sqlite3
  const src = raw
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/(?<!:)\/\/.*$/gm, '')
  for (const bad of ["require('sqlite3')", "from 'node:fs'", 'process.exit', 'node:child_process']) {
    check(`核心不含 ${bad}`, !src.includes(bad))
  }
}

console.log('== 11. ★ Model 层不得残留内联 SQL 字面量（构造唯一真源在核心）==')
{
  const MODELS = ['ChatMessageModel.js', 'ChatSessionUserModel.js', 'UserSetting.js', 'LaterHandleModel.js']
  for (const f of MODELS) {
    const raw = readFileSync(join(ROOT, 'easychat-front/src/main/db', f), 'utf8')
    const code = raw
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '')
      .replace(/(?<!:)\/\/.*$/gm, '')
    const hits = code.match(/["'`]\s*(select|update|insert|delete)\s/gi) || []
    check(`${f} 无内联 SQL 字面量`, hits.length === 0,
      hits.length ? `残留 ${hits.length} 处：${hits.slice(0, 3).join(' | ')}` : '')
  }
}

console.log('')
console.log(`结果：${pass} 通过 / ${fail} 失败`)
if (fail > 0) {
  console.log('[FAIL] 存在失败项')
  process.exit(1)
}
console.log('[PASS] 本地 db 层查询构造核心全部通过')
process.exit(0)