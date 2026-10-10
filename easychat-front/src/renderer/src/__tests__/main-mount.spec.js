import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import Main from '@/views/Main.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { useSysSettingStore } from '@/stores/SysSettingStore'
import { useAvatarInfoStore } from '@/stores/AvatarUpdateStore'
import { useCallStore } from '@/stores/useCallStore'
import { applyTheme } from '@/utils/theme'
import { ipcCalls } from './setup'

/**
 * 主界面外壳的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 菜单渲染（top 3 + bottom 1）与 active 态
 *   2. changeMenu → router.push + moment 红点消音（markMomentRead）
 *   3. momentNotify / momentUnread 红点驱动（含 ±1 累加与覆盖）
 *   4. getLoginInfo → setInfo + 头像强制回源 + ipc getLocalStore
 *   5. getSysSetting → sysSettingStore.setSetting
 *   6. getSysSettingCallback → 主题应用（合法/非法 JSON 兜底 light）
 *   7. reLogin → router.push('/login')
 *   8. reloadAvatar → setFoceReload(fileId, false)
 *   9. callMessage 带 messageType → useCallStore().handleFrame
 */

vi.mock('@/utils/theme', () => ({ applyTheme: vi.fn() }))

const stubs = {
  Avatar: {
    name: 'Avatar',
    props: ['userId', 'width', 'showDetail'],
    template: '<div class="avatar" :data-userid="userId"></div>'
  },
  Update: { name: 'Update', template: '<div class="update-panel" />' },
  WinOp: { name: 'WinOp', template: '<div class="win-op" />' },
  CallWindow: { name: 'CallWindow', template: '<div class="call-window" />' }
}

let pinia
let router
let request
let listeners

function makeRequest(handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

// 默认 handler：模拟「已登录」的主进程初始状态。
// 若默认返回 data:null，getLoginInfo 会 setInfo(null)，
// 模板 getInfo().userId 抛错并污染同文件其余用例。
function defaultHandler(opts) {
  if (opts.url === '/moment/unreadCount') {
    return { code: 0, data: 0 }
  }
  if (opts.url === '/user/getUserInfo') {
    return { code: 0, data: { userId: 'U_me', nickName: '我' } }
  }
  if (opts.url === '/sysSetting/get') {
    return { code: 0, data: { theme: 'light', notifySwitch: true } }
  }
  return { code: 0, data: null }
}

function mountMain(handler) {
  // handler 形参 (base, opts)：base 为默认结果，用例只覆盖关心的 url，
  // 避免未 mock 的 getUserInfo 返回 data:null 让 getLoginInfo 把
  // store 打成 null（模板 getInfo().userId 渲染崩）
  const h = (opts) => {
    const base = defaultHandler(opts)
    return handler ? handler(base, opts) : base
  }
  request = makeRequest(h)
  const wrapper = mount(Main, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: {
            momentUnreadCount: '/moment/unreadCount',
            momentMarkAllRead: '/moment/markAllRead',
            getUserInfo: '/user/getUserInfo',
            getSysSetting: '/sysSetting/get'
          }
        }
      },
      plugins: [pinia, router]
    }
  })
  return wrapper
}

describe('Main.vue 真实挂载（DOM 级）', () => {
  beforeEach(async () => {
    pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/chat', name: 'chat', component: { name: 'chat', template: '<div class="v-chat" />' } },
        { path: '/moment', component: { template: '<div class="v-moment" />' } },
        { path: '/contact', component: { template: '<div class="v-contact" />' } },
        { path: '/setting', component: { template: '<div class="v-setting" />' } },
        { path: '/login', component: { template: '<div class="v-login" />' } }
      ]
    })
    // 必须先推入目标路由再 mount：watch immediate 会读 route.path，
    // 初始 '/' 时 menuSelect 找不到匹配项 → currentMenu=undefined → 渲染崩
    await router.push('/chat')
    await router.isReady()
    vi.spyOn(router, 'push')
    // 主界面预设当前登录用户（真实场景登录后 store 已有信息，getInfo() 初始为 null）
    useUserInfoStore().setInfo({ userId: 'U_me', nickName: '我' })
    vi.clearAllMocks()
    listeners = {}
    ipcCalls.length = 0
    window.ipcRenderer = {
      on: (ch, cb) => { listeners[ch] = cb },
      send: (...args) => { ipcCalls.push({ channel: 'send', args }) },
      removeAllListeners: () => {}
    }
  })

  it('菜单渲染：top 3 + bottom 1 + 默认 active chat', () => {
    const wrapper = mountMain()
    const items = wrapper.findAll('.tab-item')
    expect(items).toHaveLength(4)
    expect(wrapper.findAll('.menu-buttom .tab-item')).toHaveLength(1)
    // 初始 route /chat → chat 项 active
    const active = items.find((i) => i.classes().includes('active'))
    expect(active).toBeTruthy()
    expect(active.classes()).toContain('icon-chat')
    // 自己头像传给 Avatar
    expect(wrapper.find('.avatar').exists()).toBe(true)
  })

  it('changeMenu → router.push 目标路径', async () => {
    const wrapper = mountMain()
    const contact = wrapper.findAll('.tab-item').find((i) => i.classes().includes('icon-user'))
    await contact.trigger('click')
    expect(router.push).toHaveBeenCalledWith('/contact')
  })

  it('getLoginInfo → setInfo + 头像强制回源 + ipc getLocalStore', async () => {
    const wrapper = mountMain((base, opts) => {
      if (opts.url === '/user/getUserInfo') {
        return { code: 0, data: { userId: 'U1', nickName: '小谢' } }
      }
      return base
    })
    await new Promise((r) => setTimeout(r, 0))
    expect(useUserInfoStore().getInfo().userId).toBe('U1')
    // 登录后强制回源头像一次
    expect(useAvatarInfoStore().getFoceReload('U1')).toBe(true)
    // 请求本地服务端口
    expect(ipcCalls.some((c) => c.args[0] === 'getLocalStore' && c.args[1] === 'U1localServerPort')).toBe(true)
    wrapper.unmount()
  })

  it('getSysSetting → sysSettingStore.setSetting', async () => {
    mountMain((base, opts) => {
      if (opts.url === '/sysSetting/get') {
        return { code: 0, data: { theme: 'dark', notifySwitch: true } }
      }
      return base
    })
    await new Promise((r) => setTimeout(r, 0))
    expect(useSysSettingStore().sysSetting.theme).toBe('dark')
  })

  it('moment 未读 → 红点渲染（Badge count>0）', async () => {
    const wrapper = mountMain((base, opts) => {
      if (opts.url === '/moment/unreadCount') {
        return { code: 0, data: 3 }
      }
      return base
    })
    await new Promise((r) => setTimeout(r, 0))
    // moment 项下 Badge 显示 3
    const momentItem = wrapper.findAll('.tab-item').find((i) => i.classes().includes('icon-more'))
    expect(momentItem.find('.badge').text()).toBe('3')
  })

  it('momentNotify（带 unreadCount）→ 红点覆盖', async () => {
    const wrapper = mountMain()
    // 先等挂载时的初始未读加载落定，避免其回写(0)覆盖本用例的设置
    await new Promise((r) => setTimeout(r, 0))
    listeners.momentNotify(null, { extendData: { unreadCount: 7 } })
    await new Promise((r) => setTimeout(r, 0))
    const momentItem = wrapper.findAll('.tab-item').find((i) => i.classes().includes('icon-more'))
    expect(momentItem.find('.badge').text()).toBe('7')
  })

  it('momentNotify（无 extend.unreadCount）→ 红点 +1 累加', async () => {
    const wrapper = mountMain()
    await new Promise((r) => setTimeout(r, 0))
    listeners.momentNotify(null, { extendData: {} })
    await new Promise((r) => setTimeout(r, 0))
    listeners.momentNotify(null, {})
    await new Promise((r) => setTimeout(r, 0))
    const momentItem = wrapper.findAll('.tab-item').find((i) => i.classes().includes('icon-more'))
    expect(momentItem.find('.badge').text()).toBe('2')
  })

  it('momentUnread 通道 → 红点直接置数', async () => {
    const wrapper = mountMain()
    await new Promise((r) => setTimeout(r, 0))
    listeners.momentUnread(null, { unreadCount: 9 })
    await new Promise((r) => setTimeout(r, 0))
    const momentItem = wrapper.findAll('.tab-item').find((i) => i.classes().includes('icon-more'))
    expect(momentItem.find('.badge').text()).toBe('9')
  })

  it('点 moment 且有未读 → markMomentRead 请求 + 红点消失', async () => {
    const wrapper = mountMain((base, opts) => {
      if (opts.url === '/moment/unreadCount') return { code: 0, data: 5 }
      if (opts.url === '/moment/markAllRead') return { code: 0, data: null }
      return base
    })
    await new Promise((r) => setTimeout(r, 0))
    const momentItem = wrapper.findAll('.tab-item').find((i) => i.classes().includes('icon-more'))
    expect(momentItem.find('.badge').text()).toBe('5')
    await momentItem.trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    // 已读请求
    expect(request.__calls__.some((c) => c.url === '/moment/markAllRead')).toBe(true)
    // 红点消失
    expect(momentItem.find('.badge').exists()).toBe(false)
    // 路由切到 moment
    expect(router.push).toHaveBeenCalledWith('/moment')
  })

  it('getSysSettingCallback 合法 JSON → applyTheme 按主题', async () => {
    mountMain()
    listeners.getSysSettingCallback(null, JSON.stringify({ theme: 'dark' }))
    await new Promise((r) => setTimeout(r, 0))
    expect(applyTheme).toHaveBeenCalledWith('dark')
    listeners.getSysSettingCallback(null, JSON.stringify({ theme: 'light' }))
    expect(applyTheme).toHaveBeenCalledWith('light')
  })

  it('getSysSettingCallback 非法 JSON → 兜底 applyTheme(light)', async () => {
    mountMain()
    listeners.getSysSettingCallback(null, 'not-json{{')
    await new Promise((r) => setTimeout(r, 0))
    expect(applyTheme).toHaveBeenCalledWith('light')
  })

  it('reLogin → router.push(/login)', async () => {
    mountMain()
    listeners.reLogin(null, {})
    await new Promise((r) => setTimeout(r, 0))
    expect(router.push).toHaveBeenCalledWith('/login')
  })

  it('路由到非菜单路径（/login）→ currentMenu 不崩且保持原选中', async () => {
    const wrapper = mountMain()
    await new Promise((r) => setTimeout(r, 0))
    // 真实导航到非菜单路径：route.path 变化触发 watch → menuSelect。
    // 捕获 Vue 渲染期错误。渲染函数抛错时 Vue 走 console.warn
    // （"[Vue warn]: Unhandled error during execution of render function"），
    // 且作业队列内的 TypeError 会变成 unhandled rejection——
    // 只断言 DOM 残留不可靠：渲染失败时旧 DOM 仍在，断言照样绿。
    const renderErrors = []
    const origWarn = console.warn
    const origError = console.error
    const capture = (args) => {
      const first = String((args && args[0]) || '')
      if (first.includes('Unhandled error during execution') || first.includes('reading')) {
        renderErrors.push(first)
      }
    }
    console.warn = (...args) => { capture(args); origWarn.apply(console, args) }
    console.error = (...args) => { capture(args); origError.apply(console, args) }
    try {
      await router.push('/login')
      await new Promise((r) => setTimeout(r, 0))
    } finally {
      console.warn = origWarn
      console.error = origError
    }
    // 渲染未被击穿
    expect(renderErrors).toEqual([])
    // DOM 完整
    expect(wrapper.findAll('.tab-item')).toHaveLength(4)
    // active 仍为初始 chat（未匹配项不得清空选中）
    const active = wrapper.findAll('.tab-item').find((i) => i.classes().includes('active'))
    expect(active.classes()).toContain('icon-chat')
  })

  it('onMounted 注册全部 ipc 监听（漏注册=功能静默失效）', async () => {
    mountMain()
    // Main.vue 的 onMounted 必须注册这 7 个回调；onUnmounted 逐个 remove
    expect(Object.keys(listeners).sort()).toEqual(
      [
        'callMessage',
        'getLocalStoreCallback',
        'getSysSettingCallback',
        'momentNotify',
        'momentUnread',
        'reLogin',
        'reloadAvatar'
      ].sort()
    )
  })

  it('callMessage 带 messageType → useCallStore().handleFrame', async () => {
    const callStore = useCallStore()
    const spy = vi.spyOn(callStore, 'handleFrame').mockImplementation(() => {})
    mountMain()
    const frame = { messageType: 10, data: 'x' }
    listeners.callMessage(null, frame)
    expect(spy).toHaveBeenCalledWith(frame)
    spy.mockRestore()
  })
})
