import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessageTime from '@/views/chat/ChatMessageTime.vue'

/**
 * 消息时间气泡的**真实挂载**测试（DOM 级）。
 *
 * 被测点：sendTime 经 proxy.Utils.formatDate 格式化后渲染。
 */

describe('ChatMessageTime.vue 真实挂载（DOM 级）', () => {
  it('sendTime 经 Utils.formatDate 渲染', () => {
    const formatDate = vi.fn(() => '2026-10-01 09:05')
    const wrapper = mount(ChatMessageTime, {
      props: { data: { sendTime: 1700000000000 } },
      global: { config: { globalProperties: { Utils: { formatDate } } } }
    })
    expect(formatDate).toHaveBeenCalledWith(1700000000000)
    expect(wrapper.find('.message-time').text()).toBe('2026-10-01 09:05')
  })
})
