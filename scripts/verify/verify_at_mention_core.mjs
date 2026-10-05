#!/usr/bin/env node
/**
 * 门禁：群聊 @ 提及判定核心（atMentionCore.mjs）
 *
 * 2026-10-04，openspec `2026-10-04-at-mention-pure-core`
 *
 * 覆盖两类断言：
 *   A. 核心的**行为**不变量（实际 import 并调用，不靠读源码猜）
 *   B. `MessageSend.vue` **确实复用**纯核心、且未把判定内联回去（ADR-004）
 *
 * ⚠️ B 类断言必须**先剥注释**再匹配（AGENTS §2.1 第 7 条）：
 *   本仓注释里大量**引用**反模式原文作为说明，若不剥会把说明判成违规。
 *
 * ⚠️ 本脚本自身的三条纪律（AGENTS §2.1 第 10 / 8 条）：
 *   ① Windows 上 import 绝对路径必须用 `pathToFileURL`，否则抛
 *      `ERR_UNSUPPORTED_ESM_URL_SCHEME`，门禁**根本没跑起来**就退出，
 *      而变异脚本只判 exit≠0 会记成「捕获」→ 100% 假通过
 *   ② 正则一律 `\r?\n`（仓库文件是 CRLF）
 *   ③ 判「某标识符是否出现」用**词边界**，不用裸 `includes`（`sql.includes('a')`
 *      对 `insert or ignore into` 恒为 true 这类坑）
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const CORE = path.join(ROOT, 'easychat-front/src/renderer/src/utils/atMentionCore.mjs')
const COMPONENT = path.join(ROOT, 'easychat-front/src/renderer/src/views/chat/MessageSend.vue')

let pass = 0
const fails = []

function check (name, fn) {
  try {
    const r = fn()
    if (r === true || r === undefined) {
      pass++
    } else {
      fails.push(`${name} → ${r}`)
    }
  } catch (e) {
    fails.push(`${name} → 抛异常：${e.message}`)
  }
}

/** 剥掉 // 与 /* *​/ 注释，避免把「说明里引用的反模式」判成违规 */
function stripComments (src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
}

/** 词边界匹配：避免 `AT_ALL` 命中 `AT_ALL_TEXT` */
function hasWord (src, word) {
  return new RegExp(`\\b${word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(src)
}

const coreSrc = fs.readFileSync(CORE, 'utf8')
const compRaw = fs.readFileSync(COMPONENT, 'utf8')
// ⚠️ 两个源码都要剥注释后再做「不得包含」类断言。
//   本门禁首版正是在这里踩了 AGENTS §2.1 第 7 条：核心的 JSDoc 里写着
//   「本模块不 import electron / window / **axios** / store」这句说明，
//   B6 于是把自己的注释判成了违规。**注释里引用反模式是应该的**（那是解释）。
const coreCode = stripComments(coreSrc)
const compCode = stripComments(compRaw)

const core = await import(pathToFileURL(CORE).href)

// ════════════════════════════════════════════════════════════════
// A. 核心行为不变量
// ════════════════════════════════════════════════════════════════

check('A1 canAtAll 仅 群主(0)/管理员(1) 为真', () => {
  if (!core.canAtAll(0) || !core.canAtAll(1)) return '0/1 应为 true'
  for (const r of [2, 3, null, undefined, '0', NaN]) {
    if (core.canAtAll(r)) return `角色 ${String(r)} 不应被放行`
  }
  return true
})

check('A2 roleText 映射正确且未知值兜底为「成员」', () => {
  const map = { 0: '群主', 1: '管理员', 2: '成员' }
  for (const [k, v] of Object.entries(map)) {
    if (core.roleText(Number(k)) !== v) return `roleText(${k}) 应为 ${v}`
  }
  for (const r of [9, null, undefined, 'x']) {
    if (core.roleText(r) !== '成员') return `roleText(${String(r)}) 应兜底为「成员」`
  }
  return true
})

check('A3 ★ filterMembers 同时匹配昵称与 userId（缺陷 1 修复）', () => {
  const list = [
    { userId: 'U001', contactName: '张三' },
    { userId: 'U002', contactName: '' },
    { userId: 'U003', contactName: 'LiSi' }
  ]
  const byId = core.filterMembers(list, 'U002').map((i) => i.userId)
  if (byId.length !== 1 || byId[0] !== 'U002') {
    return `按 userId 应命中 U002，实际 ${JSON.stringify(byId)}（未设昵称者搜不到 = 缺陷 1 复活）`
  }
  const byName = core.filterMembers(list, '张').map((i) => i.userId)
  if (byName.length !== 1 || byName[0] !== 'U001') return `按昵称搜索失效：${JSON.stringify(byName)}`
  const byCase = core.filterMembers(list, '  LIS  ').map((i) => i.userId)
  if (byCase.length !== 1 || byCase[0] !== 'U003') return '大小写/空白不敏感失效'
  return true
})

check('A4 filterMembers 空关键词返回全部，null 列表不抛', () => {
  const list = [{ userId: 'U1', contactName: 'a' }]
  for (const kw of ['', null, undefined, '   ']) {
    if (core.filterMembers(list, kw).length !== 1) return `空关键词(${String(kw)}) 应返回全部`
  }
  if (!Array.isArray(core.filterMembers(null, 'x'))) return 'null 列表应返回数组'
  if (!Array.isArray(core.filterMembers(undefined, ''))) return 'undefined 列表应返回数组'
  return true
})

check('A5 spliceAtText 光标处插入与选区替换', () => {
  if (core.spliceAtText('ABCD', 'XY', 2, 2).content !== 'ABXYCD') return '插入位置错误'
  if (core.spliceAtText('ABCD', 'XY', 2, 2).cursor !== 4) return '光标位置错误'
  if (core.spliceAtText('ABCD', 'Z', 1, 3).content !== 'AZD') return '选区替换错误'
  const rev = core.spliceAtText('ABCD', 'X', 3, 1)
  if (rev.content !== 'AXD' || rev.cursor !== 2) {
    return `start>end 未归一化：${rev.content}/${rev.cursor}（会把选区文本复制一份）`
  }
  if (core.spliceAtText('AB', 'X', 99, 99).content !== 'ABX') return '越界未钳制'
  if (core.spliceAtText('AB', 'X', -5, -5).content !== 'XAB') return '负数未钳制'
  return true
})

check('A6 extractAtUserIds 提取并去重', () => {
  const ids = core.extractAtUserIds('@U001 和 @U002 和 @U001').sort()
  if (ids.length !== 2) return `应去重为 2 项，实际 ${JSON.stringify(ids)}`
  if (core.extractAtUserIds('@someone').length !== 0) return '非 U 开头不应提取'
  for (const e of ['', null, undefined]) {
    if (core.extractAtUserIds(e).length !== 0) return `空输入 ${String(e)} 应返回空数组`
  }
  return true
})

check('A7 ★ atAll 写入必须叠加角色权限（缺陷 2 修复）', () => {
  const mk = (role, content, enabled = false) =>
    JSON.parse(core.buildExtraData({ contactType: 1, messageContent: content, atAllEnabled: enabled, role }) || '{}')

  if (mk(2, '大家好 @所有人').atAll !== undefined) {
    return '普通成员(2)手工键入 @所有人 不应写 atAll（会被服务端 CODE_2305 拒绝）'
  }
  if (mk(2, '大家好', true).atAll !== undefined) {
    return '普通成员(2)面板勾选也不应写 atAll'
  }
  if (mk(undefined, '大家好 @所有人').atAll !== undefined) {
    return '角色未知时应保守视为无权限'
  }
  if (mk(0, '大家好', true).atAll !== true) return '群主(0)勾选应写 atAll'
  return true
})

check('A8 ★ 兜底分支保留：管理员草稿重发仍写 atAll', () => {
  // 草稿只存文本，atAllEnabled 是运行时 ref 不持久化 ⇒ 必须靠「正文含 @所有人」兜底。
  // 若为修缺陷 2 而删掉兜底，草稿重发会丢 @所有人。
  const json = core.buildExtraData({
    contactType: 1, messageContent: '大家好 @所有人', atAllEnabled: false, role: 1
  })
  if (JSON.parse(json || '{}').atAll !== true) {
    return '管理员草稿重发（atAllEnabled 丢失）应仍写 atAll'
  }
  return true
})

check('A9 ★ extraData.atUserIds 与 atUserIds 字段口径一致且去重（缺陷 3 修复）', () => {
  for (const content of ['@U001 @U001', '@U001 @U002 @U001', '@U001 文本 @U002 @U002']) {
    const extra = JSON.parse(core.buildExtraData({ contactType: 1, messageContent: content }) || '{}')
    const field = core.buildAtUserIdsField(content)
    const fromExtra = extra.atUserIds || []
    const fromField = field ? field.split(',') : []
    if (fromExtra.join(',') !== fromField.join(',')) {
      return `两处口径分叉：extraData=[${fromExtra}] vs 字段="${field}"（content=${content}）`
    }
    if (new Set(fromExtra).size !== fromExtra.length) {
      return `extraData.atUserIds 含重复项：${JSON.stringify(fromExtra)}`
    }
  }
  return true
})

check('A10 单聊不夹带任何 @ 字段', () => {
  const json = core.buildExtraData({ contactType: 0, messageContent: '@所有人 @U001', atAllEnabled: true })
  if (json !== null) return `单聊无引用时应返回 null，实际 ${json}`
  const withQuote = JSON.parse(core.buildExtraData({
    contactType: 0, messageContent: '@所有人 @U001', atAllEnabled: true,
    quoteInfo: { messageId: 'M1', quoteContent: 'C', quoteNickName: 'N' }
  }))
  const keys = Object.keys(withQuote).sort().join(',')
  if (keys !== 'quoteContent,quoteId,quoteNickName') return `单聊夹带了 @ 字段：${keys}`
  return true
})

check('A11 buildAtUserIdsField 无匹配返回 null', () => {
  for (const c of ['没有提及', '', null, undefined]) {
    if (core.buildAtUserIdsField(c) !== null) return `${String(c)} 应返回 null`
  }
  return true
})

// ════════════════════════════════════════════════════════════════
// B. 组件确实复用纯核心（ADR-004）
// ════════════════════════════════════════════════════════════════

check('B1 组件 import 了纯核心', () => {
  if (!/@\/utils\/atMentionCore\.mjs/.test(compCode)) return '未 import atMentionCore.mjs'
  return true
})

check('B2 ★ 组件内不得残留 @ 用户 ID 正则（缺陷 3 分叉隐患）', () => {
  if (/@\(U\[A-Za-z0-9\]/.test(compCode)) {
    return '组件内仍有一份 @ 用户 ID 正则 —— 与核心分叉，缺陷 3 会原地复活'
  }
  return true
})

check('B3 ★ 组件内不得内联角色权限判定', () => {
  // 抽离前形态：`myGroupRole.value === AT_ALL_ROLE || myGroupRole.value === AT_ADMIN_ROLE`
  if (/myGroupRole\.value\s*===/.test(compCode)) return '组件内仍内联角色判定'
  if (/===?\s*0\s*\|\|\s*myGroupRole/.test(compCode)) return '组件内仍内联角色判定'
  return true
})

check('B4 ★ 组件内不得内联成员过滤（缺陷 1 会原地复活）', () => {
  if (/contactName\s*\|\|\s*''\)\s*\.toLowerCase/.test(compCode)) {
    return '组件内仍内联按 contactName 的过滤 —— 缺陷 1 会原地复活'
  }
  if (/\.filter\(\(item\)\s*=>/.test(compCode)) {
    return '组件内仍有对成员的 filter —— 应委托 filterMembers'
  }
  return true
})

check('B5 ★ 正则在核心中只出现一处', () => {
  const n = (coreCode.match(/@\(U\[A-Za-z0-9\]/g) || []).length
  if (n !== 1) return `核心中该正则出现 ${n} 次，应恰好 1 次`
  return true
})

check('B6 核心不 import electron / window / axios / store（可被 node 直接加载）', () => {
  for (const bad of ['from \'electron\'', 'from "electron"', 'window.', 'axios', '@/stores/', 'proxy.']) {
    if (coreCode.includes(bad)) return `核心含环境依赖：${bad}（node 门禁将无法 import）`
  }
  return true
})

check('B7 组件把 myGroupRole 传入 buildExtraData（权限判定需要角色）', () => {
  if (!/role:\s*myGroupRole\.value/.test(compCode)) {
    return '组件未把 myGroupRole 传入核心 —— atAll 权限判定会因 role=undefined 恒为无权限'
  }
  return true
})

check('B8 剥注释后仍能命中（守卫剥注释逻辑本身有效）', () => {
  // 若 stripComments 失效，B2~B4 会被注释里的反模式原文误判为违规。
  // 反之若它剥得太狠，把真实代码也剥掉，B2~B4 就会假绿。
  // 故做一次自检：注释里的反模式应被剥掉，真实代码应保留。
  if (hasWord(compCode, 'buildExtraData') === false) return '剥注释后连 buildExtraData 都不见了，剥过头了'
  if (/\.slice\(0,\s*start\)/.test(compCode)) return '组件内仍残留 slice 拼接，光标插入未委托核心'
  return true
})

// ════════════════════════════════════════════════════════════════

console.log('\n[verify_at_mention_core] 结论：' + (fails.length === 0 ? '通过' : '失败'))
console.log(`  ${pass} 项通过 / ${fails.length} 项失败`)
if (fails.length > 0) {
  for (const f of fails) console.log('  [FAIL] ' + f)
  process.exit(1)
}
