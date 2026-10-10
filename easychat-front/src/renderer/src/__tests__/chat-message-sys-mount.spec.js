import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessageSys from '@/views/chat/ChatMessageSys.vue'

/**
 * 系统消息气泡的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. messageType==1 → 文案被 computed 改写（与入参不同）
 *   2. 其他类型 → 文案原样透传
 *   3. 顶部渲染 ChatMessageTime（同一 data）
 *
 * 注：源文件中「打招呼」文案为历史转码乱码字节，故不断言其字面内容，
 * 只断言「被改写」这一行为（AGENTS.md §2.1 第 4 条：不碰乱码文件）。
 */

const stubs = {
  ChatMessageTime: {
    name: 'ChatMessageTime',
    props: ['data'],
    template: '<div class="chat-message-time" />'
  }
}

describe('ChatMessageSys.vue 真实挂载（DOM 级）', () => {
  it('messageType==1 → 文案被改写', () => {
    const wrapper = mount(ChatMessageSys, {
      props: { data: { messageType: 1, messageContent: '原始内容' } },
      global: { stubs }
    })
    const text = wrapper.find('.sys-message').text()
    expect(text).toBeTruthy()
    expect(text).not.toBe('原始内容')
  })

  it('其他类型 → 文案原样透传', () => {
    const wrapper = mount(ChatMessageSys, {
      props: { data: { messageType: 9, messageContent: '某某某加入了群聊' } },
      global: { stubs }
    })
    expect(wrapper.find('.sys-message').text()).toBe('某某某加入了群聊')
  })

  it('渲染 ChatMessageTime 且传同一份 data', () => {
    const data = { messageType: 9, messageContent: 'x', sendTime: 1 }
    const wrapper = mount(ChatMessageSys, { props: { data }, global: { stubs } })
    const time = wrapper.findComponent({ name: 'ChatMessageTime' })
    expect(time.exists()).toBe(true)
    // 传的是 computed 派生对象（props.data 的副本），非同一引用
    expect(time.props('data')).toEqual(data)
    expect(time.props('data')).not.toBe(data)
  })
})
