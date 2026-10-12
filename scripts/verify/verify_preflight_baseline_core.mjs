#!/usr/bin/env node
/**
 * verify_preflight_baseline_core.mjs —— 存量库纳管前置闸门的**行为**测试
 *
 * 背景（docs/system-facts.md §14 遗留 #11，人工决策「投 L3 抽纯核心 + 行为测试」）：
 *   `verify_migration_flyway.mjs` 对 `preflight-baseline-check.mjs` 的断言**全是读源码找字面量**，
 *   静态断言看不出**条件分支的方向**——把放行条件
 *     `if (!missingTables.length && !missingCols.length) { 放行 }`
 *   反写成 `if (missingTables.length || missingCols.length) { 放行 }`
 *   后四条断言**全部照样通过**，而语义已完全颠倒。
 *
 *   本脚本 import 抽离出的 `preflightBaselineCore.mjs` 做**行为**断言，
 *   行为测试**不受静态上限约束**：方向写反必然转红。
 *
 * 用法：node scripts/verify/verify_preflight_baseline_core.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { readFileSync } from 'node:fs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

// ⚠ Windows 上必须转 file:// URL，否则 ESM loader 抛 ERR_UNSUPPORTED_ESM_URL_SCHEME
const imp = async (rel) => import(pathToFileURL(join(ROOT, rel)).href)

const core = await imp('scripts/migrate/preflightBaselineCore.mjs')
const {
  decidePreflight, configuredBaselineVersion, maxMigrationVersion,
  parseBaseline, parseLiveColumns, computeDiff, formatMissingReport,
  DECISION_KINDS
} = core

let pass = 0
let fail = 0
const seenKinds = new Set()
function check (name, cond, detail = '') {
  if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
    fail++; console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
  }
}

const K = DECISION_KINDS
// 「声称自己是最新版」的公共前提
const claimingLatest = {
  tableCount: 20, hasHistory: 0, configuredVersion: 12, maxVersion: 12
}

console.log('== 1. ★ 方向敏感：结构有缺失 → 必须拒绝（这就是本门禁存在的理由）==')
{
  const r = decidePreflight({ ...claimingLatest, missingTables: ['user_info'], missingCols: [] })
  seenKinds.add(r.kind)
  check('缺表 → exit 1', r.exitCode === 1, `实际 exitCode=${r.exitCode} kind=${r.kind}`)
  check('缺表 → kind 为结构不一致', r.kind === K.STRUCTURE_MISMATCH)
  check('★ 缺表绝不可放行（方向写反时此处必红）', r.exitCode !== 0)
}
{
  const r = decidePreflight({ ...claimingLatest, missingTables: [], missingCols: ['user_info.password'] })
  seenKinds.add(r.kind)
  check('缺列 → exit 1', r.exitCode === 1)
  check('缺列 → kind 为结构不一致', r.kind === K.STRUCTURE_MISMATCH)
}

console.log('== 2. ★ 方向敏感：结构完全一致 → 必须放行 ==')
{
  const r = decidePreflight({ ...claimingLatest, missingTables: [], missingCols: [] })
  seenKinds.add(r.kind)
  check('一致 → exit 0', r.exitCode === 0, `实际 exitCode=${r.exitCode} kind=${r.kind}`)
  check('一致 → kind 为结构一致', r.kind === K.STRUCTURE_MATCH)
}

console.log('== 3. 其余放行分支 ==')
{
  const r = decidePreflight({ tableCount: 0, hasHistory: 0, configuredVersion: 12, maxVersion: 12 })
  seenKinds.add(r.kind)
  check('库为空 → exit 0', r.exitCode === 0)
  check('库为空 → kind 正确', r.kind === K.LIB_EMPTY)
}
{
  const r = decidePreflight({ tableCount: 20, hasHistory: 3, configuredVersion: 12, maxVersion: 12 })
  seenKinds.add(r.kind)
  check('已有历史表 → exit 0', r.exitCode === 0)
  check('已有历史表 → kind 正确', r.kind === K.HAS_HISTORY)
}
{
  const r = decidePreflight({ tableCount: 20, hasHistory: 0, configuredVersion: 9, maxVersion: 12 })
  seenKinds.add(r.kind)
  check('版本号 9 != 12 → exit 0（合法的待升级库）', r.exitCode === 0)
  check('版本号不等 → kind 正确', r.kind === K.LEGACY_UPGRADE)
}

console.log('== 4. 判定优先级：库为空优先于结构比对 ==')
{
  // 库为空 + 结构「不一致」→ 仍应放行（无库可比）
  const r = decidePreflight({
    tableCount: 0, hasHistory: 0, configuredVersion: 12, maxVersion: 12,
    missingTables: ['x'], missingCols: ['y.z']
  })
  check('库为空时不因结构差异而拒绝', r.exitCode === 0, `kind=${r.kind}`)
}

console.log('== 5. ★ 方向敏感：baseline-version 必须 env 优先于 properties ==')
{
  const props = 'spring.flyway.baseline-version=${SPRING_FLYWAY_BASELINE_VERSION:12}'
  const r = configuredBaselineVersion('9', props)
  check('env=9 优先于 properties 默认 12', r.version === 9, `实际=${r.version}`)
  check('env 生效时 source 指向环境变量', r.source.includes('环境变量'))
}
{
  const props = 'spring.flyway.baseline-version=${SPRING_FLYWAY_BASELINE_VERSION:12}'
  // env 非法 → 回退 properties
  for (const bad of ['', '  ', 'abc', '9x', '-1']) {
    const r = configuredBaselineVersion(bad, props)
    check(`env 非法(${JSON.stringify(bad)}) → 回退 properties 得 12`, r.version === 12,
      `实际=${r.version}`)
  }
  check('env undefined → 回退 properties',
    configuredBaselineVersion(undefined, props).version === 12)
  check('properties 无该键 → 兜底 12',
    configuredBaselineVersion(undefined, 'foo=bar').version === 12)
}

console.log('== 6. 最大迁移编号 ==')
{
  const files = [
    'easychat-migration-001-a.sql', 'easychat-migration-009-b.sql',
    'easychat-migration-012-c.sql', 'easychat.sql', 'README.md'
  ]
  check('取最大编号 12', maxMigrationVersion(files) === 12)
  check('无迁移文件 → 0', maxMigrationVersion([]) === 0)
  check('非迁移文件被忽略', maxMigrationVersion(['easychat.sql', 'x.txt']) === 0)
}

console.log('== 7. 基线解析 ==')
{
  const sql = [
    'CREATE TABLE `user_info` (',
    '  `user_id` varchar(64) NOT NULL,',
    '  `password` varchar(60) DEFAULT NULL,',
    '  PRIMARY KEY (`user_id`)',
    ') ENGINE=InnoDB;',
    '',
    'CREATE TABLE `chat_message` (',
    '  `id` bigint(20) NOT NULL,',
    '  UNIQUE KEY `uk` (`id`)',
    ') ENGINE=InnoDB;'
  ].join('\n')
  const m = parseBaseline(sql)
  check('解析出 2 张表', m.size === 2, `实际=${m.size}`)
  check('user_info 有 user_id/password 两列且不含主键行',
    m.get('user_info').size === 2 && m.get('user_info').has('password') &&
    !m.get('user_info').has('PRIMARY'))
  check('UNIQUE/KEY 行不被当作列', m.get('chat_message').size === 1)
  check('空文本 → 空 Map', parseBaseline('').size === 0)
}

console.log('== 8. 活库解析 ==')
{
  const out = 'user_info\tuser_id\nuser_info\tpassword\nchat_message\tid\n'
  const m = parseLiveColumns(out)
  check('解析出 2 张表', m.size === 2)
  check('user_info 两列', m.get('user_info').size === 2)
  check('空输出 → 空 Map', parseLiveColumns('').size === 0)
  check('残缺行被忽略', parseLiveColumns('onlytable\n').size === 0)
}

console.log('== 9. 差异计算 ==')
{
  const baseline = new Map([
    ['user_info', new Set(['user_id', 'password'])],
    ['chat_message', new Set(['id'])],
    ['group_info', new Set(['group_id'])]
  ])
  const live = new Map([
    ['user_info', new Set(['user_id'])],       // 缺 password
    ['chat_message', new Set(['id'])]
  ])
  const d = computeDiff(baseline, live)
  check('缺表恰为 group_info', JSON.stringify(d.missingTables) === '["group_info"]',
    JSON.stringify(d.missingTables))
  check('缺列恰为 user_info.password',
    JSON.stringify(d.missingCols) === '["user_info.password"]', JSON.stringify(d.missingCols))
  check('完全一致 → 差异为空',
    (() => { const x = computeDiff(baseline, new Map(baseline)); return !x.missingTables.length && !x.missingCols.length })())
  check('基线为空 → 差异为空',
    (() => { const x = computeDiff(new Map(), live); return !x.missingTables.length && !x.missingCols.length })())
  check('★ 活库多出的表不算缺失（只比基线→活库方向）',
    (() => {
      const extra = new Map(live); extra.set('zzz_extra', new Set(['c']))
      const x = computeDiff(baseline, extra)
      return !x.missingTables.includes('zzz_extra')
    })())
}

console.log('== 10. 缺失明细文案 ==')
{
  const lines = formatMissingReport(['t1', 't2'], ['a.b'])
  check('含表清单标题', lines.some((l) => l.includes('缺失的表（2）')))
  check('含列清单标题', lines.some((l) => l.includes('缺失的列（1）')))
  check('空差异 → 无输出', formatMissingReport([], []).length === 0)
}

console.log('== 11. 空转自检（AGENTS §2.1 第 14 条）==')
{
  const declared = Object.values(DECISION_KINDS)
  const missing = declared.filter((k) => !seenKinds.has(k))
  check(`每个已声明的 kind 都至少被一条用例命中（缺 ${missing.length} 个）`, missing.length === 0,
    missing.length ? '未被覆盖：' + missing.join(', ') : '')
  const unregistered = [...seenKinds].filter((k) => !declared.includes(k))
  check('用例未产生未登记的 kind', unregistered.length === 0, unregistered.join(', '))
}

console.log('== 12. 静态断言：核心必须是纯的（不得引入 IO / exit）==')
{
  // ⚠ 必须**先剥注释再匹配**（AGENTS §2.1 第 7 条）：核心文件顶部的注释里
  //   明确写了「不读文件、不起进程、不 exit」——那是在**解释**约束，不是违反约束。
  //   直接全文匹配会把这段说明判成违规。
  const raw = readFileSync(join(ROOT, 'scripts/migrate/preflightBaselineCore.mjs'), 'utf8')
  const src = raw
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/(?<!:)\/\/.*$/gm, '')
  for (const bad of ['process.exit', "require('fs')", "from 'fs'", "from 'node:fs'",
    'execFileSync', 'node:child_process']) {
    check(`核心不含 ${bad}`, !src.includes(bad))
  }
}

console.log('')
console.log(`结果：${pass} 通过 / ${fail} 失败（判定种类覆盖 ${seenKinds.size}/${Object.values(DECISION_KINDS).length}）`)
if (fail > 0) {
  console.log('[FAIL] 存在失败项')
  process.exit(1)
}
console.log('[PASS] 前置闸门纯核心行为全部通过')
process.exit(0)