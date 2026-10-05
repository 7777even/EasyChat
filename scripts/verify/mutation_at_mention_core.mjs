#!/usr/bin/env node
/**
 * 变异检验：verify_at_mention_core.mjs 是否有判别力
 *
 * 2026-10-04，openspec `2026-10-04-at-mention-pure-core`
 *
 * 按 AGENTS §2.1 第 10 条落实四条前置纪律：
 *   ① 沙箱能真实跑起来吗 —— 被测门禁是纯 node 脚本（无 npm / 无 maven），已实证可跑；
 *      且门禁用 `pathToFileURL` 加载 .mjs，不会抛 ERR_UNSUPPORTED_ESM_URL_SCHEME
 *   ② 汇总行能区分「漏网」与「捕获」吗 —— 分别计数，并单列 `[无效]`
 *      （非 0 退出但输出里没有 [FAIL] 行 = 门禁**崩了**，不算捕获）
 *   ③ 基线自检在最前吗 —— 未变异时门禁必须先跑通，否则后续「捕获」可能只是环境坏了
 *   ④ 锚点与正则对换行敏感吗 —— 纯字符串锚点先归一化 LF 再替换，按原风格写回；
 *      正则一律 `\r?\n`（MessageSend.vue 是 CRLF，atMentionCore.mjs 是 LF）
 *
 * 沙箱策略：**原地临时改 + try/finally 必还原**。
 * 不用「复制到临时目录」—— 本项目教训：复制工程后没有 node_modules，
 * 门禁会因环境缺失而「红」，那不是判别力。
 */

import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const CORE = path.join(ROOT, 'easychat-front/src/renderer/src/utils/atMentionCore.mjs')
const COMP = path.join(ROOT, 'easychat-front/src/renderer/src/views/chat/MessageSend.vue')
const GATE = path.join(ROOT, 'scripts/verify/verify_at_mention_core.mjs')

const pristine = new Map()

function savePristine (file) {
  if (!pristine.has(file)) pristine.set(file, fs.readFileSync(file, 'utf8'))
}

function restoreAll () {
  for (const [file, content] of pristine) {
    fs.writeFileSync(file, content, 'utf8')
  }
}

/** 归一化 LF 后替换，再按该文件原换行风格写回（纪律 ④） */
function edit (file, findLf, replLf) {
  savePristine(file)
  const original = pristine.get(file)
  const eol = original.includes('\r\n') ? '\r\n' : '\n'
  const norm = original.replace(/\r\n/g, '\n')
  if (!norm.includes(findLf)) {
    throw new Error('锚点未命中 ← ' + JSON.stringify(findLf.slice(0, 90)))
  }
  fs.writeFileSync(file, norm.replace(findLf, replLf).split('\n').join(eol), 'utf8')
}

/** 返回 {failed, hasFailLine} —— 两者都要，进���判「捕获」还是「门禁崩了」 */
function runGate () {
  try {
    const out = execFileSync('node', [GATE], { cwd: ROOT, encoding: 'utf8', stdio: 'pipe' })
    return { failed: false, hasFailLine: /\[FAIL\]/.test(out), out }
  } catch (e) {
    const out = String(e.stdout || '') + String(e.stderr || '')
    return { failed: true, hasFailLine: /\[FAIL\]/.test(out), out }
  }
}

const CASES = [
  {
    name: '★ 缺陷 1 复活：filterMembers 去掉 userId 匹配',
    apply () {
      edit(CORE,
        'return name.includes(kw) || uid.includes(kw)',
        'return name.includes(kw)')
    }
  },
  {
    name: '★ 缺陷 2 复活：buildExtraData 去掉 canAtAll 权限条件',
    apply () {
      edit(CORE,
        'if (canAtAll(role) && (atAllEnabled || messageContent.indexOf(AT_ALL_TEXT) >= 0)) {',
        'if (atAllEnabled || messageContent.indexOf(AT_ALL_TEXT) >= 0) {')
    }
  },
  {
    name: '★ 缺陷 3 复活：extraData.atUserIds 不去重',
    apply () {
      edit(CORE,
        'const ids = extractAtUserIds(messageContent)',
        'const ids = (messageContent.match(AT_USER_ID_PATTERN) || []).map((x) => x.substring(1))')
    }
  },
  {
    name: '★ 兜底被删：草稿重发丢 @所有人（只认面板勾选）',
    apply () {
      edit(CORE,
        'if (canAtAll(role) && (atAllEnabled || messageContent.indexOf(AT_ALL_TEXT) >= 0)) {',
        'if (canAtAll(role) && atAllEnabled) {')
    }
  },
  {
    name: '角色判定放宽：普通成员(2)也被放行',
    apply () {
      edit(CORE,
        'return role === AT_ALL_ROLE || role === AT_ADMIN_ROLE',
        'return role !== null && role !== undefined')
    }
  },
  {
    name: '单聊也写入 @ 字段（contactType 守卫被删）',
    apply () {
      edit(CORE,
        'if (contactType === GROUP_CONTACT_TYPE && messageContent) {',
        'if (messageContent) {')
    }
  },
  {
    name: 'ADR-004 失效：组件把过滤逻辑内联回去',
    apply () {
      edit(COMP,
        'const filteredAtMemberList = computed(() =>\n  filterMembers(atMemberList.value, atKeyword.value)\n)',
        'const filteredAtMemberList = computed(() => {\n' +
        '  const kw = (atKeyword.value || \'\').trim().toLowerCase()\n' +
        '  if (!kw) { return atMemberList.value }\n' +
        '  return atMemberList.value.filter((item) => (item.contactName || \'\').toLowerCase().includes(kw))\n' +
        '})')
    }
  },
  {
    name: 'ADR-004 失效：组件内复制第二份 @ 用户 ID 正则',
    apply () {
      edit(COMP,
        'const {proxy} = getCurrentInstance()',
        'const AT_ID_RE = /@(U[A-Za-z0-9]+)/g\nconst {proxy} = getCurrentInstance()')
    }
  },
  {
    name: '组件未把 myGroupRole 传入核心（atAll 恒判无权限）',
    apply () {
      edit(COMP,
        '    role: myGroupRole.value\n  })',
        '    role: 2\n  })')
    }
  }
]

// ── 基线自检（必须最先，且必须绿） ──────────────────────────────
savePristine(CORE)
savePristine(COMP)
const base = runGate()
if (base.failed) {
  console.log('[基线] ✗ 未变异时门禁即失败 —— 环境问题，终止')
  console.log('（不能把环境缺陷记成「门禁有判别力」）')
  if (!base.hasFailLine) console.log('[基线] 且输出无 [FAIL] 行 → 门禁可能崩了而非判定失败')
  process.stdout.write(base.out.slice(0, 2000))
  restoreAll()
  process.exit(1)
}
console.log('[基线] ✓ 未变异时门禁全绿，后续「捕获」可归因于变异本身')

let caught = 0
let escaped = 0
let invalid = 0

try {
  for (const c of CASES) {
    restoreAll()
    try {
      c.apply()
    } catch (e) {
      invalid++
      console.log('[无效] ' + c.name + ' ← ' + e.message)
      continue
    }
    const r = runGate()
    if (!r.failed) {
      escaped++
      console.log('[漏网] ' + c.name + '  ← 门禁仍全绿，该变异无判别力')
    } else if (!r.hasFailLine) {
      invalid++
      console.log('[无效] ' + c.name + '  ← 非 0 退出但无 [FAIL] 行，门禁崩了（不算捕获）')
    } else {
      caught++
      console.log('[捕获] ' + c.name)
    }
  }
} finally {
  restoreAll()
}

console.log('')
console.log(`=== 结论：${caught}/${CASES.length} 个变异被门禁捕获，漏网 ${escaped}，无效 ${invalid} ===`)
if (escaped > 0 || invalid > 0) {
  console.log('✗ 存在无判别力的断言或无效用例')
  process.exit(1)
}
console.log('✓ 门禁有判别力')
