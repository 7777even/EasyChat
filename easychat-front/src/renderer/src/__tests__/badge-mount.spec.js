import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Badge from '@/components/Badge.vue'

/**
 * 角标组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. count=0 → 不渲染（无消息不显示角标）
 *   2. 0<count≤99 → 显示具体数字
 *   3. count>99 → 封顶 "99+"
 *   4. top/left → 定位样式
 */
describe('Badge.vue 真实挂载（DOM 级）', () => {
  it('count=0 → 不渲染', () => {
    const wrapper = mount(Badge, { props: { count: 0 } })
    expect(wrapper.find('.badge').exists()).toBe(false)
  })

  it('count=5 → 显示 "5"', () => {
    const wrapper = mount(Badge, { props: { count: 5 } })
    const badge = wrapper.find('.badge')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('5')
  })

  it('count=100 → 显示 "99+"', () => {
    const wrapper = mount(Badge, { props: { count: 100 } })
    expect(wrapper.find('.badge').text()).toBe('99+')
  })

  it('top/left → 定位样式', () => {
    const wrapper = mount(Badge, { props: { count: 3, top: 10, left: 20 } })
    const style = wrapper.find('.badge').attributes('style')
    expect(style).toContain('top: 10px')
    expect(style).toContain('left: 20px')
  })
})
