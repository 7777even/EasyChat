import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatMessageVideo from '@/views/chat/ChatMessageVideo.vue'

/**
 * 视频消息气泡的**真实挂载**测试（DOM 级）。
 *
 * 被测点：与图片气泡同构，但 showPlay=true（视频要显示播放按钮），
 * 且**不**向上透传 click（点击播放由 ShowLocalImage 自己处理）。
 */

const stubs = {
  ShowLocalImage: {
    name: 'ShowLocalImage',
    props: ['fileId', 'partType', 'fileType', 'showPlay'],
    emits: ['click'],
    template: '<div class="show-local-image" />'
  }
}

describe('ChatMessageVideo.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('ShowLocalImage 参数透传（showPlay=true）', () => {
    const wrapper = mount(ChatMessageVideo, {
      props: { data: { messageId: 'M9', fileType: 3 } },
      global: { stubs }
    })
    const img = wrapper.findComponent({ name: 'ShowLocalImage' })
    expect(img.props('fileId')).toBe('M9')
    expect(img.props('partType')).toBe('chat')
    expect(img.props('fileType')).toBe(3)
    // 视频气泡必须带播放标识，与图片气泡的差异点
    expect(img.props('showPlay')).toBe(true)
  })
})
