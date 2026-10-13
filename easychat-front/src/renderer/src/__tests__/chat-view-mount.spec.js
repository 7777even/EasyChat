import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

/**
 * `Chat.vue` 的**真实挂载**测试。
 *
 * ## 为什么现在写（台账 #7 的最后一块）
 * `Chat.vue` 是 79 个 `.vue` 里**唯一**没有组件级覆盖的。此前「不挂载」的理由是
 * 「180+ 行视图连带 stub 十余个依赖，成本远高于收益，且依赖一变即假红」。
 * 该理由经 2026-10-13 复核已**部分失效**：本仓最大 spec 已有 712 行，
 * `setup.js` 备好了 ResizeObserver / IntersectionObserver / matchMedia /
 * `window.ipcRenderer`(Proxy) / crypto / ObjectURL，视图级 spec 也已有
 * 「真实 router + 真实 pinia」的成熟范式（见 `group-detail-mount.spec.js`）。
 *
 * ## 本 spec 与源码门禁**不重叠**
 * `verify_chat_message_dispatch.mjs` 解析**源码**，覆盖模板里的消息分发条件
 * （`data.messageType == 1 || ... || 25`）——那是历史上真出过缺陷的一环，
 * 本 spec **不重复测它**。本 spec 覆盖门禁看不到的四类：
 *   ① 模板在真实 store / router 下**真能编译渲染**（含「组件解析失败」告警）
 *   ② 传给子组件的 **props 是否正确**
 *   ③ **事件绑定**是否接对了
 *   ④ IPC 订阅注册 / 卸载清理、列表渲染
 *
 * ## 子组件一律 stub 的理由
 * 14 个子组件**各自已有独立的挂载 spec**（78/79 覆盖）。此处 stub 是为了让本 spec
 * 聚焦「Chat.vue 自己怎么接线」，而不是重复测子组件内部。
 * ⚠ 计数口径：stub 掉子组件**不算**覆盖那些子组件（台账 #7 已写明
 * 「仅当 spec 真正 import 该组件才计覆盖」）——本 spec 计的是 **Chat.vue 自身**。
 */

const MessageStub = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
const ConfirmStub = vi.fn(() => Promise.resolve({ ok: true }))

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

// ── IPC 桩：能捕获监听器，从而**驱动**组件状态 ──────────────────
// setup.js 的 Proxy 只记录调用、无法触发回调；Chat.vue 的数据全部经
// `window.ipcRenderer.on(channel, cb)` 回流，必须能手动 fire 才能测三态渲染。
const ipcListeners = {}
const ipcCalls = []
const ipcStub = new Proxy({}, {
  get (_t, prop) {
    if (typeof prop !== 'string') return undefined
    if (prop === '__reset') {
      Object.keys(ipcListeners).forEach((k) => delete ipcListeners[k])
      ipcCalls.length = 0
      return true
    }
    if (prop === 'on') {
      return (channel, cb) => { (ipcListeners[channel] = ipcListeners[channel] || []).push(cb) }
    }
    if (prop === 'removeAllListeners') {
      return (channel) => {
        // ⚠ 必须**记录**这次调用：否则「onUnmounted 有没有调 removeAllListeners」
        //   这条断言永远查不到东西（`ipcCalls` 里根本没有它），会变成恒红的假覆盖。
        ipcCalls.push({ channel: 'removeAllListeners:' + String(channel), args: [] })
        delete ipcListeners[channel]
      }
    }
    return (...args) => { ipcCalls.push({ channel: prop, args }); return undefined }
  },
  has () { return true }
})
const fire = (channel, payload) => (ipcListeners[channel] || []).forEach((cb) => cb({}, payload))

// ── Element Plus 桩（本仓既有 spec 的统一做法）────────────────────
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'size'],
  emits: ['update:modelValue', 'keyup', 'keyup.enter'],
  template: `<input class="el-input" :value="modelValue" :placeholder="placeholder"
              @input="$emit('update:modelValue', $event.target.value)"
              @keyup="$emit('keyup', $event)"
              @keyup.enter="$emit('keyup.enter', $event)" />
            <span class="el-input-suffix"><slot name="suffix" /></span>`
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'disabled'],
  emits: ['click'],
  template: `<button class="el-button" :data-type="type" :disabled="disabled" @click="$emit('click')"><slot /></button>`
}
const ElIconStub = {
  name: 'ElIcon',
  template: '<i class="el-icon-stub"><slot /></i>'
}

// ── 业务子组件桩 ────────────────────────────────────────────────
// MessageVirtualList 必须真的渲染 default slot，否则模板里对
// `messageList[index-1].sendTime` 的引用根本走不到。
const MessageVirtualListStub = defineComponent({
  name: 'MessageVirtualList',
  props: { list: { type: Array, default: () => [] } },
  emits: ['scroll', 'load-more'],
  setup (props, { emit, slots, expose }) {
    expose({ scrollToMessage: () => true })
    return () => h('div', { class: 'message-panel-stub' },
      (props.list || []).map((item, index) =>
        slots.default ? slots.default({ item, index }) : h('div', String(item && item.messageId))))
  }
})

/**
 * 通用业务子组件桩。
 *
 * @param exposed 组件通过 `defineExpose` 暴露的方法。**必须按实际调用点补齐** ——
 *   `Chat.vue#chatSessionClickHandler` 里有一句裸调用
 *   `messageSendRef.value.cleanMessage()`，桩不暴露它就会抛
 *   「Unhandled error during execution of component event handler」，
 *   而那是个**测试装配问题**，不是产品缺陷，排查时极易误判。
 */
const stubComponent = (name, props = [], emits = [], exposed = {}) => defineComponent({
  name,
  props,
  emits,
  setup (_p, { slots, expose }) {
    expose(exposed)
    return () => h('div', { class: name.toLowerCase() + '-stub' }, slots.default ? slots.default() : [])
  }
})

/**
 * ⚠️ **刻意不桩 `AvatarBase`**：Chat.vue 模板第 22 行用了 `<AvatarBase>`，
 * 但它既没在 Chat.vue 里 import，也没在 main.js 里全局注册
 * （main.js 注册的是 `Avatar`，不是 `AvatarBase`）。
 * 不替它兜底，好让「Failed to resolve component」这条告警真正冒出来 ——
 * 见 T1 第一条用例。
 */
const CHILD_STUBS = {
  MessageVirtualList: MessageVirtualListStub,
  // ⚠ 模板里 ref 指向它，chatSessionClickHandler 有一句裸调用
  //   `messageSendRef.value.cleanMessage()`，桩不暴露它就会抛。
  MessageSend: stubComponent('MessageSend', [], [], { cleanMessage: vi.fn() }),
  ChatSession: stubComponent('ChatSession', ['data', 'currentSession'], ['click']),
  ChatMessage: stubComponent('ChatMessage', ['data', 'currentChatSession', 'multiSelectMode', 'selected']),
  ChatMessageTime: stubComponent('ChatMessageTime', ['data']),
  ChatMessageSys: stubComponent('ChatMessageSys', ['data']),
  SearchResult: stubComponent('SearchResult', ['data']),
  ChatGroupDetail: stubComponent('ChatGroupDetail'),
  GroupFile: stubComponent('GroupFile'),
  MessageSearch: stubComponent('MessageSearch'),
  ForwardSelect: stubComponent('ForwardSelect'),
  ReportDialog: stubComponent('ReportDialog', ['modelValue', 'type', 'messageId']),
  GlobalSearch: stubComponent('GlobalSearch'),
  Blank: stubComponent('Blank'),
  ContextMenu: stubComponent('ContextMenu')
}

const LayoutStub = defineComponent({
  name: 'Layout',
  setup (_p, { slots }) {
    return () => h('div', { class: 'layout-stub' }, [
      h('div', { class: 'layout-left' }, slots['left-content'] ? slots['left-content']() : []),
      h('div', { class: 'layout-right' }, slots['right-content'] ? slots['right-content']() : [])
    ])
  }
})

/**
 * main.js 里 `app.component(...)` 全局注册的组件。
 *
 * ⚠ **必须照抄生产装配**：Chat.vue 自己 import 了 `AvatarBase`，
 *   而 `AvatarBase.vue` 内部又用了全局注册的 `ShowLocalImage`。
 *   少注册任何一个，测试就会报「Failed to resolve component」——
 *   那会让人误以为是产品缺陷，实际上是测试没还原 `main.js` 的装配。
 *   （第一版就踩了这个坑：只补了 AvatarBase 的 import，随即冒出 ShowLocalImage 未解析。）
 */
const GLOBAL_COMPONENT_NAMES = [
  'WinOp', 'Avatar', 'Dialog', 'ContentPanel', 'UserBaseInfo', 'Badge', 'Table', 'ShowLocalImage'
]
const globalStubs = GLOBAL_COMPONENT_NAMES.reduce((acc, name) => {
  acc[name] = stubComponent(name)
  return acc
}, { Layout: LayoutStub })

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div/>' } },
    { path: '/chat', component: { template: '<div/>' } },
    { path: '/login', component: { template: '<div/>' } }
  ]
})

const requestStub = makeRequest(() => ({ code: 0, data: null }))

vi.mock('@/utils/Request', () => ({ default: (opts) => requestStub(opts) }))
vi.mock('@/utils/Message', () => ({ default: MessageStub }))
vi.mock('@/utils/Confirm', () => ({ default: ConfirmStub }))
vi.mock('@/utils/cloudBackup', () => ({ pullCloudHistory: vi.fn(() => Promise.resolve({ code: 0, data: null })) }))
vi.mock('@imengyu/vue3-context-menu', () => ({ default: stubComponent('ContextMenu') }))

const Chat = (await import('@/views/chat/Chat.vue')).default

/** 捕获 Vue 的渲染期告警（组件解析失败、渲染异常等） */
function captureVueWarnings () {
  const warnings = []
  const origWarn = console.warn
  const origError = console.error
  const record = (args) => {
    const text = (args || []).map((a) => String(a)).join(' ')
    if (/Failed to resolve component|Unhandled error during execution/i.test(text)) warnings.push(text)
  }
  console.warn = (...args) => { record(args); origWarn.apply(console, args) }
  console.error = (...args) => { record(args); origError.apply(console, args) }
  return { warnings, restore () { console.warn = origWarn; console.error = origError } }
}

function mountChat () {
  const spy = captureVueWarnings()
  window.ipcRenderer = ipcStub
  const wrapper = mount(Chat, {
    global: {
      components: {
        ...globalStubs,
        'el-input': ElInputStub,
        'el-button': ElButtonStub,
        'el-icon': ElIconStub
      },
      plugins: [createPinia(), router],
      stubs: CHILD_STUBS
    }
  })
  return { wrapper, spy }
}

const SESSIONS = [
  { contactId: 'C1', contactName: '小明', contactType: 0, sessionId: 'S1', noReadCount: 2, topType: 0, lastReceiveTime: 100 },
  { contactId: 'G1', contactName: '研发群', contactType: 1, sessionId: 'S2', noReadCount: 5, topType: 1, memberCount: 12, lastReceiveTime: 200 }
]

/**
 * 驱动：灌入会话列表并选中第一个（走真实 IPC 回调链）。
 *
 * ⚠ **注意排序**：`onLoadSessionData` 里会调 `sortChatSessionList`，
 *   规则是 **topType 降序 → lastReceiveTime 降序**。
 *   故传入的顺序**不等于**渲染顺序 —— 下例 G1（topType 1）会排到 C1 前面。
 *   这条行为本身值得单独断言（见「会话按置顶排序」用例）。
 */
async function driveSessions (wrapper, sessions = SESSIONS) {
  fire('loadSessionDataCallback', sessions)
  await nextTick()
  const first = wrapper.findAllComponents({ name: 'ChatSession' })[0]
  if (first) await first.vm.$emit('click', sessions[0])
  await nextTick()
  fire('loadChatMessage', { dataList: [], pageTotal: 1, pageNo: 1 })
  await nextTick()
}

beforeEach(() => {
  setActivePinia(createPinia())
  requestStub.__calls__.length = 0
  ipcStub.__reset
  window.ipcRenderer = ipcStub
  Object.keys(ipcListeners).forEach((k) => delete ipcListeners[k])
  ipcCalls.length = 0
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
  MessageStub.error.mockClear()
  ConfirmStub.mockClear()
})

afterEach(() => { vi.restoreAllMocks() })

// ==================== ① 挂载与组件解析 ====================

describe('T1 挂载与渲染', () => {
  it('★ 挂载不抛异常，且无「Failed to resolve component」告警', async () => {
    const { wrapper, spy } = mountChat()
    await nextTick()
    await nextTick()
    spy.restore()

    const unresolved = spy.warnings.filter((w) => w.includes('Failed to resolve component'))
    expect(
      unresolved.length,
      `模板里有组件既未 import 也未全局注册，渲染时会静默变成空节点：\n${unresolved.join('\n')}`
    ).toBe(0)

    const renderErrors = spy.warnings.filter((w) => w.includes('Unhandled error'))
    expect(renderErrors.length, renderErrors.join('\n')).toBe(0)
    wrapper.unmount()
  })

  it('未选中会话 → Blank 占位可见、聊天面板隐藏', async () => {
    const { wrapper, spy } = mountChat()
    await nextTick()
    spy.restore()

    expect(wrapper.find('.chat-blank').exists()).toBe(true)
    expect(wrapper.find('.chat-panel').attributes('style') || '').toContain('display: none')
    // title-panel 同样 v-if 未渲染
    expect(wrapper.find('.title-panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it('Layout 的左右插槽都渲染了内容', async () => {
    const { wrapper, spy } = mountChat()
    await nextTick()
    spy.restore()

    expect(wrapper.find('.layout-left').exists()).toBe(true)
    expect(wrapper.find('.layout-right').exists()).toBe(true)
    wrapper.unmount()
  })

  it('文件传输助手行常驻（不依赖会话列表）', async () => {
    const { wrapper, spy } = mountChat()
    await nextTick()
    spy.restore()

    const row = wrapper.find('.file-transfer-assistant')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('文件传输助手')
    wrapper.unmount()
  })
})

// ==================== ② props 传参 + 列表渲染 ====================

describe('T2 会话列表与 props 传参', () => {
  it('★ 会话按「置顶优先」排序（topType 降序），而非插入顺序', async () => {
    const { wrapper, spy } = mountChat()
    // 传入顺序 C1(topType 0) → G1(topType 1)，期望渲染顺序被置顶规则翻转
    fire('loadSessionDataCallback', SESSIONS)
    await nextTick()
    spy.restore()

    const sessions = wrapper.findAllComponents({ name: 'ChatSession' })
    expect(sessions.map((s) => s.props('data').contactId)).toEqual(['G1', 'C1'])
    wrapper.unmount()
  })

  it('同 topType 时按最后接收时间降序', async () => {
    const { wrapper, spy } = mountChat()
    fire('loadSessionDataCallback', [
      { contactId: 'A', contactName: '甲', contactType: 0, sessionId: 'SA', noReadCount: 0, topType: 0, lastReceiveTime: 100 },
      { contactId: 'B', contactName: '乙', contactType: 0, sessionId: 'SB', noReadCount: 0, topType: 0, lastReceiveTime: 300 },
      { contactId: 'C', contactName: '丙', contactType: 0, sessionId: 'SC', noReadCount: 0, topType: 0, lastReceiveTime: 200 }
    ])
    await nextTick()
    spy.restore()

    const ids = wrapper.findAllComponents({ name: 'ChatSession' }).map((s) => s.props('data').contactId)
    expect(ids).toEqual(['B', 'C', 'A'])
    wrapper.unmount()
  })

  it('选中会话 → currentSession 只标记当前项', async () => {
    const { wrapper, spy } = mountChat()
    await driveSessions(wrapper)
    spy.restore()

    const sessions = wrapper.findAllComponents({ name: 'ChatSession' })
    expect(sessions.length).toBe(SESSIONS.length)
    // 排序后第一个被选中 → 只有它是 current
    expect(sessions.map((s) => s.props('currentSession'))).toEqual([true, false])
    wrapper.unmount()
  })

  it('会话 data 原样透传给 ChatSession（未在传递途中丢失字段）', async () => {
    const { wrapper, spy } = mountChat()
    fire('loadSessionDataCallback', SESSIONS)
    await nextTick()
    spy.restore()

    const sessions = wrapper.findAllComponents({ name: 'ChatSession' })
    expect(sessions[0].props('data').contactId).toBe('G1')
    expect(sessions[1].props('data').contactName).toBe('小明')
    // 群会话的 memberCount 必须透传（标题栏要用它显示成员数）
    expect(sessions[0].props('data').memberCount).toBe(12)
    wrapper.unmount()
  })

  it('单聊会话 → 标题不显示成员数括号', async () => {
    const { wrapper, spy } = mountChat()
    fire('loadSessionDataCallback', SESSIONS)
    await nextTick()
    // 显式点单聊 C1（排序后是第二个）
    const sessions = wrapper.findAllComponents({ name: 'ChatSession' })
    await sessions[1].vm.$emit('click', SESSIONS[0])
    await nextTick()
    spy.restore()

    expect(wrapper.find('.title-panel').text()).toContain('小明')
    expect(wrapper.find('.title-panel').text()).not.toContain('(12)')
    wrapper.unmount()
  })

  it('群会话 → 标题显示成员数，且群专属按钮出现', async () => {
    const { wrapper, spy } = mountChat()
    fire('loadSessionDataCallback', SESSIONS)
    await nextTick()
    const sessions = wrapper.findAllComponents({ name: 'ChatSession' })
    await sessions[0].vm.$emit('click', SESSIONS[1])   // 选群 G1
    await nextTick()
    spy.restore()

    const title = wrapper.find('.title-panel')
    expect(title.text()).toContain('研发群')
    expect(title.text()).toContain('(12)')
    // contactType == 1 才有的两个按钮
    expect(wrapper.find('[title="群文件"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('单聊 → 群专属按钮不出现', async () => {
    const { wrapper, spy } = mountChat()
    fire('loadSessionDataCallback', SESSIONS)
    await nextTick()
    const sessions = wrapper.findAllComponents({ name: 'ChatSession' })
    await sessions[1].vm.$emit('click', SESSIONS[0])   // 选单聊 C1
    await nextTick()
    spy.restore()

    expect(wrapper.find('[title="群文件"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

// ==================== ③ 事件绑定 ====================

describe('T3 事件绑定', () => {
  it('搜索框输入 → 会话列表隐藏、搜索结果区显示', async () => {
    const { wrapper, spy } = mountChat()
    await nextTick()
    spy.restore()

    const input = wrapper.find('.top-search .el-input')
    expect(input.exists()).toBe(true)

    await input.setValue('小明')
    await nextTick()

    expect(wrapper.find('.chat-session-list').attributes('style') || '').toContain('display: none')
    expect(wrapper.find('.search-list').attributes('style') || '').not.toContain('display: none')
    wrapper.unmount()
  })

  it('清空搜索词 → 恢复会话列表', async () => {
    const { wrapper, spy } = mountChat()
    await driveSessions(wrapper)
    spy.restore()

    const input = wrapper.find('.top-search .el-input')
    await input.setValue('x')
    await nextTick()
    await input.setValue('')
    await nextTick()

    expect(wrapper.find('.chat-session-list').attributes('style') || '').not.toContain('display: none')
    wrapper.unmount()
  })

  it('MessageVirtualList 收到 messageList 并订阅 scroll / load-more', async () => {
    const { wrapper, spy } = mountChat()
    await driveSessions(wrapper)
    spy.restore()

    const list = wrapper.findComponent({ name: 'MessageVirtualList' })
    expect(list.exists()).toBe(true)
    expect(Array.isArray(list.props('list'))).toBe(true)
    // 两个事件都要能被模板触发（触发不抛 = 绑定有效）
    expect(() => list.vm.$emit('scroll', { distanceBottom: 0 })).not.toThrow()
    expect(() => list.vm.$emit('load-more')).not.toThrow()
    wrapper.unmount()
  })

  it('聊天背景为空时不渲染背景层', async () => {
    const { wrapper, spy } = mountChat()
    await driveSessions(wrapper)
    spy.restore()

    // 默认无聊天背景 → .chat-background 不应出现
    expect(wrapper.find('.chat-background').exists()).toBe(false)
    wrapper.unmount()
  })
})

// ==================== ④ 浮层与 IPC 生命周期 ====================

describe('T4 浮层与 IPC 订阅', () => {
  it('举报弹窗初始关闭且 type 固定为 message', async () => {
    const { wrapper, spy } = mountChat()
    await nextTick()
    spy.restore()

    const dialog = wrapper.findComponent({ name: 'ReportDialog' })
    expect(dialog.exists()).toBe(true)
    expect(dialog.props('modelValue')).toBe(false)
    expect(dialog.props('type')).toBe('message')
    wrapper.unmount()
  })

  it('挂载后注册了会话 / 消息 / 未读等 IPC 监听', async () => {
    const { spy } = mountChat()
    await nextTick()
    spy.restore()

    for (const ch of ['loadSessionDataCallback', 'reciveMessage', 'loadChatMessage', 'typingStatus']) {
      expect(Object.keys(ipcListeners), `未注册 IPC 监听：${ch}`).toContain(ch)
    }
  })

  it('卸载后清理 IPC 监听（不泄漏回调）', async () => {
    const { wrapper, spy } = mountChat()
    await nextTick()
    spy.restore()

    const before = Object.keys(ipcListeners)
    expect(before.length, '挂载时应注册了一批监听').toBeGreaterThan(0)

    wrapper.unmount()

    // ⚠ 断言写成**字符串比较**而非数组 diff：Windows 控制台 GBK 会把长数组
    //   diff 截断成乱码，排查时根本看不出残留了哪些频道。
    const leaked = Object.keys(ipcListeners).sort()
    expect(
      leaked.join(', '),
      `卸载后仍残留 ${leaked.length} 个 IPC 监听（挂载时注册了 ${before.length} 个）。` +
      '每次进出 /chat 都会再累积一批 —— 例如 playNotifySound 累积会导致新消息提示音重复播放。'
    ).toBe('')
  })

  it('卸载后调用了 removeAllListeners（以 IPC 侧为准，不臆测 addEventListener）', async () => {
    const { wrapper, spy } = mountChat()
    await nextTick()
    spy.restore()

    // ⚠ 顺序：**必须先 unmount 再查**。removeAllListeners 挂在 onUnmounted 上，
    //   卸载前查必然是 0 条 —— 我第一版就是这么写的，直接恒红。
    expect(ipcCalls.filter((c) => c.channel.startsWith('removeAllListeners:')).length)
      .toBe(0)
    wrapper.unmount()
    expect(ipcCalls.filter((c) => c.channel.startsWith('removeAllListeners:')).length,
      'onUnmounted 应调用 removeAllListeners').toBeGreaterThan(0)
  })
})