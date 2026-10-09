import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ContactSearchResult from '@/views/contact/ContactSearchResult.vue'

/**
 * 联系人搜索结果条目的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. `contactName` 经 v-html 渲染（高亮 span 来自父层预构建）
 *   2. Avatar 透传 contactId
 *   3. 无高亮 / 空字段两种形态
 */

const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'showDetail', 'width', 'borderRadius'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}

function mountResult (data) {
  return mount(ContactSearchResult, {
    props: { data },
    global: { stubs: { Avatar: AvatarStub } }
  })
}

describe('ContactSearchResult.vue 真实挂载（DOM 级）', () => {
  it('渲染高亮后的联系人名 + Avatar 透传 contactId', () => {
    const wrapper = mountResult({
      contactId: 'U007',
      contactName: '李<span class="highlight">四</span>'
    })
    expect(wrapper.find('.avatar-stub').attributes('data-userid')).toBe('U007')
    const name = wrapper.find('.contact-name')
    expect(name.text()).toBe('李四')
    expect(name.find('span.highlight').exists()).toBe(true)
  })

  it('无高亮命中：纯文本直出', () => {
    const wrapper = mountResult({ contactId: 'G009', contactName: '产品群' })
    expect(wrapper.find('.contact-name').text()).toBe('产品群')
    expect(wrapper.find('span.highlight').exists()).toBe(false)
  })

  it('空 data 字段 → 名称为空，不抛错', () => {
    const wrapper = mountResult({ contactId: 'U008' })
    expect(wrapper.find('.contact-name').text()).toBe('')
    expect(wrapper.find('.avatar-stub').attributes('data-userid')).toBe('U008')
  })
})
