import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Blank from '@/components/Blank.vue'

/**
 * 空白占位组件的渲染校验（纯静态，无逻辑）。
 * 仅锁定 DOM 结构不退化。
 */
describe('Blank.vue 挂载', () => {
  it('渲染顶部拖拽区与占位图标', () => {
    const wrapper = mount(Blank)
    expect(wrapper.find('.top').exists()).toBe(true)
    expect(wrapper.find('.icon-chat').exists()).toBe(true)
  })
})
