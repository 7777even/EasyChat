import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SearchResult from '@/views/chat/SearchResult.vue'

/**
 * 搜索结果条目的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. `searchContactName` / `searchLastMessage` 经 v-html 渲染（高亮 span 来自父层预构建）
 *   2. Avatar 透传 contactId
 *   3. 高亮命中 / 未命中两种数据形态都能完整渲染
 */

const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'showDetail', 'width', 'borderRadius'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}

function mountResult (data) {
  return mount(SearchResult, {
    props: { data },
    global: { stubs: { Avatar: AvatarStub } }
  })
}

describe('SearchResult.vue 真实挂载（DOM 级）', () => {
  it('渲染高亮后的联系人名与最后消息（v-html 生效）', () => {
    const wrapper = mountResult({
      contactId: 'U001',
      searchContactName: '张<span class="highlight">三</span>',
      searchLastMessage: '今天见<span class="highlight">面</span>聊'
    })
    expect(wrapper.find('.avatar-stub').attributes('data-userid')).toBe('U001')
    const name = wrapper.find('.contact-name')
    expect(name.text()).toBe('张三')
    expect(name.find('span.highlight').exists()).toBe(true)
    const last = wrapper.find('.last-message')
    expect(last.text()).toBe('今天见面聊')
    expect(last.find('span.highlight').exists()).toBe(true)
  })

  it('无高亮命中：纯文本也能渲染（v-html 直出原文）', () => {
    const wrapper = mountResult({
      contactId: 'G001',
      searchContactName: '开发群',
      searchLastMessage: '无关键词的普通消息'
    })
    expect(wrapper.find('.contact-name').text()).toBe('开发群')
    expect(wrapper.find('.last-message').text()).toBe('无关键词的普通消息')
    expect(wrapper.find('span.highlight').exists()).toBe(false)
  })

  it('空 data 字段 → 两个槽位为空，不抛错', () => {
    const wrapper = mountResult({ contactId: 'U002' })
    expect(wrapper.find('.contact-name').text()).toBe('')
    expect(wrapper.find('.last-message').text()).toBe('')
    expect(wrapper.find('.avatar-stub').attributes('data-userid')).toBe('U002')
  })
})
