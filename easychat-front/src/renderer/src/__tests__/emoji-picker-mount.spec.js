import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import EmojiPicker from '@/views/chat/EmojiPicker.vue'

/**
 * 表情包选择器组件的**真实挂载**测试（DOM 级）。
 *
 * 为什么选它作为前端组件测试首批：
 *   1. 交互明确（选择/上传/删除），依赖清晰（仅 Request/Api/Message/Confirm）
 *   2. 被 MsgSend 多处使用，是聊天核心交互链的一环
 *   3. 与已测的 ChatMessageVoice 同属"媒体选择"类，测试模式可复用
 *
 * 测试模式参照 chat-message-voice-mount.spec.js：
 *   mount + globalProperties（Request/Api/Message/Confirm）+ makeRequestStub
 */

const MessageStub = { error: vi.fn(), warning: vi.fn(), success: vi.fn() }

/** Confirm 桩：同步执行 okfun（模拟用户点「确定」） */
const ConfirmStub = vi.fn((config) => {
  if (config && typeof config.okfun === 'function') {
    config.okfun()
  }
})

/** 可控的 Request 桩：按 url 返回预设结果，并记录调用 */
function makeRequestStub (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountEmoji (handler) {
  const request = makeRequestStub(handler)
  const wrapper = mount(EmojiPicker, {
    global: {
      // Element Plus 组件 stub：不注册全量插件（快且可控），
      // 但保留 class 形态使测试选择器与生产 DOM 一致
      stubs: {
        'el-button': {
          template: '<button :class="[\'el-button\', type ? \'el-button--\' + type : \'\']"><slot /></button>',
          props: ['type']
        },
        'el-dialog': {
          template: '<div v-if="modelValue" class="el-dialog"><slot /><slot name="footer" /></div>',
          props: ['modelValue']
        },
        'el-upload': { template: '<div><slot /></div>' },
        'el-icon': { template: '<span><slot /></span>' }
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            emojiList: '/chat/emojiList',
            emojiUpload: '/chat/emojiUpload',
            emojiDelete: '/chat/emojiDelete'
          },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  return { wrapper, request }
}

/** 等待异步链完成（onMounted → Request → 渲染） */
async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

describe('EmojiPicker.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    MessageStub.error.mockClear()
    MessageStub.warning.mockClear()
    MessageStub.success.mockClear()
    ConfirmStub.mockClear()
  })

  it('挂载后拉取列表并渲染表情包', async () => {
    const { wrapper, request } = mountEmoji(() => ({
      code: 0,
      data: [
        { id: 1, filePath: '/e/1.png', fileName: '表情1' },
        { id: 2, filePath: '/e/2.png', fileName: '表情2' }
      ]
    }))
    await flush()
    expect(request.__calls__.some((c) => c.url === '/chat/emojiList')).toBe(true)
    expect(wrapper.findAll('.emoji-item').length).toBe(2)
    expect(wrapper.find('img').attributes('src')).toBe('/e/1.png')
  })

  it('空列表显示「暂无表情包」', async () => {
    const { wrapper } = mountEmoji(() => ({ code: 0, data: [] }))
    await flush()
    expect(wrapper.find('.empty').exists()).toBe(true)
    expect(wrapper.find('.empty').text()).toContain('暂无表情包')
  })

  it('点击表情触发 select 事件', async () => {
    const { wrapper } = mountEmoji(() => ({
      code: 0,
      data: [{ id: 1, filePath: '/e/1.png', fileName: '表情1' }]
    }))
    await flush()
    await wrapper.find('.emoji-item').trigger('click')
    expect(wrapper.emitted('select')).toBeTruthy()
    expect(wrapper.emitted('select')[0][0]).toEqual({
      id: 1,
      filePath: '/e/1.png',
      fileName: '表情1'
    })
  })

  it('上传未选文件时提示「请选择文件」', async () => {
    const { wrapper } = mountEmoji(() => ({ code: 0, data: [] }))
    await flush()
    // 打开上传弹窗
    await wrapper.find('.emoji-header .el-button').trigger('click')
    await flush()
    // 未选文件直接点确定 → warning
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请选择文件')
  })

  it('删除表情包：Confirm 确认后调 emojiDelete 并重新拉取', async () => {
    const { wrapper, request } = mountEmoji(() => ({
      code: 0,
      data: [{ id: 1, filePath: '/e/1.png', fileName: '表情1' }]
    }))
    await flush()
    await wrapper.find('.emoji-delete').trigger('click')
    await flush()
    // Confirm 被调（okfun 执行）→ Request 调 emojiDelete
    const del = request.__calls__.find((c) => c.url === '/chat/emojiDelete')
    expect(del).toBeTruthy()
    expect(del.params.emojiId).toBe(1)
    expect(MessageStub.success).toHaveBeenCalledWith('删除成功')
    // 删除成功后重新拉取列表（挂载 1 次 + 删除后 1 次）
    const listCalls = request.__calls__.filter((c) => c.url === '/chat/emojiList')
    expect(listCalls.length).toBe(2)
  })
})
