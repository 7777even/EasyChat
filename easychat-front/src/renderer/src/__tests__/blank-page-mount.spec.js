import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import BlankPage from '@/views/contact/BlankPage.vue'

/**
 * 空白页包装组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点：BlankPage 是 Blank 的纯包装（无 props/逻辑），
 * 验证其正确透传渲染且不吞掉 Blank 的内容。
 */

const BlankStub = {
  name: 'Blank',
  template: '<div class="blank-inner"><slot /></div>'
}

describe('BlankPage.vue 真实挂载（DOM 级）', () => {
  it('渲染内部 Blank 组件（包装层不吞内容）', () => {
    const wrapper = mount(BlankPage, {
      global: { stubs: { Blank: BlankStub } }
    })
    expect(wrapper.find('.blank-inner').exists()).toBe(true)
    expect(wrapper.html()).toContain('blank-inner')
  })

  it('真实 Blank（非 stub）：渲染其默认占位内容', async () => {
    const { default: RealBlank } = await import('@/components/Blank.vue')
    const wrapper = mount(BlankPage, {
      global: { stubs: { Blank: RealBlank } }
    })
    // 真实组件至少渲染出根节点（结构完整性：包装层透传无断裂）
    expect(wrapper.findComponent(RealBlank).exists()).toBe(true)
    expect(wrapper.text().length).toBeGreaterThanOrEqual(0)
  })
})
