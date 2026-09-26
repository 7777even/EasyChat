<template>
  <div ref="containerRef" class="vl-container" @scroll.passive="onScroll">
    <div class="vl-spacer" :style="{ height: totalHeight + 'px' }">
      <div
        v-for="i in visibleRange"
        :key="keyOf(list[i])"
        class="vl-row"
        :data-mid="keyOf(list[i])"
        :style="{ transform: 'translateY(' + offsets[i] + 'px)' }"
        :ref="(el) => setRowRef(el, keyOf(list[i]))"
      >
        <slot :item="list[i]" :index="i"></slot>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { buildOffsets, findIndexAtOffset, computeVisibleRange } from '@/utils/virtualListCore.mjs'

/**
 * 不定高虚拟滚动列表（自研，不依赖第三方库）
 *
 * 为什么不用「估算高度」方案：聊天气泡高度差异极大（一行文本 ~40px、
 * 图片/视频 ~200px+、引用块 +40px），固定估算会导致滚动条长度失真、
 * 快速拖动错位、上翻加载后位置跳动。故采用：
 *   1) ResizeObserver 实测每条真实高度（按 messageId 持久化）；
 *   2) offsets 前缀和表 + 二分查找定位可视区间；
 *   3) 头部插入时先按当前偏移补偿 scrollTop，待新项测量完成后二次校正。
 */
const props = defineProps({
  list: { type: Array, default: () => [] },
  /** 未测量行的估算高度，仅用于首帧与测量完成前的占位 */
  estimateHeight: { type: Number, default: 72 },
  /** 视口外额外渲染条数，缓解快速滚动白屏 */
  overscan: { type: Number, default: 6 }
})

const emit = defineEmits(['scroll', 'loadMore'])

const containerRef = ref(null)
const scrollTop = ref(0)
const viewportHeight = ref(0)
/** messageId -> 实测高度 */
const heights = ref({})
/** 每次高度变更自增，用于强制依赖重算（heights 为整体替换时也能触发） */
const measureVersion = ref(0)

const keyOf = (item) => (item && item.messageId != null ? item.messageId : item && item.id)

const heightOf = (index) => {
  const item = props.list[index]
  if (!item) return props.estimateHeight
  const h = heights.value[keyOf(item)]
  return h && h > 0 ? h : props.estimateHeight
}

// offsets[i] = 前 i 条的总高度（offsets[0]=0）
// 算法本体抽到 utils/virtualListCore.mjs（纯函数），便于用 Node 单独验证正确性
const offsets = computed(() => {
  measureVersion.value // 显式依赖：heights 变更后强制重算
  return buildOffsets(props.list, heights.value, props.estimateHeight)
})

const totalHeight = computed(() => offsets.value[props.list.length] || 0)

const findIndexAt = (target) => findIndexAtOffset(offsets.value, target)

const visibleRange = computed(() =>
  computeVisibleRange(offsets.value, scrollTop.value, viewportHeight.value, props.overscan)
)

// ── 高度测量 ─────────────────────────────────────────
let ro = null
let onWinResize = null
const rowEls = new Map()

const setRowRef = (el, mid) => {
  if (!el) {
    return
  }
  rowEls.set(mid, el)
  measureEl(el, mid)
  if (ro) {
    ro.observe(el)
  }
}

const measureEl = (el, mid) => {
  if (!el || mid == null) return
  const h = el.offsetHeight
  if (h && h > 0 && heights.value[mid] !== h) {
    heights.value[mid] = h
    measureVersion.value++
  }
}

onMounted(() => {
  const c = containerRef.value
  if (!c) return
  viewportHeight.value = c.clientHeight
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver((entries) => {
      let changed = false
      for (const entry of entries) {
        const mid = entry.target.dataset && entry.target.dataset.mid
        const h = entry.target.offsetHeight
        if (mid && h && h > 0 && heights.value[mid] !== h) {
          heights.value[mid] = h
          changed = true
        }
      }
      if (changed) {
        measureVersion.value++
      }
    })
    rowEls.forEach((el) => ro.observe(el))
  }
  // 窗口缩放会改变视口高度，需同步（否则可见区间算错）
  onWinResize = () => {
    if (containerRef.value) {
      viewportHeight.value = containerRef.value.clientHeight
    }
  }
  window.addEventListener('resize', onWinResize)
})

onBeforeUnmount(() => {
  if (ro) {
    ro.disconnect()
    ro = null
  }
  if (onWinResize) {
    window.removeEventListener('resize', onWinResize)
    onWinResize = null
  }
  rowEls.clear()
})

// ── 滚动 ────────────────────────────────────────────
const onScroll = () => {
  const c = containerRef.value
  if (!c) return
  scrollTop.value = c.scrollTop
  const distanceBottom = totalHeight.value - c.clientHeight - c.scrollTop
  emit('scroll', { scrollTop: c.scrollTop, distanceBottom })
  if (c.scrollTop <= 0 && props.list.length > 0) {
    emit('loadMore')
  }
}

// ── 头部插入（上翻加载历史）后的位置保持 ────────────
// 先按当前偏移补偿，再等新项实测完成后二次校正，避免估算误差造成跳动。
let pendingAnchor = null

watch(
  () => props.list,
  (newList, oldList) => {
    if (!oldList || oldList.length === 0 || !newList || newList.length === 0) return
    const oldFirstId = keyOf(oldList[0])
    if (oldFirstId == null) return
    const newIndex = newList.findIndex((m) => keyOf(m) === oldFirstId)
    // newIndex <= 0：非头部插入（切会话/清空），不补偿
    if (newIndex > 0) {
      const c = containerRef.value
      if (!c) return
      const anchorOffset = c.scrollTop - offsets.value[0]
      nextTick(() => {
        const added = offsets.value[newIndex] - offsets.value[0]
        c.scrollTop = c.scrollTop + added
        // 记录锚点，待新项测量完成后校正真实偏移
        pendingAnchor = { id: oldFirstId, offsetInItem: anchorOffset }
        applyPendingAnchor()
      })
    }
  }
)

const applyPendingAnchor = () => {
  if (!pendingAnchor) return
  const c = containerRef.value
  if (!c) return
  const idx = props.list.findIndex((m) => keyOf(m) === pendingAnchor.id)
  if (idx < 0) return
  const target = offsets.value[idx] + pendingAnchor.offsetInItem
  if (Math.abs(c.scrollTop - target) > 1) {
    c.scrollTop = target
  }
  // 头部项大多已测量后才清除，避免过早结束校正
  if (idx > 0 || heights.value[pendingAnchor.id]) {
    pendingAnchor = null
  }
}

// 高度变化后做一次锚点校正（新项测量完成会改 offsets）
watch(offsets, () => {
  applyPendingAnchor()
  if (pendingScrollIndex.value != null) {
    applyPendingScrollIndex(true)
  }
})

// ── 按 index 定位（供搜索/引用/新消息跳转）──────────
const pendingScrollIndex = ref(null)

const applyPendingScrollIndex = (fromWatch) => {
  const c = containerRef.value
  const idx = pendingScrollIndex.value
  if (!c || idx == null) return
  const itemTop = offsets.value[idx] || 0
  const itemH = heightOf(idx)
  let top = itemTop
  if (scrollAlign.value === 'center') {
    top = itemTop - (c.clientHeight - itemH) / 2
  } else if (scrollAlign.value === 'end') {
    top = itemTop - c.clientHeight + itemH
  }
  c.scrollTop = Math.max(0, top)
  if (fromWatch) {
    // 目标项已实测（或第二次校正）后清除
    if (heights.value[keyOf(props.list[idx])] || scrollRetry.value >= 2) {
      pendingScrollIndex.value = null
      scrollRetry.value = 0
    } else {
      scrollRetry.value++
    }
  } else {
    scrollRetry.value = 0
  }
}

const scrollAlign = ref('start')
const scrollRetry = ref(0)

const scrollToIndex = (index, align = 'start') => {
  const c = containerRef.value
  if (!c || index == null || index < 0) return
  scrollAlign.value = align
  pendingScrollIndex.value = index
  scrollRetry.value = 0
  applyPendingScrollIndex(false)
  // 目标项若未测量，offsets 更新后会再校正一次
  nextTick(() => applyPendingScrollIndex(true))
}

const scrollToBottom = () => {
  const c = containerRef.value
  if (!c) return
  c.scrollTop = totalHeight.value
}

// 会话切换后回到顶部并重置测量缓存以外的状态
const reset = () => {
  const c = containerRef.value
  if (c) c.scrollTop = 0
  scrollTop.value = 0
  pendingAnchor = null
  pendingScrollIndex.value = null
}

defineExpose({
  scrollToIndex,
  scrollToBottom,
  reset,
  getContainer: () => containerRef.value,
  getTotalHeight: () => totalHeight.value
})
</script>

<style lang="scss" scoped>
.vl-container {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
}

.vl-spacer {
  position: relative;
  width: 100%;
}

.vl-row {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  /* flow-root 建立 BFC：让子元素（.message-item）的 margin-bottom 计入本行 offsetHeight，
     否则按偏移表定位会逐条丢失 15px 间距、消息相互重叠 */
  display: flow-root;
  will-change: transform;
}
</style>
