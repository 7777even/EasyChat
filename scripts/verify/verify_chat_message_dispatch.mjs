#!/usr/bin/env node
/**
 * verify_chat_message_dispatch.mjs —— 前端消息分发与后端类型枚举对账
 *
 * 背景（openspec/changes/2026-10-04-frontend-vitest-baseline ADR-001）：
 *   本项目反复出现的缺陷模式是「服务端正常、历史消息也拉到，但客户端
 *   条件漏了一项 → 不渲染、不报错」。已真实发生一次：
 *   2026-10-03 走查发现 `Chat.vue:136` 分发条件不含 24/25，
 *   而 `ChatMessageVoice.vue` 是**从未被 import 的死组件**。
 *
 * 为什么是**源码级门禁**而不是组件 mount 测试：
 *   `Chat.vue` 是 180+ 行的视图，挂载它需要连带 stub Layout、路由、
 *   MessageSend、ContextMenu 等十余个依赖，成本远高于收益，
 *   且一旦这些依赖变动测试即假红。
 *   而本条要验的其实是**模板里的条件表达式**——用源码解析更直接、
 *   更快、也更贴近「条件漏项」这一失败模式本身。
 *   （`ChatMessage.vue` 的**二次**分发则由 vitest 真挂载验证，
 *   见 `chat-message-inner-dispatch.spec.js`。）
 *
 * 用法：node scripts/verify/verify_chat_message_dispatch.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const read = (p) => readFileSync(resolve(ROOT, p), 'utf8')

const CHAT_VUE = 'easychat-front/src/renderer/src/views/chat/Chat.vue'
const CHAT_MSG_VUE = 'easychat-front/src/renderer/src/views/chat/ChatMessage.vue'
const ENUM_JAVA = 'easychat-java/src/main/java/com/easychat/entity/enums/MessageTypeEnum.java'
const SVC_JAVA = 'easychat-java/src/main/java/com/easychat/service/impl/ChatMessageServiceImpl.java'

let pass = 0
let fail = 0
function check (name, cond, detail = '') {
  if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
    fail++
    console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
  }
}

console.log('===== 消息分发条件与后端类型枚举对账 =====\n')

// ── 1. 解析后端 MessageTypeEnum ──────────────────────────────
console.log('=== 1. 后端类型枚举 ===')
const enumSrc = read(ENUM_JAVA)
const enumMap = new Map() // type -> NAME
for (const m of enumSrc.matchAll(/^\s*([A-Z_]+)\(\s*(\d+)\s*,/gm)) {
  enumMap.set(Number(m[2]), m[1])
}
check(`枚举解析到类型定义（实际 ${enumMap.size} 个）`, enumMap.size >= 20,
  '解析器可能失效：MessageTypeEnum 的声明格式变了')

// ── 2. 解析落库白名单（只有落库的才会进历史漫游、需要渲染）──────
console.log('\n=== 2. 落库白名单 ===')
const svcSrc = read(SVC_JAVA)
const wlMatch = svcSrc.match(/ArraysUtil\.contains\(new Integer\[\]\{([\s\S]*?)\}\)/)
check('找到落库白名单 ArraysUtil.contains', !!wlMatch)
if (wlMatch) {
  const names = [...wlMatch[1].matchAll(/MessageTypeEnum\.([A-Z_]+)\.getType\(\)/g)].map((m) => m[1])
  check(`白名单解析到枚举项（实际 ${names.length} 个）`, names.length > 0)
  const persisted = new Set()
  for (const n of names) {
    const type = [...enumMap.entries()].find(([, name]) => name === n)?.[0]
    if (type !== undefined) persisted.add(type)
  }
  check('白名单项全部能映射到类型号', persisted.size === names.length,
    `白名单 ${names.length} 项，映射成功 ${persisted.size} 项`)

  // ── 3. Chat.vue 分发条件 vs 落库白名单 ─────────────────────
  console.log('\n=== 3. Chat.vue 分发条件对账 ===')
  const chatSrc = read(CHAT_VUE)
  // 只取 MessageVirtualList 的 default 插槽内的分发分支。
  //
  // ⚠ 截断锚点不能用 `</template>`：插槽里**每个分支自身**都以 </template> 闭合，
  //   用它截断只会拿到第 1 个分支（实测「解析到分发分支 1 个」，
  //   进而误报「24/25 未覆盖」——而 Chat.vue 明明覆盖了）。
  //   正解：从插槽起点截到**关闭 MessageVirtualList 的那个 </template>**，
  //   即取 `#default` 之后的下一个 `</MessageVirtualList>` 之前的模板区。
  const slotStart = chatSrc.indexOf('#default="{ item: data')
  check('定位到消息列表插槽', slotStart > 0)
  const listEnd = slotStart > 0 ? chatSrc.indexOf('</MessageVirtualList>', slotStart) : -1
  check('定位到 MessageVirtualList 结束标签', listEnd > slotStart && listEnd > 0,
    listEnd > 0 ? `在 @${listEnd}` : '未找到；插槽结构可能已变')
  const slot = slotStart > 0 && listEnd > slotStart
    ? chatSrc.slice(slotStart, listEnd)
    : ''
  // 每个 <template v-if="…"><ChildName  → 该 ChildName 覆盖的类型
  const branches = [...slot.matchAll(
    /<template\s+v-if="([\s\S]*?)"\s*>\s*<([A-Za-z0-9]+)/g
  )]
  check(`解析到分发分支（实际 ${branches.length} 个）`, branches.length >= 3,
    '模板结构变了：可能是 v-if 写法调整，也可能分支被删')

  const covered = new Set()
  const table = []
  for (const [, cond, comp] of branches) {
    const types = [...cond.matchAll(/messageType\s*==\s*(\d+)/g)].map((m) => Number(m[1]))
    table.push({ comp, types })
    for (const t of types) covered.add(t)
  }
  for (const row of table) {
    console.log(`        ${row.comp} <- [${row.types.join(', ')}]`)
  }
  check(`解析到被覆盖的类型（实际 ${covered.size} 个）`, covered.size > 0)

  const missing = [...persisted].filter((t) => !covered.has(t)).sort((a, b) => a - b)
  check(
    `落库类型全部被 Chat.vue 分发条件覆盖（白名单 ${persisted.size} 个，已覆盖 ${covered.size} 个）`,
    missing.length === 0,
    missing.length
      ? `未覆盖：${missing.map((t) => `${t}(${enumMap.get(t)})`).join(', ')} —— ` +
        '这些消息会「历史漫游能拉到、界面什么都不显示」，且不抛异常不报错'
      : ''
  )
}

// ── 4. ChatMessage.vue 二次分发（24/25 不得掉进纯文本兜底）─────
console.log('\n=== 4. ChatMessage.vue 二次分发 ===')
const msgSrc = read(CHAT_MSG_VUE)
// 该事故的真实形态：Voice 分支缺失 → 24 落进 v-else-if="!= 5" 的纯文本兜底
check('ChatMessage.vue 存在位置消息分支（messageType==25）',
  /messageType\s*==\s*25/.test(msgSrc))
check('ChatMessage.vue 存在语音消息分支（messageType==24）',
  /messageType\s*==\s*24/.test(msgSrc))
check('ChatMessageVoice 已被 import（不再是死组件）',
  /import\s+ChatMessageVoice\s+from/.test(msgSrc),
  '未 import 即死组件：语音消息会落进 v-else-if="data.messageType != 5" 的纯文本分支，' +
  '渲染出 messageContent 的普通气泡（2026-10-03 实际事故）')
check('ChatMessageLocation 已被 import',
  /import\s+ChatMessageLocation\s+from/.test(msgSrc))
// 纯文本兜底分支必须在三个条件之后，否则会抢走 24/25
const fallbackIdx = msgSrc.indexOf('v-else-if="data.messageType != 5"')
const voiceIdx = msgSrc.search(/ChatMessageVoice[^>]*messageType\s*==\s*24/)
const locIdx = msgSrc.search(/ChatMessageLocation[^>]*messageType\s*==\s*25/)
check('纯文本兜底分支位于 24/25 分支之后（否则会抢走它们）',
  fallbackIdx > 0 && voiceIdx >= 0 && locIdx >= 0 && fallbackIdx > voiceIdx && fallbackIdx > locIdx,
  `voice@${voiceIdx}, location@${locIdx}, fallback@${fallbackIdx}；` +
  '兜底在前会让 24/25 渲染成纯文本')

// ── 汇总 ────────────────────────────────────────────────────
console.log(`\n===== 结论：${pass}/${pass + fail} 通过 =====`)
if (fail) {
  console.log(`失败 ${fail} 项`)
  process.exit(1)
}
process.exit(0)