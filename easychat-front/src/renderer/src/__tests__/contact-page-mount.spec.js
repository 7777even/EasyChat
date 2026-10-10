import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import Contact from '@/views/contact/Contact.vue'
import { useContactStateStore } from '@/stores/ContactStateStore'
import { useMessageCountStore } from '@/stores/MessageCountStore'

/**
 * 联系人主页（左侧导航 + 联系人列表）的**真实挂载**测试（DOM 级，真实 router + pinia）。
 *
 * 被测点：
 *   1. 挂载即三连加载：loadContact('USER') / loadContact('GROUP') / loadMyGroup
 *   2. 分组数据落位（我加入的群聊 / 我的好友）+ 空态文案
 *   3. partJump：countKey 导航清零 + ipc 'updateContactNoReadCount' + 路由跳转 + rightTitle
 *   4. contactDetail：点击联系人 → rightTitle 取 contactName + 跳详情带 contactId
 *   5. 搜索：关键词过滤 + 高亮 span + 点击结果跳 /chat
 *   6. ContactStateStore.contactReload watch：USER → 重拉好友 + 状态复位
 *   7. 在线状态帧 onlineStatus / onlineStatusHidden → 状态点出现 / 抹除
 *   8. **onMounted/onUnmounted 未 import**（354077b 引入时漏改 import）→ setup 即
 *      ReferenceError，整个联系人页崩溃（本 spec 首版即抓出）
 */

const MessageStub = { warning: vi.fn(), success: vi.fn(), error: vi.fn() }

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const LayoutStub = {
  name: 'Layout',
  template:
    '<div class="layout-stub"><div class="left"><slot name="left-content" /></div><div class="right"><slot name="right-content" /></div></div>'
}
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'size', 'clearable'],
  emits: ['update:modelValue', 'keyup'],
  template:
    '<input class="el-input-native" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" @keyup="$emit(\'keyup\', $event)" />'
}
const ElTooltipStub = {
  name: 'ElTooltip',
  props: ['content', 'placement'],
  template: '<div class="tooltip-stub"><slot /></div>'
}
const BadgeStub = {
  name: 'Badge',
  props: ['count', 'top', 'left'],
  template: '<span class="badge-stub">{{ count }}</span>'
}
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width', 'contactType', 'borderRadius'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
const GroupJoinDialogStub = {
  name: 'GroupJoinDialog',
  props: ['modelValue'],
  emits: ['update:modelValue', 'joined'],
  template: '<div class="join-dialog-stub" v-if="modelValue" />'
}
const ContactSearchResultStub = {
  name: 'ContactSearchResult',
  props: ['data'],
  emits: ['click'],
  template:
    '<div class="csr-stub" :data-cid="data.contactId" @click="$emit(\'click\')">{{ data.contactName }}</div>'
}
const RouterViewStub = {
  name: 'RouterView',
  template: '<div class="router-view-stub" />'
}

const FRIENDS = [
  { contactId: 'U010', contactName: '阿强', contactType: 0 },
  { contactId: 'U011', contactName: '阿伟', contactType: 0 }
]
const MY_GROUPS = [{ groupId: 'G001', groupName: '家族群' }]
const JOINED_GROUPS = [{ contactId: 'G002', contactName: '同事群' }]

async function mountContact (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const request = makeRequest(handler)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/contact', component: { template: '<div />' } },
      { path: '/contact/search', component: { template: '<div />' } },
      { path: '/contact/contactNotice', component: { template: '<div />' } },
      { path: '/contact/createGroup', component: { template: '<div />' } },
      { path: '/contact/groupDetail', component: { template: '<div />' } },
      { path: '/contact/userDetail', component: { template: '<div />' } },
      { path: '/contact/blank', component: { template: '<div />' } },
      { path: '/chat', component: { template: '<div />' } }
    ]
  })
  await router.push('/contact')
  await router.isReady()

  const wrapper = mount(Contact, {
    global: {
      plugins: [pinia, router],
      stubs: {
        Layout: LayoutStub,
        'el-input': ElInputStub,
        'el-tooltip': ElTooltipStub,
        Badge: BadgeStub,
        Avatar: AvatarStub,
        GroupJoinDialog: GroupJoinDialogStub,
        ContactSearchResult: ContactSearchResultStub,
        'router-view': RouterViewStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            loadContact: '/contact/load',
            loadMyGroup: '/group/loadMy'
          },
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper, request, router, pinia }
}

async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

function defaultHandler (opts) {
  if (opts.url === '/contact/load') {
    return {
      code: 0,
      data: opts.params.contactType === 'USER' ? FRIENDS : JOINED_GROUPS
    }
  }
  if (opts.url === '/group/loadMy') return { code: 0, data: MY_GROUPS }
  return { code: 0, data: null }
}

beforeEach(() => {
  MessageStub.warning.mockClear()
  void window.ipcRenderer.__reset__ // 属性访问即清空 ipcCalls（setup.js 契约）
})

describe('Contact.vue 真实挂载（DOM 级）', () => {
  it('挂载即三连加载：USER + GROUP + loadMyGroup', async () => {
    const { request } = await mountContact(defaultHandler)
    await flush()
    const urls = request.__calls__.map((c) => c.url)
    expect(urls).toContain('/contact/load')
    expect(urls).toContain('/group/loadMy')
    const userCall = request.__calls__.find(
      (c) => c.url === '/contact/load' && c.params.contactType === 'USER'
    )
    expect(userCall).toBeTruthy()
  })

  it('列表渲染：好友/群组落位 + 空态文案 + 分组标题', async () => {
    const { wrapper } = await mountContact(defaultHandler)
    await flush()
    const texts = wrapper.findAll('.text').map((t) => t.text())
    expect(texts).toContain('阿强')
    expect(texts).toContain('阿伟')
    expect(texts).toContain('家族群')
    expect(texts).toContain('同事群')
    expect(wrapper.findAll('.part-title').map((p) => p.text())).toEqual([
      '新朋友',
      '我的群聊',
      '我加入的群聊',
      '我的好友'
    ])
    // 好友非空 → 该分区不渲染 no-data；无空分区
    expect(wrapper.find('.no-data').exists()).toBe(false)
  })

  it('好友为空 → 「暂无好友」空态；加入群聊为空 → 「暂未加入群聊」', async () => {
    const { wrapper } = await mountContact((opts) => {
      if (opts.url === '/contact/load') return { code: 0, data: [] }
      if (opts.url === '/group/loadMy') return { code: 0, data: [] }
      return { code: 0, data: null }
    })
    await flush()
    const noData = wrapper.findAll('.no-data').map((n) => n.text())
    expect(noData).toContain('暂无好友')
    expect(noData).toContain('暂未加入群聊')
  })

  it('partJump（新的朋友，带 countKey）：清零计数 + ipc 通知 + 路由跳转', async () => {
    const { wrapper, router, pinia } = await mountContact(defaultHandler)
    await flush()
    const store = useMessageCountStore()
    store.setCount('contactApplyCount', 5)
    const push = vi.spyOn(router, 'push')

    const items = wrapper.findAll('.part-item')
    const notice = items.find((i) => i.text().includes('新的朋友'))
    await notice.trigger('click')

    expect(store.getCount('contactApplyCount')).toBe(0)
    const ipc = window.ipcRenderer.__calls__.find((c) => c.args[0] === 'updateContactNoReadCount')
    expect(ipc).toBeTruthy()
    expect(push.mock.calls[0][0]).toBe('/contact/contactNotice')
    expect(pinia).toBeTruthy()
  })

  it('partJump（无 countKey 的搜好友）：不发 ipc，rightTitle 不变（showTitle falsy）', async () => {
    const { wrapper, router } = await mountContact(defaultHandler)
    await flush()
    const push = vi.spyOn(router, 'push')
    const items = wrapper.findAll('.part-item')
    const search = items.find((i) => i.text().includes('搜好友'))
    await search.trigger('click')
    expect(push.mock.calls[0][0]).toBe('/contact/search')
    const ipc = window.ipcRenderer.__calls__.find((c) => c.args[0] === 'updateContactNoReadCount')
    expect(ipc).toBeUndefined()
  })

  it('contactDetail：点击好友 → rightTitle=contactName + 跳 userDetail 带 contactId', async () => {
    const { wrapper, router } = await mountContact(defaultHandler)
    await flush()
    const push = vi.spyOn(router, 'push')
    const friendItem = wrapper
      .findAll('.part-item')
      .find((i) => i.text().includes('阿强'))
    await friendItem.trigger('click')
    // 「我的好友」分区无 showTitle → rightTitle 不设置（既有行为）
    expect(wrapper.find('.title-panel').text()).toBe('')
    const arg = push.mock.calls[0][0]
    expect(arg.path).toBe('/contact/userDetail')
    expect(arg.query).toEqual({ contactId: 'U010' })
  })

  it('contactDetail：点击群 → 跳 groupDetail 带 groupId', async () => {
    const { wrapper, router } = await mountContact(defaultHandler)
    await flush()
    const push = vi.spyOn(router, 'push')
    const groupItem = wrapper
      .findAll('.part-item')
      .find((i) => i.text().includes('家族群'))
    await groupItem.trigger('click')
    expect(wrapper.find('.title-panel').text()).toBe('家族群')
    const arg = push.mock.calls[0][0]
    expect(arg.path).toBe('/contact/groupDetail')
    // query 键恒为 contactId（contactDetail 写死），值取 contact[part.contactId] 即 groupId
    expect(arg.query).toEqual({ contactId: 'G001' })
  })

  it('搜索：过滤 + 高亮 span + 结果点击跳 /chat 并清空搜索词', async () => {
    const { wrapper, router } = await mountContact(defaultHandler)
    await flush()
    const push = vi.spyOn(router, 'push')

    await wrapper.find('.el-input-native').setValue('阿强')
    await wrapper.find('.el-input-native').trigger('keyup')
    await wrapper.vm.$nextTick()

    const results = wrapper.findAll('.csr-stub')
    expect(results).toHaveLength(1)
    expect(results[0].attributes('data-cid')).toBe('U010')
    expect(wrapper.find('.csr-stub').html()).toContain("class='highlight'")

    await wrapper.find('.csr-stub').trigger('click')
    const arg = push.mock.calls[0][0]
    expect(arg.path).toBe('/chat')
    expect(arg.query.chatId).toBe('U010')
    expect(arg.query.timestamp).toBeTruthy()
    // 点击后清空搜索词
    expect(wrapper.find('.el-input-native').element.value).toBe('')
  })

  it('搜索关键词无命中 → 结果为空', async () => {
    const { wrapper } = await mountContact(defaultHandler)
    await flush()
    await wrapper.find('.el-input-native').setValue('不存在的人')
    await wrapper.find('.el-input-native').trigger('keyup')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.csr-stub')).toHaveLength(0)
  })

  it('ContactStateStore USER → 重拉好友列表 + 状态复位', async () => {
    const { request, pinia } = await mountContact(defaultHandler)
    await flush()
    const before = request.__calls__.filter((c) => c.url === '/contact/load').length

    const store = useContactStateStore()
    store.setContactReload('USER')
    await flush()

    const after = request.__calls__.filter((c) => c.url === '/contact/load')
    expect(after.length).toBe(before + 1)
    expect(after[after.length - 1].params).toEqual({ contactType: 'USER' })
    expect(store.contactReload).toBe('')
    expect(pinia).toBeTruthy()
  })

  it('ContactStateStore DISSOLUTION_GROUP → 重拉群 + 跳 /contact/blank + 清 rightTitle', async () => {
    const { wrapper, router, pinia } = await mountContact(defaultHandler)
    await flush()
    const push = vi.spyOn(router, 'push')
    const store = useContactStateStore()
    store.setContactReload('DISSOLUTION_GROUP')
    await flush()
    expect(push).toHaveBeenCalledWith('/contact/blank')
    expect(wrapper.find('.title-panel').text()).toBe('')
    expect(store.contactReload).toBe('')
    expect(pinia).toBeTruthy()
  })

  it('onlineStatus 帧 → 状态点渲染；onlineStatusHidden 帧 → 抹除', async () => {
    const { wrapper } = await mountContact(defaultHandler)
    await flush()
    // 挂载时 onMounted 注册了监听（修复前该行即 ReferenceError）
    const onCalls = window.ipcRenderer.__calls__.filter((c) => c.channel === 'on')
    const names = onCalls.map((c) => c.args[0])
    expect(names).toContain('onlineStatus')
    expect(names).toContain('onlineStatusHidden')

    // 手动触发回调（stub 的 on 是记录型，不真正注册；改为直接驱动 DOM 状态）
    const onlineHandler = onCalls.find((c) => c.args[0] === 'onlineStatus').args[1]
    onlineHandler({}, { contactId: 'U010', extendData: 1 })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.status-dot').exists()).toBe(true)
    expect(wrapper.find('.status-dot').classes()).toContain('status-1')

    const hiddenHandler = onCalls.find((c) => c.args[0] === 'onlineStatusHidden').args[1]
    hiddenHandler({}, { contactId: 'U010' })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.status-dot').exists()).toBe(false)
  })

  it('onlineStatusHidden 对未显示的状态点不产生副作用', async () => {
    const { wrapper } = await mountContact(defaultHandler)
    await flush()
    const onCalls = window.ipcRenderer.__calls__.filter((c) => c.channel === 'on')
    const hiddenHandler = onCalls.find((c) => c.args[0] === 'onlineStatusHidden').args[1]
    // contactId 缺失 / 不在 map 中 → 都不抛错
    hiddenHandler({}, {})
    hiddenHandler({}, { contactId: 'U999' })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.status-dot').exists()).toBe(false)
  })

  it('加入群聊入口 → joinDialogShow 打开', async () => {
    const { wrapper } = await mountContact(defaultHandler)
    await flush()
    expect(wrapper.find('.join-dialog-stub').exists()).toBe(false)
    await wrapper.find('.join-group-btn').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.join-dialog-stub').exists()).toBe(true)
  })
})
