import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatMessageVoice from '@/views/chat/ChatMessageVoice.vue'

/**
 * 语音消息组件的**真实挂载**测试（DOM 级）。
 *
 * 为什么在已有 3 个 spec（58 例）之后还要写这个：
 *   既有 spec 全是「纯核心函数」+「对 .vue 模板做**源码文本断言**」，
 *   **没有一个真正 mount 过组件**。而 2026-10-03 的真实事故正是：
 *   `ChatMessageVoice.vue` 从未被 import（死组件）→ 语音消息落进纯文本兜底分支 →
 *   **渲染成普通文本气泡而不是可播放语音条**，服务端正常、历史漫游也拉到，
 *   **不抛异常不报错**。文本断言能发现「分支缺失」，但发现不了
 *   「分支在、组件却没渲染出来」这类**运行期**退化。
 *
 * 本文件同时是**基础设施可用性的证明**：@vue/test-utils + jsdom + @vitejs/plugin-vue
 * 此前已安装并被 verify_frontend_test_base.mjs 门禁锁住，却**没有任何测试在用** ——
 * 门禁只能证明「具备挂载能力」，不能证明「有人在挂载」。这个文件补上后半句。
 */

const MessageStub = { error: vi.fn(), warning: vi.fn(), success: vi.fn() }

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

function mountVoice (data, requestHandler, pinia) {
  const request = makeRequestStub(requestHandler)
  // ⚠️ 注入形状必须与 main.js 一致：`<script setup>` 里的
  //    `const { proxy } = getCurrentInstance()` 拿到的是**实例代理**，
  //    它会**直接转发到 globalProperties**。所以生产代码是
  //    `app.config.globalProperties.Request = Request`，
  //    组件里写 `proxy.Request` —— **不存在名为 `proxy` 的 globalProperties 键**。
  //    我第一版注入成 `globalProperties.proxy = {...}`，结果 proxy.Request 为 undefined。
  const wrapper = mount(ChatMessageVoice, {
    props: { data },
    global: {
      config: {
        globalProperties: {
          Request: request,
          Api: { markVoiceRead: '/chat/markVoiceRead', loadVoiceRead: '/chat/loadVoiceRead' },
          Message: MessageStub
        }
      },
      // store 用**同一个** pinia 实例（组件内部直接 useUserInfoStore()）
      plugins: [pinia]
    }
  })
  return { wrapper, request }
}

/** 语音消息的最小可用 data 形态（字段名对齐后端 ChatMessage PO 的 camelCase 映射） */
function voiceData (overrides = {}) {
  return {
    messageId: '1001',
    messageType: 24,
    messageContent: '',
    sendUserId: 'U_PEER',
    sendTime: 1700000000000,
    contactType: 0,
    duration: 7,
    fileName: 'v_1.webm',
    fileType: 3,
    ...overrides
  }
}

describe('ChatMessageVoice.vue 真实挂载（DOM 级）', () => {
  // ⚠️ pinia 实例必须**共用同一个**：mount 的 `plugins: [createPinia()]` 会创建
  //    独立实例，若测试侧另用 setActivePinia 设值，组件读到的是另一个空 store。
  //    我第一版正是这样，导致「自己发的语音不拉已读状态」一例假红
  //    —— 断言自己写错，却一度指向组件有 bug。故此处由 describe 级变量统一持有。
  let pinia

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    MessageStub.error.mockClear()
    // jsdom 不实现 HTMLMediaElement.play/pause，未 stub 时挂载或点击即抛
    // "Not implemented" —— 这正是「不真正跑过」的证据，故此处必须补。
    window.HTMLMediaElement.prototype.play = vi.fn().mockResolvedValue(undefined)
    window.HTMLMediaElement.prototype.pause = vi.fn()
    window.HTMLMediaElement.prototype.load = vi.fn()
  })

  it('挂载后渲染出可播放的 <audio>，而不是纯文本', () => {
    const { wrapper } = mountVoice(voiceData(), pinia)
    const audio = wrapper.find('audio')
    expect(audio.exists(), '语音消息必须渲染 <audio>，否则就是纯文本兜底').toBe(true)
    // 时长也要真的显示出来
    expect(wrapper.text()).toContain('7s')
  })

  it('<audio> 的 src 指向本地文件服务器且带 fileType=3（fileType 错会导致浏览器不解码）', () => {
    const { wrapper } = mountVoice(voiceData(), pinia)
    const src = wrapper.find('audio').attributes('src') || ''
    // 2026-10-03 事故二：file.js 的 FILE_TYPE_CONTENT_TYPE 缺 fileType=3
    //   → content-type 拼成 "undefinedwebm" → <audio> 静默无声。
    //   这里断言 URL 侧的 fileType=3，与 verify_file_type_content_type.mjs 呼应。
    expect(src).toContain('fileType=3')
    expect(src).toContain('fileId=1001')
  })

  it('无 messageId 时 src 为空字符串（不生成坏 URL）', () => {
    const { wrapper } = mountVoice(voiceData({ messageId: null }), pinia)
    expect(wrapper.find('audio').attributes('src')).toBe('')
  })

  it('未播放红点：对方发来且后端说未播放时显示', async () => {
    // loadVoiceRead 返回空数组 = 不在已读列表 → 应亮红点
    const { wrapper, request } = mountVoice(voiceData(), () => ({ code: 0, data: [] }), pinia)
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.some((c) => c.url === '/chat/loadVoiceRead')).toBe(true)
    expect(wrapper.find('.voice-unplayed-dot').exists()).toBe(true)
  })

  it('已播放则不亮红点（loadVoiceRead 返回非空 = 已在已读列表）', async () => {
    const { wrapper } = mountVoice(voiceData(), () => ({ code: 0, data: [{ messageId: '1001' }] }), pinia)
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.voice-unplayed-dot').exists()).toBe(false)
  })

  it('自己发的语音不拉已读状态（无意义且浪费一次请求）', async () => {
    // 把 store 里的当前用户设为发送者本人
    const { useUserInfoStore } = await import('@/stores/UserInfoStore')
    const store = useUserInfoStore()
    store.setInfo({ userId: 'U_PEER' })
    const { request } = mountVoice(voiceData({ sendUserId: 'U_PEER' }), pinia)
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.some((c) => c.url === '/chat/loadVoiceRead')).toBe(false)
  })

  it('点击播放：调用 play()、加 playing 类、乐观清除红点并上报已读', async () => {
    let playCalls = 0
    window.HTMLMediaElement.prototype.play = vi.fn(() => { playCalls++; return Promise.resolve() })
    const { wrapper, request } = mountVoice(voiceData(), (opts) => {
      if (opts.url === '/chat/loadVoiceRead') return { code: 0, data: [] }   // 先亮红点
      if (opts.url === '/chat/markVoiceRead') return { code: 0, data: true }
      return { code: 0, data: null }
    }, pinia)
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.voice-unplayed-dot').exists()).toBe(true)

    await wrapper.find('.voice-message').trigger('click')
    expect(playCalls, '点击应调用 audio.play()').toBe(1)
    expect(wrapper.find('.voice-icon').classes()).toContain('playing')
    // 乐观清除红点 + 上报后端
    expect(wrapper.find('.voice-unplayed-dot').exists()).toBe(false)
    const mark = request.__calls__.find((c) => c.url === '/chat/markVoiceRead')
    expect(mark, '播放后必须上报已读，否则红点会在下次拉取时又亮起').toBeTruthy()
    expect(mark.params.messageId).toBe('1001')
  })

  it('markVoiceRead 失败时恢复红点（防止「界面显示已读、实际未记录」）', async () => {
    const { wrapper } = mountVoice(voiceData(), (opts) => {
      if (opts.url === '/chat/loadVoiceRead') return { code: 0, data: [] }
      if (opts.url === '/chat/markVoiceRead') return null      // 模拟失败返回 null
      return { code: 0, data: null }
    }, pinia)
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.voice-message').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.voice-unplayed-dot').exists(),
      '上报失败必须把红点恢复回来，否则用户以为已读而实际未记录').toBe(true)
  })

  it('再次点击暂停：isPlaying 复位且不再重复上报已读', async () => {
    let pauseCalls = 0
    window.HTMLMediaElement.prototype.pause = vi.fn(() => { pauseCalls++ })
    const { wrapper, request } = mountVoice(voiceData(), (opts) => {
      if (opts.url === '/chat/loadVoiceRead') return { code: 0, data: [] }
      return { code: 0, data: true }
    }, pinia)
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.voice-message').trigger('click')
    await wrapper.find('.voice-message').trigger('click')
    expect(pauseCalls).toBe(1)
    expect(wrapper.find('.voice-icon').classes()).not.toContain('playing')
    const marks = request.__calls__.filter((c) => c.url === '/chat/markVoiceRead')
    expect(marks.length, '暂停不应再次上报已读').toBe(1)
  })

  it('播放出错时提示且复位 playing 类', async () => {
    const { wrapper } = mountVoice(voiceData(), pinia)
    const audio = wrapper.find('audio')
    await audio.trigger('error')
    expect(MessageStub.error).toHaveBeenCalledWith('语音播放失败')
    expect(wrapper.find('.voice-icon').classes()).not.toContain('playing')
  })
})
