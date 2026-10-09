import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ContentPanel from '@/components/ContentPanel.vue'

/**
 * 内容面板组件的**真实挂载**测试（DOM 级）。
 *
 * 说明：showTopBorder 差异体现在 `border-top` 内联样式（含 CSS 变量
 * `var(--ec-border)`），jsdom 的 cssstyle 不支持 CSS 自定义属性，
 * style 属性不会被渲染，故不对此做 DOM 断言（不可靠断言不如不断言）。
 * 这里锁定结构与 slot 渲染这两件可靠事实。
 */
describe('ContentPanel.vue 真实挂载（DOM 级）', () => {
  it('挂载后渲染 content-panel 结构', () => {
    const wrapper = mount(ContentPanel)
    expect(wrapper.find('.content-panel').exists()).toBe(true)
    expect(wrapper.find('.content-inner').exists()).toBe(true)
  })

  it('slot 内容渲染到 content-inner 内', () => {
    const wrapper = mount(ContentPanel, {
      slots: { default: '<div class=\"my-content\">内容</div>' }
    })
    expect(wrapper.find('.content-inner .my-content').exists()).toBe(true)
    expect(wrapper.text()).toContain('内容')
  })

  it('props 传递：showTopBorder 默认 false', () => {
    const wrapper = mount(ContentPanel)
    expect(wrapper.props('showTopBorder')).toBe(false)
  })
})
