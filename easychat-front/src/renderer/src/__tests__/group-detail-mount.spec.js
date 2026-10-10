import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import GroupDetail from '@/views/contact/GroupDetail.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { useContactStateStore } from '@/stores/ContactStateStore'

/**
 * 群详情页的**真实挂载**测试（DOM 级，真实 router + 真实 pinia store）。
 *
 * 被测点：
 *   1. route.query.contactId watch（immediate）→ getGroupInfo + loadMyRole 双请求
 *   2. 群信息渲染：群ID / 群名称 / 群主 / 成员数 / 我的角色 / 加入权限 / 公告
 *   3. 我的角色三态（0 群主 / 1 管理员 / 2 成员）→ roleText 与下拉菜单分支
 *   4. canMangeMember 守卫：自己不可管理、群主不可被管理、管理员只能管成员
 *   5. onSetAdmin / onMuteMember / onTransferOwner 的守卫与参数
 *   6. 公告编辑 toggle（管理员可编 / 非管理员 warning）+ submitNotice 参数
 *   7. 解散群 / 退群 Confirm 链路与 contactStateStore 刷新标记
 *   8. sendMessage → 跳 /chat 带 chatId
 *   9. 二维码 / 邀请链接弹窗（仅管理员可见按钮）
 */

const MessageStub = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
const ConfirmStub = vi.fn()

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const GROUP = {
  groupId: 'G001',
  groupName: '研发群',
  groupOwnerId: 'U001',
  groupOwnerNickName: '小明',
  memberCount: 12,
  joinType: 0,
  groupNotice: '周末迭代'
}
const CHAT_INFO = {
  userContactList: [
    { userId: 'U001', role: 0 },
    { userId: 'U002', role: 1 },
    { userId: 'U003', role: 2 }
  ]
}
const MEMBER_LIST = {
  list: [
    { userId: 'U001', contactName: '小明', role: 0 },
    { userId: 'U002', contactName: '阿强', role: 1 },
    { userId: 'U003', contactName: '阿伟', role: 2 },
    { userId: 'U004', contactName: '小李', role: 2, muteEndTime: 9999999999999 }
  ],
  total: 4
}

const ContentPanelStub = {
  name: 'ContentPanel',
  props: ['showTopBorder'],
  template: '<div class="content-panel-stub"><slot /></div>'
}
const DialogStub = {
  name: 'Dialog',
  props: ['show', 'title', 'buttons', 'width', 'showCancel'],
  emits: ['close'],
  template:
    '<div class="dialog-stub" v-if="show"><div class="d-title">{{ title }}</div><div class="d-body"><slot /></div>' +
    '<button v-for="b in buttons" :key="b.text" class="d-btn" @click="b.click">{{ b.text }}</button>' +
    '<button class="d-close" @click="$emit(\'close\')">X</button></div>'
}
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'contactType', 'size', 'showName'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
const GroupEditDialogStub = {
  name: 'GroupEditDialog',
  props: [],
  emits: ['reloadGroupInfo'],
  setup (_, { expose }) {
    const show = vi.fn()
    expose({ show })
    return { show }
  },
  template: '<div class="ged-stub" />'
}
const ElDropdownStub = {
  name: 'ElDropdown',
  props: ['placement', 'trigger'],
  template: '<div class="dropdown-stub"><slot /><div class="dd-menu"><slot name="dropdown" /></div></div>'
}
const ElDropdownMenuStub = {
  name: 'ElDropdownMenu',
  template: '<div class="dd-items"><slot /></div>'
}
const ElDropdownItemStub = {
  name: 'ElDropdownItem',
  props: ['divided'],
  emits: ['click'],
  template: '<div class="dd-item" @click="$emit(\'click\')"><slot /></div>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'link'],
  emits: ['click'],
  template: '<button class="el-button" :data-type="type" @click="$emit(\'click\')"><slot /></button>'
}
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'maxlength', 'type', 'rows', 'showWordLimit', 'resize', 'controlsPosition'],
  emits: ['update:modelValue'],
  template:
    '<input class="el-input-native" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" />'
}
const ElTagStub = {
  name: 'ElTag',
  props: ['type', 'size'],
  template: '<span class="el-tag-stub"><slot /></span>'
}
const ElDialogStub = {
  name: 'ElDialog',
  props: ['show', 'title', 'width', 'appendToBody'],
  emits: ['close'],
  template:
    '<div class="el-dialog-stub" v-if="show"><div class="ed-title">{{ title }}</div><slot /></div>'
}
const ElInputNumberStub = {
  name: 'ElInputNumber',
  props: ['modelValue', 'min', 'max', 'controlsPosition'],
  emits: ['update:modelValue'],
  template: '<input class="num-input" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />'
}
const ElFormStub = { name: 'ElForm', template: '<form class="el-form-stub"><slot /></form>' }
const ElFormItemStub = {
  name: 'ElFormItem',
  props: ['label'],
  template: '<div class="el-form-item-stub"><slot /></div>'
}

const Stubs = {
  ContentPanel: ContentPanelStub,
  Dialog: DialogStub,
  Avatar: AvatarStub,
  GroupEditDialog: GroupEditDialogStub,
  'el-dropdown': ElDropdownStub,
  'el-dropdown-menu': ElDropdownMenuStub,
  'el-dropdown-item': ElDropdownItemStub,
  'el-button': ElButtonStub,
  'el-input': ElInputStub,
  'el-tag': ElTagStub,
  'el-dialog': ElDialogStub,
  'el-input-number': ElInputNumberStub,
  'el-form': ElFormStub,
  'el-form-item': ElFormItemStub
}

async function mountDetail (handler, { path = '/contact/groupDetail', query = { contactId: 'G001' } } = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const request = makeRequest(handler)
  const userInfo = useUserInfoStore()
  userInfo.setInfo({ userId: 'U001', nickName: '小明' })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/contact/groupDetail', component: { template: '<div />' } },
      { path: '/chat', component: { template: '<div />' } }
    ]
  })
  await router.push({ path, query })
  await router.isReady()
  const wrapper = mount(GroupDetail, {
    global: {
      plugins: [pinia, router],
      stubs: Stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: {
            getGroupInfo: '/group/getGroupInfo',
            getGroupInfo4Chat: '/group/getGroupInfo4Chat',
            generateGroupQrCode: '/group/genQr',
            generateGroupInvite: '/group/genInvite',
            dissolutionGroup: '/group/dissolution',
            leaveGroup: '/group/leave',
            editNotice: '/group/editNotice',
            memberList: '/group/memberList',
            setAdmin: '/group/setAdmin',
            muteMember: '/group/muteMember',
            transferOwner: '/group/transferOwner'
          },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  return { wrapper, request, router, pinia }
}

function defaultHandler (opts) {
  if (opts.url === '/group/getGroupInfo') return { code: 0, data: GROUP }
  if (opts.url === '/group/getGroupInfo4Chat') return { code: 0, data: CHAT_INFO }
  if (opts.url === '/group/memberList') return { code: 0, data: MEMBER_LIST }
  return { code: 0, data: null }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
  ConfirmStub.mockClear()
})

describe('GroupDetail.vue 真实挂载（DOM 级）', () => {
  it('immediate watch → getGroupInfo + loadMyRole 双请求（带 groupId）', async () => {
    const { request } = await mountDetail(defaultHandler)
    await flush()
    const urls = request.__calls__.map((c) => c.url)
    expect(urls).toContain('/group/getGroupInfo')
    expect(urls).toContain('/group/getGroupInfo4Chat')
    const info = request.__calls__.find((c) => c.url === '/group/getGroupInfo')
    expect(info.params).toEqual({ groupId: 'G001' })
  })

  it('群信息渲染：ID/名称/群主/成员数/角色/加入权限/公告', async () => {
    const { wrapper } = await mountDetail(defaultHandler)
    await flush()
    const items = wrapper.findAll('.group-info-item')
    const byTitle = (t) => items.find((i) => i.find('.group-title').text() === t)
    expect(byTitle('群ID：').find('.group-value').text()).toBe('G001')
    expect(byTitle('群名称：').find('.group-value').text()).toBe('研发群')
    expect(byTitle('群主：').find('.group-value').text()).toBe('小明')
    expect(byTitle('群成员：').find('.group-value').text()).toBe('12')
    expect(byTitle('我的角色：').find('.group-value').text()).toBe('群主')
    expect(byTitle('加入权限：').find('.group-value').text()).toContain('直接加入')
    expect(byTitle('公告：').find('.group-value').text()).toBe('周末迭代')
  })

  it('joinType=1 → 「管理员同意后加入」', async () => {
    const { wrapper } = await mountDetail((opts) => {
      if (opts.url === '/group/getGroupInfo') return { code: 0, data: { ...GROUP, joinType: 1 } }
      return defaultHandler(opts)
    })
    await flush()
    const items = wrapper.findAll('.group-info-item')
    const join = items.find((i) => i.find('.group-title').text() === '加入权限：')
    expect(join.find('.group-value').text()).toContain('管理员同意后加入')
  })

  it('我的角色：管理员（U002）→ roleText「管理员」+ 管理员下拉菜单（含退出该群、无解散）', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useUserInfoStore().setInfo({ userId: 'U002', nickName: '阿强' })
    const request = makeRequest(defaultHandler)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/contact/groupDetail', component: { template: '<div />' } }]
    })
    await router.push({ path: '/contact/groupDetail', query: { contactId: 'G001' } })
    await router.isReady()
    const wrapper = mount(GroupDetail, {
      global: {
        plugins: [pinia, router],
        stubs: Stubs,
        config: {
          globalProperties: {
            Request: request,
            Api: {
              getGroupInfo: '/group/getGroupInfo',
              getGroupInfo4Chat: '/group/getGroupInfo4Chat',
              memberList: '/group/memberList',
              editNotice: '/group/editNotice'
            },
            Message: MessageStub,
            Confirm: ConfirmStub
          }
        }
      }
    })
    await flush()
    const items = wrapper.findAll('.group-info-item')
    const role = items.find((i) => i.find('.group-title').text() === '我的角色：')
    expect(role.find('.group-value').text()).toBe('管理员')
    const labels = wrapper.findAll('.dd-item').map((i) => i.text())
    expect(labels).toContain('群成员管理')
    expect(labels).toContain('退出该群')
    expect(labels).toContain('编辑公告')
    expect(labels).not.toContain('解散该群')
    expect(labels).not.toContain('修改群信息')
  })

  it('我的角色：普通成员（U003）→ 仅「退出该群」，无管理按钮', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useUserInfoStore().setInfo({ userId: 'U003', nickName: '阿伟' })
    const request = makeRequest(defaultHandler)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/contact/groupDetail', component: { template: '<div />' } }]
    })
    await router.push({ path: '/contact/groupDetail', query: { contactId: 'G001' } })
    await router.isReady()
    const wrapper = mount(GroupDetail, {
      global: {
        plugins: [pinia, router],
        stubs: Stubs,
        config: {
          globalProperties: {
            Request: request,
            Api: { getGroupInfo: '/group/getGroupInfo', getGroupInfo4Chat: '/group/getGroupInfo4Chat' },
            Message: MessageStub,
            Confirm: ConfirmStub
          }
        }
      }
    })
    await flush()
    const items = wrapper.findAll('.group-info-item')
    const role = items.find((i) => i.find('.group-title').text() === '我的角色：')
    expect(role.find('.group-value').text()).toBe('成员')
    expect(wrapper.findAll('.dd-item').map((i) => i.text())).toEqual(['退出该群'])
    // 底部无二维码/邀请按钮
    const btns = wrapper.findAll('.el-button').map((b) => b.text())
    expect(btns).not.toContain('群二维码')
    expect(btns).not.toContain('群邀请链接')
  })

  it('群主 → 底部按钮含「群二维码」「群邀请链接」', async () => {
    const { wrapper } = await mountDetail(defaultHandler)
    await flush()
    const btns = wrapper.findAll('.el-button').map((b) => b.text())
    expect(btns).toContain('发送群消息')
    expect(btns).toContain('群二维码')
    expect(btns).toContain('群邀请链接')
  })

  it('sendMessage → 跳 /chat 带 groupId', async () => {
    const { wrapper, router } = await mountDetail(defaultHandler)
    await flush()
    const push = vi.spyOn(router, 'push')
    const btn = wrapper.findAll('.el-button').find((b) => b.text() === '发送群消息')
    await btn.trigger('click')
    const arg = push.mock.calls[0][0]
    expect(arg.path).toBe('/chat')
    expect(arg.query.chatId).toBe('G001')
    expect(arg.query.timestamp).toBeTruthy()
  })

  it('群二维码：生成请求 → 弹窗开 + token 展示', async () => {
    const { wrapper, request } = await mountDetail((opts) => {
      if (opts.url === '/group/genQr') return { code: 0, data: 'tok123' }
      return defaultHandler(opts)
    })
    await flush()
    const btn = wrapper.findAll('.el-button').find((b) => b.text() === '群二维码')
    await btn.trigger('click')
    // renderQrCode 走真 qrcode 库（异步 canvas 编码），全量并发下两拍不够 → 带超时轮询
    await vi.waitFor(() => {
      expect(wrapper.find('.qrcode-token').exists()).toBe(true)
    }, { timeout: 3000 })
    expect(request.__calls__.find((c) => c.url === '/group/genQr').params).toEqual({ groupId: 'G001' })
    expect(wrapper.findAll('.el-dialog-stub').some((d) => d.find('.ed-title').text() === '群二维码')).toBe(true)
    expect(wrapper.find('.qrcode-token').text()).toBe('tok123')
  })

  it('群邀请链接：生成请求 → 弹窗开 + token 展示', async () => {
    const { wrapper, request } = await mountDetail((opts) => {
      if (opts.url === '/group/genInvite') return { code: 0, data: 'inv456' }
      return defaultHandler(opts)
    })
    await flush()
    const btn = wrapper.findAll('.el-button').find((b) => b.text() === '群邀请链接')
    await btn.trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/group/genInvite').params).toEqual({ groupId: 'G001' })
    expect(wrapper.find('.invite-token').text()).toBe('inv456')
  })

  it('copyToken → ipc copyText + 提示已复制', async () => {
    const { wrapper } = await mountDetail((opts) => {
      if (opts.url === '/group/genInvite') return { code: 0, data: 'inv456' }
      return defaultHandler(opts)
    })
    await flush()
    const btn = wrapper.findAll('.el-button').find((b) => b.text() === '群邀请链接')
    await btn.trigger('click')
    await flush()
    window.ipcRenderer.__reset__
    await wrapper.find('.invite-panel .el-button').trigger('click')
    const copy = window.ipcRenderer.__calls__.find((c) => c.args[0] === 'copyText')
    expect(copy).toBeTruthy()
    expect(copy.args[1]).toBe('inv456')
    expect(MessageStub.success).toHaveBeenCalledWith('已复制')
  })

  it('群成员管理：开弹窗 + 列表渲染（角色标签/禁言标记）+ 标题带总数', async () => {
    const { wrapper, request } = await mountDetail(defaultHandler)
    await flush()
    // 群主下拉菜单第一项即「修改群信息」，先找「群成员管理」
    const item = wrapper.findAll('.dd-item').find((i) => i.text() === '群成员管理')
    await item.trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/group/memberList').params).toEqual({ groupId: 'G001' })
    const memberDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text().includes('群成员管理'))
    expect(memberDialog).toBeTruthy()
    expect(memberDialog.find('.d-title').text()).toBe('群成员管理(4)')
    const names = memberDialog.findAll('.member-name').map((n) => n.text())
    expect(names).toEqual(['小明', '阿强', '阿伟', '小李'])
    expect(memberDialog.findAll('.muted-tag')).toHaveLength(1)
    // 群主看全员：自己(U001)不可管理，其余 3 人可管理
    expect(memberDialog.findAll('.member-op')).toHaveLength(3)
  })

  it('canMangeMember：群主可管管理员与成员、不可管自己', async () => {
    const { wrapper } = await mountDetail(defaultHandler)
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '群成员管理').trigger('click')
    await flush()
    const memberDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text().includes('群成员管理'))
    const rows = memberDialog.findAll('.member-item')
    // U001 自己 → 无 op；U002 管理员 → 群主可管；U003/U004 成员 → 可管
    expect(rows[0].find('.member-op').exists()).toBe(false)
    expect(rows[1].find('.member-op').exists()).toBe(true)
    expect(rows[2].find('.member-op').exists()).toBe(true)
    // 群主行有「转让群主」按钮
    expect(rows[1].find('.member-op').text()).toContain('转让群主')
  })

  it('canMangeMember 自守卫：群主自身行 role=null（myRole 兜底为 0）→ 自己行不出现管理按钮', async () => {
    // 场景：user_contact.role 为 null → loadMyRole 走「ownerId===me ? 0 : 2」兜底得 my=0，
    // 而成员行自身 role=null 不命中 item.role===0 → 此时「自己不可被管理」守卫是唯一防线。
    const { wrapper } = await mountDetail((opts) => {
      if (opts.url === '/group/getGroupInfo4Chat') {
        return { code: 0, data: { userContactList: [{ userId: 'U001', role: null }] } }
      }
      if (opts.url === '/group/memberList') {
        return {
          code: 0,
          data: { list: [{ userId: 'U001', contactName: '小明', role: null }], total: 1 }
        }
      }
      return defaultHandler(opts)
    })
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '群成员管理').trigger('click')
    await flush()
    const memberDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text().includes('群成员管理'))
    const row = memberDialog.findAll('.member-item')[0]
    expect(row.find('.member-op').exists()).toBe(false)
  })

  it('转让群主：Confirm → transferOwner 参数 → 关成员弹窗 + 刷新角色与群信息', async () => {
    const { wrapper, request } = await mountDetail((opts) => {
      if (opts.url === '/group/transferOwner') return { code: 0, data: 1 }
      return defaultHandler(opts)
    })
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '群成员管理').trigger('click')
    await flush()
    const memberDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text().includes('群成员管理'))
    const transferBtn = memberDialog.findAll('.member-item')[1]
      .findAll('.el-button').find((b) => b.text() === '转让群主')
    await transferBtn.trigger('click')
    expect(ConfirmStub).toHaveBeenCalled()
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    const call = request.__calls__.find((c) => c.url === '/group/transferOwner')
    expect(call.params).toEqual({ groupId: 'G001', newOwnerUserId: 'U002' })
    expect(MessageStub.success).toHaveBeenCalledWith('转让成功')
    expect(wrapper.findAll('.dialog-stub').some((d) => d.find('.d-title').text().includes('群成员管理'))).toBe(false)
  })

  it('设为管理员：Confirm 文案含目标昵称 + setAdmin role=1', async () => {
    const { wrapper, request } = await mountDetail((opts) => {
      if (opts.url === '/group/setAdmin') return { code: 0, data: 1 }
      return defaultHandler(opts)
    })
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '群成员管理').trigger('click')
    await flush()
    const memberDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text().includes('群成员管理'))
    const setBtn = memberDialog.findAll('.member-item')[2]
      .findAll('.el-button').find((b) => b.text() === '设为管理员')
    await setBtn.trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toContain('确定将【阿伟】设为管理员？')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    const call = request.__calls__.find((c) => c.url === '/group/setAdmin')
    expect(call.params).toEqual({ groupId: 'G001', userId: 'U003', role: 1 })
    expect(MessageStub.success).toHaveBeenCalledWith('操作成功')
  })

  it('取消管理员：对 role=1 的目标，targetRole=0', async () => {
    const { wrapper, request } = await mountDetail((opts) => {
      if (opts.url === '/group/setAdmin') return { code: 0, data: 1 }
      return defaultHandler(opts)
    })
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '群成员管理').trigger('click')
    await flush()
    const memberDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text().includes('群成员管理'))
    const btn = memberDialog.findAll('.member-item')[1]
      .findAll('.el-button').find((b) => b.text() === '取消管理员')
    await btn.trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toContain('确定取消【阿强】的管理员身份？')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(request.__calls__.find((c) => c.url === '/group/setAdmin').params.role).toBe(0)
  })

  it('禁言：开禁言弹窗（默认 30 分钟）→ 确定 → muteMember 参数', async () => {
    const { wrapper, request } = await mountDetail((opts) => {
      if (opts.url === '/group/muteMember') return { code: 0, data: 1 }
      return defaultHandler(opts)
    })
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '群成员管理').trigger('click')
    await flush()
    const memberDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text().includes('群成员管理'))
    const muteBtn = memberDialog.findAll('.member-item')[2]
      .findAll('.el-button').find((b) => b.text() === '禁言')
    await muteBtn.trigger('click')
    await flush()
    const muteDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text() === '禁言')
    expect(muteDialog).toBeTruthy()
    expect(muteDialog.find('.num-input').element.value).toBe('30')
    await muteDialog.find('.d-btn').trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/group/muteMember').params).toEqual({
      groupId: 'G001', userId: 'U003', minutes: 30
    })
    expect(MessageStub.success).toHaveBeenCalledWith('操作成功')
  })

  it('已禁言成员 → 按钮文案「解除禁言」且默认分钟数 0', async () => {
    const { wrapper } = await mountDetail(defaultHandler)
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '群成员管理').trigger('click')
    await flush()
    const memberDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text().includes('群成员管理'))
    const btn = memberDialog.findAll('.member-item')[3]
      .findAll('.el-button').find((b) => b.text() === '解除禁言')
    await btn.trigger('click')
    await flush()
    const muteDialog = wrapper.findAll('.dialog-stub').find((d) => d.find('.d-title').text() === '解除禁言')
    expect(muteDialog.find('.num-input').element.value).toBe('0')
  })

  it('编辑公告：toggle 出 textarea 预填现公告 → 保存调 editNotice 并关编辑态', async () => {
    const { wrapper, request } = await mountDetail((opts) => {
      if (opts.url === '/group/editNotice') return { code: 0, data: 1 }
      return defaultHandler(opts)
    })
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '编辑公告').trigger('click')
    await flush()
    const textarea = wrapper.find('.el-input-native')
    expect(textarea.exists()).toBe(true)
    expect(textarea.element.value).toBe('周末迭代')
    await textarea.setValue('新公告内容')
    const saveBtn = wrapper.findAll('.el-button').find((b) => b.text() === '保存')
    await saveBtn.trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/group/editNotice').params).toEqual({
      groupId: 'G001', notice: '新公告内容'
    })
    expect(MessageStub.success).toHaveBeenCalledWith('公告已更新')
    // 保存后退出编辑态 → 公告以文本展示
    await flush()
    const items = wrapper.findAll('.group-info-item')
    const notice = items.find((i) => i.find('.group-title').text() === '公告：')
    expect(notice.find('.el-input-native').exists()).toBe(false)
  })

  it('解散群：Confirm 文案 → dissolutionGroup 参数 → 置 DISSOLUTION_GROUP', async () => {
    const { wrapper, request, pinia } = await mountDetail((opts) => {
      if (opts.url === '/group/dissolution') return { code: 0, data: 1 }
      return defaultHandler(opts)
    })
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '解散该群').trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toBe('确定要删除群组?删除后将无法恢复!')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(request.__calls__.find((c) => c.url === '/group/dissolution').params).toEqual({ groupId: 'G001' })
    expect(MessageStub.success).toHaveBeenCalledWith('解散成功')
    expect(useContactStateStore().contactReload).toBe('DISSOLUTION_GROUP')
    expect(pinia).toBeTruthy()
  })

  it('退出群（成员视角）：Confirm → leaveGroup 参数 → 置 LEAVE_GROUP', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useUserInfoStore().setInfo({ userId: 'U003', nickName: '阿伟' })
    const request = makeRequest((opts) => {
      if (opts.url === '/group/leave') return { code: 0, data: 1 }
      return defaultHandler(opts)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/contact/groupDetail', component: { template: '<div />' } }]
    })
    await router.push({ path: '/contact/groupDetail', query: { contactId: 'G001' } })
    await router.isReady()
    const wrapper = mount(GroupDetail, {
      global: {
        plugins: [pinia, router],
        stubs: Stubs,
        config: {
          globalProperties: {
            Request: request,
            Api: { getGroupInfo: '/group/getGroupInfo', getGroupInfo4Chat: '/group/getGroupInfo4Chat', leaveGroup: '/group/leave' },
            Message: MessageStub,
            Confirm: ConfirmStub
          }
        }
      }
    })
    await flush()
    await wrapper.findAll('.dd-item')[0].trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toBe('确定要退出群组?')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(request.__calls__.find((c) => c.url === '/group/leave').params).toEqual({ groupId: 'G001' })
    expect(MessageStub.success).toHaveBeenCalledWith('退出成功')
    expect(useContactStateStore().contactReload).toBe('LEAVE_GROUP')
  })

  it('解散失败（返回空）→ 不置刷新标记、不提示成功', async () => {
    const { wrapper, pinia } = await mountDetail((opts) => {
      if (opts.url === '/group/dissolution') return undefined
      return defaultHandler(opts)
    })
    await flush()
    await wrapper.findAll('.dd-item').find((i) => i.text() === '解散该群').trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(useContactStateStore().contactReload).toBeNull()
    expect(pinia).toBeTruthy()
  })

  it('route 变化（contactId 改变）→ 重新拉取群信息', async () => {
    const { wrapper, request, router } = await mountDetail(defaultHandler)
    await flush()
    const before = request.__calls__.filter((c) => c.url === '/group/getGroupInfo').length
    await router.push({ path: '/contact/groupDetail', query: { contactId: 'G002' } })
    await flush()
    const after = request.__calls__.filter((c) => c.url === '/group/getGroupInfo')
    expect(after.length).toBe(before + 1)
    expect(after[after.length - 1].params).toEqual({ groupId: 'G002' })
    expect(wrapper.find('.group-info-item').exists()).toBe(true)
  })

  it('群信息接口失败（返回空）→ 不渲染群ID，页面不抛错', async () => {
    const { wrapper } = await mountDetail((opts) => {
      if (opts.url === '/group/getGroupInfo') return undefined
      return defaultHandler(opts)
    })
    await flush()
    const items = wrapper.findAll('.group-info-item')
    const idItem = items.find((i) => i.find('.group-title').text() === '群ID：')
    expect(idItem.find('.group-value').text()).toBe('')
  })
})
