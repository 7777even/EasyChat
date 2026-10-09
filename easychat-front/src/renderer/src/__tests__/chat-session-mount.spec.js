import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatSession from '@/views/chat/ChatSession.vue'

/**
 * 会话列表项的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 群标签 / 昵称 / 时间 / 最后消息 / 未读 Badge / 头像透传
 *   2. currentSession → active 样式；topType=1 → 置顶图标
 *   3. 右键：单聊弹菜单（浮窗项 → emit floatingWindow 带 data）；
 *      群聊无菜单项（不弹）
 */

// 右键菜单是第三方库，mock 掉 showContextMenu 以观测调用参数
const showContextMenu = vi.fn()
vi.mock('@imengyu/vue3-context-menu', () => ({
  default: { showContextMenu: (...args) => showContextMenu(...args) }
}))

const stubs = {
  Badge: { name: 'Badge', props: ['count', 'top', 'left'], template: '<div class="badge" :data-count="count" />' },
  AvatarBase: { name: 'AvatarBase', props: ['width', 'userId', 'partType'], template: '<div class="avatar-base" :data-userid="userId" />' }
}

const formatDate = vi.fn(() => '2026-10-01 09:05')

function mountSession(data, currentSession = false) {
  return mount(ChatSession, {
    props: { data, currentSession },
    global: {
      stubs,
      plugins: [createPinia()],
      config: { globalProperties: { Utils: { formatDate } } }
    }
  })
}

describe('ChatSession.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    showContextMenu.mockClear()
  })

  it('单聊渲染：昵称/时间/最后消息/头像/未读数', () => {
    const wrapper = mountSession({
      contactType: 0,
      contactId: 'U_1',
      contactName: '张三',
      lastReceiveTime: 1700000000000,
      lastMessage: '你好',
      noReadCount: 3
    })
    expect(wrapper.find('.contact-tag').exists()).toBe(false)
    expect(wrapper.find('.user-name').text()).toBe('张三')
    expect(wrapper.find('.message-time').text()).toBe('2026-10-01 09:05')
    expect(wrapper.find('.last-message').text()).toBe('你好')
    expect(wrapper.findComponent({ name: 'AvatarBase' }).props('userId')).toBe('U_1')
    expect(wrapper.findComponent({ name: 'Badge' }).props('count')).toBe(3)
  })

  it('群聊渲染：群标签', () => {
    const wrapper = mountSession({ contactType: 1, contactId: 'G1', contactName: '开发群', lastMessage: '' })
    expect(wrapper.find('.contact-tag').text()).toBe('群')
  })

  it('currentSession → active 样式', () => {
    const wrapper = mountSession({ contactType: 0, contactId: 'U_1' }, true)
    expect(wrapper.find('.chat-session-item').classes()).toContain('active')
    const other = mountSession({ contactType: 0, contactId: 'U_2' }, false)
    expect(other.find('.chat-session-item').classes()).not.toContain('active')
  })

  it('topType=1 → 置顶图标', () => {
    const wrapper = mountSession({ contactType: 0, contactId: 'U_1', topType: 1 })
    expect(wrapper.find('.chat-top').exists()).toBe(true)
    const other = mountSession({ contactType: 0, contactId: 'U_2', topType: 0 })
    expect(other.find('.chat-top').exists()).toBe(false)
  })

  it('单聊右键 → 弹菜单且仅浮窗一项；点击浮窗 → emit floatingWindow 带 data', async () => {
    const data = { contactType: 0, contactId: 'U_1', contactName: '张三' }
    const wrapper = mountSession(data)
    // jsdom MouseEvent 的 x/y 是 clientX/clientY 的别名，init 里直接给 x 无效
    await wrapper.trigger('contextmenu', { clientX: 100, clientY: 200 })
    expect(showContextMenu).toHaveBeenCalledTimes(1)
    const opts = showContextMenu.mock.calls[0][0]
    expect(opts.x).toBe(100)
    expect(opts.y).toBe(200)
    expect(opts.items).toHaveLength(1)
    expect(opts.items[0].label).toBe('浮窗')
    opts.items[0].onClick()
    expect(wrapper.emitted('floatingWindow')).toEqual([[data]])
  })

  it('群聊右键 → 无菜单项（不弹）', async () => {
    const wrapper = mountSession({ contactType: 1, contactId: 'G1' })
    await wrapper.trigger('contextmenu', { x: 10, y: 20 })
    expect(showContextMenu).not.toHaveBeenCalled()
  })
})
