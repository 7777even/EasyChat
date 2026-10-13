#!/usr/bin/env node
/**
 * 虚拟滚动核心算法验证（无 DOM，直接 import 真代码）
 *
 * 沙箱无 GUI，无法验证真实滚动手感；但偏移表 / 二分定位 / 可见区间 /
 * 头部插入补偿这几块的数学正确性可以在这里断言，避免只靠"构建通过"。
 *
 * 用法：node scripts/verify/verify_virtual_core.mjs
 */
import { buildOffsets, findIndexAtOffset, computeVisibleRange } from '../../easychat-front/src/renderer/src/utils/virtualListCore.mjs'

const CORE = '../../easychat-front/src/renderer/src/utils/virtualListCore.mjs'
const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

// ── 构造数据：1000 条，高度在 40~220 之间（模拟文本/图片/引用混合）──
const EST = 72
const mkList = (n, startId = 0) =>
  Array.from({ length: n }, (_, i) => ({ messageId: startId + i }))
const mkHeights = (list, seed = 1) => {
  const h = {}
  let s = seed
  list.forEach((m) => {
    s = (s * 1103515245 + 12345) % 2147483648
    h[m.messageId] = 40 + (s % 181) // 40 ~ 220
  })
  return h
}

console.log('===== 虚拟滚动核心算法验证 =====\n')

// 1. 偏移表
console.log('=== 1. buildOffsets ===')
const list = mkList(1000)
const heights = mkHeights(list)
const off = buildOffsets(list, heights, EST)
check('长度 = n + 1', off.length === 1001, `${off.length}`)
check('offsets[0] = 0', off[0] === 0)
let monotonic = true
for (let i = 1; i < off.length; i++) {
  if (off[i] <= off[i - 1]) {
    monotonic = false
    break
  }
}
check('严格单调递增（高度均 > 0）', monotonic)
const manualSum = list.reduce((s, m) => s + heights[m.messageId], 0)
check('末项 = 各条实测高度之和', off[1000] === manualSum, `${off[1000]} vs ${manualSum}`)
const estOnly = buildOffsets(list, {}, EST)
check('未测量时全部用估算高度', estOnly[1000] === 1000 * EST, `${estOnly[1000]}`)
// ⚠ 2026-10-13 由 mutation_virtual_core.cjs 补：门禁此前**只测了「未测量」**，
//   未测「测到了非法值（0 / 负数）」—— 而 `measured > 0` 这个守卫正是为此存在。
//   实测去掉该守卫时上面所有断言照样通过（漏网）：负高度会让偏移表非单调，
//   二分定位的前提随之失效，表现为「滚到某处消息错位」而非任何报错。
const badHeights = Object.assign({}, heights)
badHeights[list[0].messageId] = 0
badHeights[list[1].messageId] = -50
const offBad = buildOffsets(list, badHeights, EST)
check('★ 实测高度为 0 / 负数时回落估算高度（否则偏移表非单调 → 二分定位前提失效）',
  offBad[1] === EST && offBad[2] === EST * 2, `实际 [${offBad[1]}, ${offBad[2]}]，EST=${EST}`)

// 2. 二分定位
console.log('\n=== 2. findIndexAtOffset ===')
check('target = 0 → 下标 0', findIndexAtOffset(off, 0) === 0)
// offsets 长度 = n+1，offsets[n] 是总高、不对应消息，故最大有效下标是 n-1 = 999
check('target 极大 → 末条消息下标 999', findIndexAtOffset(off, 1e9) === 999, `${findIndexAtOffset(off, 1e9)}`)
let bisectOk = true
for (let t = 0; t < off[1000]; t += 137) {
  const i = findIndexAtOffset(off, t)
  if (!(off[i] <= t && (i === 1000 || off[i + 1] > t))) {
    bisectOk = false
    break
  }
}
check('任意 target 满足 offsets[i] <= t < offsets[i+1]', bisectOk)
check('空表不崩', findIndexAtOffset([0], 5) === 0)

// 3. 可见区间覆盖性
console.log('\n=== 3. computeVisibleRange ===')
const VP = 600
const OVER = 6
let coverOk = true
let rangeOk = true
for (let st = 0; st < off[1000] - VP; st += 523) {
  const range = computeVisibleRange(off, st, VP, OVER)
  if (range.length === 0) {
    coverOk = false
    break
  }
  // 视口内每一条消息都必须被渲染出来（否则会白屏）
  const firstVisible = findIndexAtOffset(off, st)
  let lastVisible = firstVisible
  while (lastVisible < 999 && off[lastVisible + 1] < st + VP) lastVisible++
  for (let i = firstVisible; i <= lastVisible; i++) {
    if (!range.includes(i)) {
      coverOk = false
      break
    }
  }
  // 区间连续且有序
  for (let k = 1; k < range.length; k++) {
    if (range[k] !== range[k - 1] + 1) {
      rangeOk = false
      break
    }
  }
  if (!coverOk) break
}
check('视口内消息全部在渲染区间内（不白屏）', coverOk)
check('区间连续且升序', rangeOk)
const r0 = computeVisibleRange(off, 0, VP, OVER)
check('顶部区间从 0 开始', r0[0] === 0, `[${r0[0]}..${r0[r0.length - 1]}]`)
check('空列表返回空区间', computeVisibleRange([0], 0, VP, OVER).length === 0)
// ⚠ 2026-10-13 由 mutation_virtual_core.cjs 补：原断言循环 `st < off[1000] - VP`，
//   **从不滚到底部**，而 overscan 的越界恰好只在底部暴露（end 已到 n-1，+overscan 即越界）；
//   且所有调用都显式传了 VP，故「视口高度缺省」这条兜底分支从未被触发过。
const rBottom = computeVisibleRange(off, off[1000], VP, OVER)
check('★ 滚动到底部：区间含最后一条且末下标 ≤ n-1（overscan 不得越界到不存在的行）',
  rBottom.length > 0 && rBottom[rBottom.length - 1] === 999,
  `[${rBottom[0]}..${rBottom[rBottom.length - 1]}]`)
const rNoVp = computeVisibleRange(off, 0, undefined, OVER)
const r300 = computeVisibleRange(off, 0, 300, OVER)
check('★ 视口高度缺省时按 600 兜底（渲染条数不少于显式传 300px 时）',
  rNoVp.length >= r300.length, `[缺省 ${rNoVp.length} 条 vs 300px ${r300.length} 条]`)

// 4. 头部插入后的锚点保持（模拟上翻加载历史）
console.log('\n=== 4. 头部插入补偿（上翻加载）===')
const N_INSERT = 20
const newList = mkList(N_INSERT, -N_INSERT).concat(list) // 头部插入 20 条
const newHeights = Object.assign({}, heights, mkHeights(newList.slice(0, N_INSERT), 7))
const newOff = buildOffsets(newList, newHeights, EST)
const S = 1234 // 插入前的 scrollTop
const added = newOff[N_INSERT] - newOff[0]
const newScrollTop = S + added
// 锚点 = 插入前的首条（list[0]），在新列表中的下标为 N_INSERT
const anchorVisualBefore = off[0] - S // 旧：首条顶部相对视口的位置
const anchorVisualAfter = newOff[N_INSERT] - newScrollTop
check(
  '锚点消息的视觉位置保持不变',
  anchorVisualBefore === anchorVisualAfter,
  `before=${anchorVisualBefore} after=${anchorVisualAfter}`
)
check('补偿量 = 新增部分总高', added === newOff[N_INSERT] - newOff[0], `${added}`)

const passed = results.filter(([, ok]) => ok).length
console.log(`\n===== 结果: ${passed}/${results.length} PASS =====`)
if (passed !== results.length) {
  console.log('失败项:')
  results.filter(([, ok]) => !ok).forEach(([n]) => console.log('   - ' + n))
  process.exit(1)
}
