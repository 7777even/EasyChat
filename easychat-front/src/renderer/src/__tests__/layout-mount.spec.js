import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Layout from '@/components/Layout.vue'

/**
 * 布局组件的渲染校验（纯布局，无逻辑）。
 * 锁定 left-content / right-content 两个 slot 渲染到正确区域。
 */
describe('Layout.vue 挂载', () => {
  it('left-content / right-content slot 渲染到对应区域', () => {
    const wrapper = mount(Layout, {
      slots: {
        'left-content': '<div class="left-slot">L</div>',
        'right-content': '<div class="right-slot">R</div>'
      }
    })
    expect(wrapper.find('.left-side-inner .left-slot').exists()).toBe(true)
    expect(wrapper.find('.right-content .right-slot').exists()).toBe(true)
    expect(wrapper.text()).toContain('L')
    expect(wrapper.text()).toContain('R')
  })
})
