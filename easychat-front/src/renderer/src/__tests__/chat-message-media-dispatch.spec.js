import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// ChatMessage.vue 媒体消息分派（2026-10-04）
//
// 锁定的是一个**结构性隐患**：`fileType → 子组件` 的分派在模板里存在**两份**——
//   一份在 `.message-content-my`（自己发送，约 31-39 行）
//   一份在 `.message-content-other`（对方发送，约 89-97 行）
// 两者内容相同但互相独立。这属于「条件漏一项 → 静默失效」的高危结构：
// 新增一种 fileType 时若只改一侧，则**自己发的消息**与**对方发的消息**
// 会渲染出完全不同的东西，而页面不报错、控制台无警告。
//
// 本测试分别 mount 两侧，断言**同一 fileType 在两侧渲染出同一个子组件**。
// 这类「两处重复必须保持同步」的约束无法靠 eslint 表达，只能靠测试钉住。

const MY_ID = 'U_ME'

vi.mock('@imengyu/vue3-context-menu', () => ({
  default: { template: '<div data-test="stub-context-menu" />' }
}))
vi.mock('@imengyu/vue3-context-menu/lib/vue3-context-menu.css', () => ({}))

vi.mock('@/stores/UserInfoStore', () => ({
  useUserInfoStore: () => ({
    getInfo: () => ({ userId: MY_ID, userName: '我' })
  })
}))

const ChatMessage = (await import('@/views/chat/ChatMessage.vue')).default

// fileType → 期望渲染的子组件标记
const FILE_TYPE_MAP = [
  { fileType: 0, marker: 'stub-image', label: '图片' },
  { fileType: 1, marker: 'stub-video', label: '视频' },
  { fileType: 2, marker: 'stub-file', label: '文件' }
]

const STUBS = {
  ChatMessageVideo: { template: '<div data-test="stub-video" />' },
  ChatMessageImage: { template: '<div data-test="stub-image" />' },
  ChatMessageFile: { template: '<div data-test="stub-file" />' },
  ChatMessageLocation: { template: '<div data-test="stub-location" />' },
  ChatMessageVoice: { template: '<div data-test="stub-voice" />' },
  ChatMessageTime: { template: '<div data-test="stub-time" />' },
  Avatar: { template: '<div data-test="stub-avatar" />' },
  ContextMenu: { template: '<div data-test="stub-context-menu" />' },
  'el-checkbox': { template: '<input type="checkbox" />' },
  'el-skeleton': { template: '<div data-test="stub-skeleton" />' },
  'el-skeleton-item': { template: '<div data-test="stub-skeleton-item" />' }
}

function mediaData (overrides = {}) {
  return {
    messageId: '2001',
    messageType: 5, // MEDIA_CHAT
    messageContent: '',
    fileType: 0,
    fileName: 'a.png',
    filePath: '/tmp/a.png',
    fileSize: 1024,
    sendUserId: MY_ID,
    sendUserNickName: '我',
    sendTime: 1700000000000,
    contactType: 0,
    sessionId: 'U_OTHER',
    status: 1, // 必须非 0，否则渲染 sending 骨架屏
    ...overrides
  }
}

function mountMsg (data) {
  return mount(ChatMessage, {
    props: { data, currentChatSession: { userId: MY_ID, contactId: 'U_OTHER' } },
    global: { stubs: STUBS }
  })
}

describe('ChatMessage.vue 媒体消息分派（两侧必须一致）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  describe('自己发送的消息（.message-content-my）', () => {
    for (const { fileType, marker, label } of FILE_TYPE_MAP) {
      it(`fileType=${fileType} 渲染${label}组件`, () => {
        const w = mountMsg(mediaData({ fileType }))
        expect(w.find(`[data-test="${marker}"]`).exists()).toBe(true)
        expect(w.find('.message-content-my').exists()).toBe(true)
      })
    }
  })

  describe('对方发送的消息（.message-content-other）', () => {
    for (const { fileType, marker, label } of FILE_TYPE_MAP) {
      it(`fileType=${fileType} 渲染${label}组件`, () => {
        const w = mountMsg(mediaData({ fileType, sendUserId: 'U_OTHER', sendUserNickName: '对方' }))
        expect(w.find(`[data-test="${marker}"]`).exists()).toBe(true)
        expect(w.find('.message-content-other').exists()).toBe(true)
      })
    }
  })

  // ★ 核心断言：两侧对同一 fileType 必须给出**相同**的子组件。
  //   只改一侧 → 本用例转红。
  it('两侧对同一 fileType 渲染出同一个子组件（模板中分派逻辑被复制成两份，必须保持同步）', () => {
    for (const { fileType } of FILE_TYPE_MAP) {
      const mine = mountMsg(mediaData({ fileType }))
      const peer = mountMsg(mediaData({ fileType, sendUserId: 'U_OTHER', sendUserNickName: '对方' }))

      const which = (w) => {
        for (const m of ['stub-image', 'stub-video', 'stub-file']) {
          if (w.find(`[data-test="${m}"]`).exists()) return m
        }
        return '(无)'
      }
      expect(which(mine), `fileType=${fileType} 两侧渲染不一致`).toBe(which(peer))
      expect(which(mine), `fileType=${fileType} 自己侧未渲染任何媒体组件`).not.toBe('(无)')
    }
  })

  // fileType 越界时不得崩，也不得渲染出错误的媒体组件
  it('未知 fileType（99）不渲染任何媒体子组件，且不抛异常', () => {
    const mine = mountMsg(mediaData({ fileType: 99 }))
    const peer = mountMsg(mediaData({ fileType: 99, sendUserId: 'U_OTHER' }))
    for (const w of [mine, peer]) {
      for (const m of ['stub-image', 'stub-video', 'stub-file']) {
        expect(w.find(`[data-test="${m}"]`).exists()).toBe(false)
      }
    }
  })

  // 发送中（status=0）渲染骨架屏而非媒体组件
  it('发送中（status=0）渲染骨架屏而非媒体组件', () => {
    const w = mountMsg(mediaData({ fileType: 0, status: 0 }))
    expect(w.find('[data-test="stub-skeleton"]').exists()).toBe(true)
    expect(w.find('[data-test="stub-image"]').exists()).toBe(false)
  })
})