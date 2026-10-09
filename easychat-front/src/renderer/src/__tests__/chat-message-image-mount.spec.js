import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatMessageImage from '@/views/chat/ChatMessageImage.vue'

/**
 * 图片消息气泡的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. ShowLocalImage 收到 messageId（fileId）/ partType=chat / fileType
 *   2. 点击透传：子组件 click → 本组件 emit click（父级据此开大图）
 */

const stubs = {
  ShowLocalImage: {
    name: 'ShowLocalImage',
    props: ['fileId', 'partType', 'fileType', 'showPlay'],
    emits: ['click'],
    template: '<div class="show-local-image" @click="$emit(\'click\', $event)" />'
  }
}

describe('ChatMessageImage.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('ShowLocalImage 参数透传', () => {
    const wrapper = mount(ChatMessageImage, {
      props: { data: { messageId: 'M1', fileType: 2 } },
      global: { stubs }
    })
    const img = wrapper.findComponent({ name: 'ShowLocalImage' })
    expect(img.props('fileId')).toBe('M1')
    expect(img.props('partType')).toBe('chat')
    expect(img.props('fileType')).toBe(2)
  })

  it('点击 → emit click 透传给父级', async () => {
    const wrapper = mount(ChatMessageImage, {
      props: { data: { messageId: 'M1', fileType: 2 } },
      global: { stubs }
    })
    await wrapper.find('.show-local-image').trigger('click')
    expect(wrapper.emitted('click')).toBeTruthy()
  })
})
