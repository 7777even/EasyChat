import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessageFile from '@/views/chat/ChatMessageFile.vue'

/**
 * 文件消息气泡的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 文件名 / 大小（经 Utils.size2Str）/ 处理状态两态
 *   2. 完成态显示 icon-ok；处理中不显示
 *   3. 完成态 cursor=pointer
 *   4. fileType 决定右侧图标（2 文件 / 其他 视频）
 *
 * 注：源文件「文件处理中」文案为历史转码乱码字节，故对处理中分支
 * 只断言「info 有非空文本」，不断言字面内容（AGENTS.md §2.1 第 4 条）。
 */

describe('ChatMessageFile.vue 真实挂载（DOM 级）', () => {
  function mountFile(data) {
    return mount(ChatMessageFile, {
      props: { data },
      global: {
        config: {
          globalProperties: {
            Utils: { size2Str: (n) => `${n}B` }
          }
        }
      }
    })
  }

  it('文件名与大小渲染', () => {
    const wrapper = mountFile({ fileName: 'a.pdf', fileSize: 1024, status: 1, fileType: 2 })
    expect(wrapper.find('.file-name').text()).toBe('a.pdf')
    expect(wrapper.find('.file-size').text()).toBe('大小：1024B')
  })

  it('status=1（完成）→ 处理完成 + icon-ok + 可点击光标', () => {
    const wrapper = mountFile({ fileName: 'a.pdf', fileSize: 1, status: 1, fileType: 2 })
    expect(wrapper.find('.icon-ok').exists()).toBe(true)
    expect(wrapper.find('.process .info').text()).toBe('处理完成')
    expect(wrapper.find('.file-panel').attributes('style')).toContain('pointer')
  })

  it('status=0（处理中）→ 无 icon-ok + 非空提示 + 无点击光标', () => {
    const wrapper = mountFile({ fileName: 'a.pdf', fileSize: 1, status: 0, fileType: 2 })
    expect(wrapper.find('.icon-ok').exists()).toBe(false)
    expect(wrapper.find('.process .info').text()).toBeTruthy()
    expect(wrapper.find('.process .info').text()).not.toBe('处理完成')
    expect(wrapper.find('.file-panel').attributes('style') || '').not.toContain('pointer')
  })

  it('fileType 决定右侧图标', () => {
    expect(mountFile({ fileName: 'a', fileSize: 1, status: 1, fileType: 2 }).find('.icon-file').exists()).toBe(true)
    expect(mountFile({ fileName: 'a', fileSize: 1, status: 1, fileType: 3 }).find('.icon-video').exists()).toBe(true)
  })

  it('title 携带完整文件名', () => {
    const wrapper = mountFile({ fileName: '很长很长的文件名.pdf', fileSize: 1, status: 1, fileType: 2 })
    expect(wrapper.find('.file-info').attributes('title')).toBe('很长很长的文件名.pdf')
  })
})
