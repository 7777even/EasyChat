import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MessageVirtualList from '@/views/chat/MessageVirtualList.vue'

/**
 * 不定高虚拟滚动列表的**真实挂载**测试（DOM 级）。
 *
 * 算法本体（buildOffsets / findIndexAtOffset / computeVisibleRange）由
 * `scripts/verify/verify_virtual_core.mjs` 门禁覆盖；本 spec 只测组件层接线：
 *   1. 可见区间渲染（jsdom clientHeight=0 → core 兜底 600 视口）+ spacer 总高
 *   2. scroll → emit scroll（distanceBottom）；scrollTop<=0 → emit loadMore
 *   3. scrollToIndex / scrollToBottom / reset 三个暴露方法的 DOM 效果
 *   4. 头部插入（上翻历史）→ scrollTop 补偿
 */

function makeList (n) {
  return Array.from({ length: n }, (_, i) => ({
    messageId: i + 1,
    messageContent: `msg-${i + 1}`
  }))
}

function mountList (props = {}) {
  const wrapper = mount(MessageVirtualList, {
    props: {
      list: makeList(100),
      estimateHeight: 72,
      overscan: 6,
      ...props
    },
    slots: {
      default: `<template #default="{ item, index }">
        <div class="message-item">{{ index }}:{{ item.messageContent }}</div>
      </template>`
    }
  })
  return wrapper
}

describe('MessageVirtualList.vue 真实挂载（DOM 级）', () => {
  it('按可见区间渲染行（视口 600 兜底 + overscan），spacer 总高 = n × 估算高度', () => {
    const wrapper = mountList()
    // offsets[8]=576 < 600、offsets[9]=648 ≥ 600 → end=8，+overscan 6 → 0..14
    const rows = wrapper.findAll('.vl-row')
    expect(rows).toHaveLength(15)
    expect(rows[0].attributes('data-mid')).toBe('1')
    expect(rows[14].attributes('data-mid')).toBe('15')
    // 未测量行（jsdom offsetHeight=0）全用估算高度
    expect(wrapper.find('.vl-spacer').attributes('style')).toBe('height: 7200px;')
    expect(wrapper.find('.message-item').text()).toBe('0:msg-1')
  })

  it('list 为空 → 无行且总高 0', () => {
    const wrapper = mountList({ list: [] })
    expect(wrapper.findAll('.vl-row')).toHaveLength(0)
    expect(wrapper.find('.vl-spacer').attributes('style')).toBe('height: 0px;')
  })

  it('scroll 事件 → emit scroll 带 scrollTop/distanceBottom', async () => {
    const wrapper = mountList()
    const el = wrapper.find('.vl-container').element
    // jsdom 不做布局，手动赋值 scrollTop/clientHeight 模拟滚动位置
    Object.defineProperty(el, 'clientHeight', { value: 600, configurable: true })
    Object.defineProperty(el, 'scrollTop', { value: 1000, writable: true, configurable: true })

    await wrapper.find('.vl-container').trigger('scroll')
    const evt = wrapper.emitted('scroll')
    expect(evt).toHaveLength(1)
    expect(evt[0][0]).toEqual({
      scrollTop: 1000,
      // 7200 - 600 - 1000 = 5600
      distanceBottom: 5600
    })
    // 未滚到顶 → 不触发 loadMore
    expect(wrapper.emitted('loadMore')).toBeUndefined()
  })

  it('scrollTop=0 且列表非空 → emit loadMore（上翻加载）', async () => {
    const wrapper = mountList()
    const el = wrapper.find('.vl-container').element
    Object.defineProperty(el, 'scrollTop', { value: 0, writable: true, configurable: true })
    await wrapper.find('.vl-container').trigger('scroll')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)
  })

  it('scrollTop=0 但列表为空 → 不 emit loadMore', async () => {
    const wrapper = mountList({ list: [] })
    const el = wrapper.find('.vl-container').element
    Object.defineProperty(el, 'scrollTop', { value: 0, writable: true, configurable: true })
    await wrapper.find('.vl-container').trigger('scroll')
    expect(wrapper.emitted('loadMore')).toBeUndefined()
  })

  it('scrollToIndex(index, start) → 容器 scrollTop 对齐该项偏移', async () => {
    const wrapper = mountList()
    const el = wrapper.find('.vl-container').element
    Object.defineProperty(el, 'clientHeight', { value: 600, configurable: true })
    Object.defineProperty(el, 'scrollTop', { value: 0, writable: true, configurable: true })

    wrapper.vm.scrollToIndex(10, 'start')
    await wrapper.vm.$nextTick()
    // 未测量行 → 10 × 72 = 720
    expect(el.scrollTop).toBe(720)
  })

  it('scrollToIndex(index, center) → 居中对齐（itemTop - (视口-行高)/2）', async () => {
    const wrapper = mountList()
    const el = wrapper.find('.vl-container').element
    Object.defineProperty(el, 'clientHeight', { value: 600, configurable: true })
    Object.defineProperty(el, 'scrollTop', { value: 0, writable: true, configurable: true })

    wrapper.vm.scrollToIndex(10, 'center')
    await wrapper.vm.$nextTick()
    // 720 - (600 - 72) / 2 = 456
    expect(el.scrollTop).toBe(456)
  })

  it('scrollToIndex 越界/空列表 → 不动', async () => {
    const wrapper = mountList()
    const el = wrapper.find('.vl-container').element
    Object.defineProperty(el, 'scrollTop', { value: 0, writable: true, configurable: true })
    wrapper.vm.scrollToIndex(-1)
    wrapper.vm.scrollToIndex(null)
    await wrapper.vm.$nextTick()
    expect(el.scrollTop).toBe(0)

    const empty = mountList({ list: [] })
    const emptyEl = empty.find('.vl-container').element
    Object.defineProperty(emptyEl, 'scrollTop', { value: 0, writable: true, configurable: true })
    empty.vm.scrollToIndex(3)
    expect(emptyEl.scrollTop).toBe(0)
  })

  it('scrollToBottom → scrollTop = 总高；reset → 回 0', async () => {
    const wrapper = mountList()
    const el = wrapper.find('.vl-container').element
    Object.defineProperty(el, 'clientHeight', { value: 600, configurable: true })
    Object.defineProperty(el, 'scrollTop', { value: 0, writable: true, configurable: true })

    wrapper.vm.scrollToBottom()
    expect(el.scrollTop).toBe(7200)
    expect(wrapper.vm.getTotalHeight()).toBe(7200)

    wrapper.vm.reset()
    expect(el.scrollTop).toBe(0)
  })

  it('头部插入（上翻历史）→ 补偿 scrollTop 保持视觉位置', async () => {
    const wrapper = mountList()
    const el = wrapper.find('.vl-container').element
    Object.defineProperty(el, 'clientHeight', { value: 600, configurable: true })
    Object.defineProperty(el, 'scrollTop', { value: 1000, writable: true, configurable: true })
    // 模拟滚动到中间
    await wrapper.find('.vl-container').trigger('scroll')

    // 头部插入 10 条（messageId 101..110 在前，原首条 messageId=1 移到 index 10）
    const older = makeList(10).map((m) => ({ ...m, messageId: m.messageId + 100 }))
    await wrapper.setProps({ list: [...older, ...makeList(100)] })
    await wrapper.vm.$nextTick()
    await new Promise((r) => setTimeout(r, 0))

    // 补偿 = 新增 10 条 × 72 = 720
    expect(el.scrollTop).toBe(1720)
  })

  it('非头部插入（新消息追加）→ 不补偿 scrollTop', async () => {
    const wrapper = mountList()
    const el = wrapper.find('.vl-container').element
    Object.defineProperty(el, 'clientHeight', { value: 600, configurable: true })
    Object.defineProperty(el, 'scrollTop', { value: 1000, writable: true, configurable: true })
    await wrapper.find('.vl-container').trigger('scroll')

    await wrapper.setProps({
      list: [...makeList(100), { messageId: 999, messageContent: 'new' }]
    })
    await wrapper.vm.$nextTick()
    await new Promise((r) => setTimeout(r, 0))
    expect(el.scrollTop).toBe(1000)
  })
})
