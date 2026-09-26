/**
 * 虚拟滚动核心算法（纯函数，不依赖 DOM / Vue，便于单独验证）
 *
 * 供 views/chat/MessageVirtualList.vue 使用；也供 scripts/verify/verify_virtual_core.mjs 做算法验证。
 */

/**
 * 构建偏移前缀和表：offsets[i] = 前 i 条的总高度（offsets[0] = 0）
 * @param {Array} list 消息列表（按 messageId 取高度）
 * @param {Object} heights messageId -> 实测高度
 * @param {number} estimateHeight 未测量项的估算高度
 * @returns {number[]} 长度为 list.length + 1
 */
export const buildOffsets = (list, heights, estimateHeight) => {
  const n = (list && list.length) || 0
  const arr = new Array(n + 1)
  arr[0] = 0
  for (let i = 0; i < n; i++) {
    const item = list[i]
    const id = item && item.messageId != null ? item.messageId : item && item.id
    const measured = id != null && heights ? heights[id] : 0
    const h = measured && measured > 0 ? measured : estimateHeight
    arr[i + 1] = arr[i] + h
  }
  return arr
}

/**
 * 二分查找：返回最后一个满足 offsets[i] <= target 的下标
 * @param {number[]} offsets
 * @param {number} target 滚动位置 scrollTop
 */
export const findIndexAtOffset = (offsets, target) => {
  const last = offsets.length - 2 // 最后一个有效项下标
  if (last < 0) return 0
  let lo = 0
  let hi = last
  let res = 0
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (offsets[mid] <= target) {
      res = mid
      lo = mid + 1
    } else {
      hi = mid - 1
    }
  }
  return res
}

/**
 * 计算可见区间下标数组（含 overscan 缓冲）
 * @param {number[]} offsets
 * @param {number} scrollTop
 * @param {number} viewportHeight
 * @param {number} overscan
 */
export const computeVisibleRange = (offsets, scrollTop, viewportHeight, overscan) => {
  const n = offsets.length - 1
  if (n <= 0) return []
  const start = Math.max(0, findIndexAtOffset(offsets, scrollTop) - (overscan || 0))
  const bottom = scrollTop + (viewportHeight || 600)
  let end = start
  while (end < n - 1 && offsets[end + 1] < bottom) {
    end++
  }
  end = Math.min(n - 1, end + (overscan || 0))
  const range = []
  for (let i = start; i <= end; i++) {
    range.push(i)
  }
  return range
}
