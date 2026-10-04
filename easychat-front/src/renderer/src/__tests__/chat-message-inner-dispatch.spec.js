import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// ChatMessage.vue 内部分发（2026-10-04，L3）
//
// 锁定的是**已真实发生过的静默失效**：2026-10-03 走查发现
// `ChatMessageVoice.vue` 是**从未被 import 的死组件** —— 语音消息（messageType=24）
// 路由过去后因 `v-else-if="data.messageType != 5"` 落到纯文本渲染分支，
// 实际渲染出 messageContent="[语音]" 的普通气泡，而不是可播放的语音条。
// **不抛异常、不打日志**：服务端正常、历史消息也拉到，用户只看到"语音变成了文字"。
//
// 故这里断言的是「具体渲染出哪个子组件」，而不是「没报错」。
// 依据 ADR-001：首批测试必须锁定已知的静默失效点。

const mocks = vi.hoisted(() => ({
  // 子组件全部 stub 成带标记的元素，这样断言的是**父组件选了谁**，
  // 而不是子组件内部实现（后者另由各自的 spec 负责）。
  stubs: {
    ChatMessageVideo: { template: '<div data-test="stub-video" />' },
    ChatMessageImage: { template: '<div data-test="stub-image" />' },
    ChatMessageFile: { template: '<div data-test="stub-file" />' },
    ChatMessageLocation: { template: '<div data-test="stub-location" />' },
    ChatMessageVoice: { template: '<div data-test="stub-voice" />' },
    ContextMenu: { template: '<div data-test="stub-context-menu" />' }
  }
}))

vi.mock('@imengyu/vue3-context-menu', () => ({
  default: mocks.stubs.ContextMenu
}))
vi.mock('@imengyu/vue3-context-menu/lib/vue3-context-menu.css', () => ({}))

// ChatMessage.vue 用 useUserInfoStore() 拿昵称做 @ 显示
vi.mock('@/stores/UserInfoStore', () => ({
  useUserInfoStore: () => ({
    getInfo: () => ({ userId: 'U_ME', userName: '我' })
  })
}))

const ChatMessage = (await import('@/views/chat/ChatMessage.vue')).default

function makeData (overrides = {}) {
  return {
    messageId: '1001',
    messageType: 2,
    messageContent: '你好',
    sendUserId: 'U_ME',
    sendUserNickName: '我',
    sendTime: 1700000000000,
    contactType: 0,
    sessionId: 'U_OTHER',
    ...overrides
  }
}

function mountMsg (data) {
  return mount(ChatMessage, {
    props: { data, currentChatSession: { userId: 'U_ME', contactId: 'U_OTHER' } },
    global: {
      stubs: {
        ChatMessageVideo: mocks.stubs.ChatMessageVideo,
        ChatMessageImage: mocks.stubs.ChatMessageImage,
        ChatMessageFile: mocks.stubs.ChatMessageFile,
        ChatMessageLocation: mocks.stubs.ChatMessageLocation,
        ChatMessageVoice: mocks.stubs.ChatMessageVoice,
        ContextMenu: mocks.stubs.ContextMenu
      }
    }
  })
}

describe('ChatMessage.vue 二次分发', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // ── 24 语音：2026-10-03 死组件事故的同一处 ────────────────
  it('messageType=24 渲染 ChatMessageVoice（不是纯文本气泡）', () => {
    const w = mountMsg(makeData({ messageType: 24, messageContent: '[语音]', duration: 5 }))
    expect(w.find('[data-test="stub-voice"]').exists()).toBe(true)
    // 反向断言：不得同时出现"按纯文本渲染"的分支
    // （那正是事故现象：渲染出 messageContent 的纯文本 div）
    const plain = w.findAll('div.content').filter((n) => n.text().includes('[语音]'))
    expect(plain.length).toBe(0)
  })

  it('messageType=24 时不再走「非 5 即纯文本」的兜底分支', () => {
    const w = mountMsg(makeData({ messageType: 24, messageContent: '[语音]' }))
    // 该分支条件是 v-else-if="data.messageType != 5"；24 != 5 为真，
    // 若 ChatMessageVoice 未被识别，语音就会掉进这里被当纯文本渲染。
    expect(w.find('[data-test="stub-voice"]').exists()).toBe(true)
  })

  // ── 25 位置 ──────────────────────────────────────────────
  it('messageType=25 渲染 ChatMessageLocation', () => {
    const w = mountMsg(makeData({
      messageType: 25,
      messageContent: '',
      extraData: JSON.stringify({ location: { latitude: 39.9, longitude: 116.4, name: '公司' } })
    }))
    expect(w.find('[data-test="stub-location"]').exists()).toBe(true)
  })

  it('位置与语音互斥（不会同时渲染）', () => {
    const wLoc = mountMsg(makeData({ messageType: 25 }))
    expect(wLoc.find('[data-test="stub-location"]').exists()).toBe(true)
    expect(wLoc.find('[data-test="stub-voice"]').exists()).toBe(false)
  })

  // ── 普通文本 ─────────────────────────────────────────────
  it('messageType=2 走纯文本渲染，且不渲染任何媒体子组件', () => {
    const w = mountMsg(makeData({ messageType: 2, messageContent: '纯文本内容' }))
    for (const k of ['stub-voice', 'stub-location', 'stub-video', 'stub-image', 'stub-file']) {
      expect(w.find(`[data-test="${k}"]`).exists()).toBe(false)
    }
    expect(w.html()).toContain('纯文本内容')
  })

  // ── 撤回 / 管理员删除 ────────────────────────────────────
  it('messageType=14（撤回）渲染撤回态而非内容', () => {
    const w = mountMsg(makeData({ messageType: 14, messageContent: '将被撤回的原文' }))
    expect(w.html()).not.toContain('将被撤回的原文')
  })

  it('messageType=20（管理员删除）渲染管理员删除态', () => {
    const w = mountMsg(makeData({ messageType: 20, messageContent: '被删内容' }))
    expect(w.html()).not.toContain('被删内容')
  })

  // ── 撤回(14) 与管理员删除(20) 的文案必须可区分 ──────────────
  // 两者共用同一段模板，靠 `data.messageType == 20 ? … : getRecallText()`
  // 三元区分。若该三元被抹平成同一个文案，用户无法分辨「自己撤的」
  // 与「被管理员删的」——不抛异常，只是语义丢失。
  it('撤回与管理员删除的文案可区分（不得都显示同一条）', () => {
    const wRecall = mountMsg(makeData({ messageType: 14 }))
    const wAdmin = mountMsg(makeData({ messageType: 20 }))
    const tRecall = wRecall.find('.recall-text').text()
    const tAdmin = wAdmin.find('.recall-text').text()
    expect(tRecall).not.toBe(tAdmin)
  })

  it('管理员删除显示管理员语义文案', () => {
    const w = mountMsg(makeData({ messageType: 20 }))
    expect(w.find('.recall-text').text()).toContain('管理员')
  })

  // ── 未支持类型不得抛异常（前端 AGENTS：不得越层/不得静默崩）──
  it('未支持的消息类型不抛异常，且不渲染媒体子组件', () => {
    const w = mountMsg(makeData({ messageType: 99, messageContent: '未知类型' }))
    for (const k of ['stub-voice', 'stub-location', 'stub-video', 'stub-image', 'stub-file']) {
      expect(w.find(`[data-test="${k}"]`).exists()).toBe(false)
    }
  })
})