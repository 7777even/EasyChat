import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatGroupDetail from '@/views/chat/ChatGroupDetail.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { ipcCalls } from './setup'

/**
 * 群详情抽屉的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. show(groupId)：拉群信息 → 开抽屉、渲染成员/群主标签/群号/名称/公告（空公告兜底 '-'）
 *   2. 拉取失败 → errorCallback → Confirm(showCancelBtn:false)，抽屉**不打开**
 *   3. 群主 vs 成员的权限分支：添加/移除入口 + 「解散群聊」vs「退出群聊」
 *   4. 退出群聊 okfun：leaveGroup → ipc delChatSession + emit + 关抽屉
 *   5. 解散群 okfun：dissolutionGroup → 关抽屉（不发 delChatSession）
 *   6. addUser：已在群成员被 disabled；removeUser：排除第一位（群主）
 */

const MessageStub = { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
const ConfirmStub = vi.fn()

// UserSelect 子组件：组件通过 ref 调 show()，桩上必须有同名方法
const userSelectShow = vi.fn()
const UserSelectStub = {
  name: 'UserSelect',
  template: '<div class="user-select-stub" />',
  methods: {
    show: (payload) => userSelectShow(payload)
  }
}

const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
const AvatarBaseStub = {
  name: 'AvatarBase',
  props: ['userId', 'width', 'borderRadius', 'showDetail'],
  template: '<div class="avatar-base-stub" :data-userid="userId" />'
}

const ElDrawerStub = {
  template: '<div v-if="modelValue" class="el-drawer"><slot /></div>',
  props: ['modelValue', 'size']
}

const API = {
  getGroupInfo4Chat: '/group/getGroupInfo4Chat',
  leaveGroup: '/group/leaveGroup',
  dissolutionGroup: '/group/dissolutionGroup',
  loadContact: '/contact/loadContact'
}

/** 可控 Request 桩：handler(opts) → 返回值；支持主动触发 errorCallback */
function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountDetail ({ userId = 'U_9', request } = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useUserInfoStore().setInfo({ userId, nickName: '我' })
  const wrapper = mount(ChatGroupDetail, {
    global: {
      stubs: {
        UserSelect: UserSelectStub,
        Avatar: AvatarStub,
        AvatarBase: AvatarBaseStub,
        'el-drawer': ElDrawerStub
      },
      plugins: [pinia],
      config: {
        globalProperties: {
          Request: request,
          Api: API,
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  return { wrapper, request }
}

const GROUP = {
  groupId: 'G1',
  groupName: '开发群',
  groupNotice: '好好写码',
  groupOwnerId: 'OWNER'
}
const MEMBERS = [
  { userId: 'OWNER', contactName: '群主甲' },
  { userId: 'U_2', contactName: '成员乙' }
]

async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

describe('ChatGroupDetail.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    MessageStub.success.mockClear()
    MessageStub.error.mockClear()
    ConfirmStub.mockClear()
    userSelectShow.mockClear()
    ipcCalls.length = 0
  })

  it('show() 成功：开抽屉并渲染成员/群主标签/群号/名称/公告', async () => {
    const request = makeRequest(() => ({
      code: 0,
      data: { userContactList: MEMBERS, groupInfo: GROUP }
    }))
    const { wrapper } = mountDetail({ userId: 'U_9', request })
    expect(wrapper.find('.el-drawer').exists()).toBe(false)

    await wrapper.vm.show('G1')
    await wrapper.vm.$nextTick()

    expect(request.__calls__[0].url).toBe(API.getGroupInfo4Chat)
    expect(request.__calls__[0].params).toEqual({ groupId: 'G1' })
    expect(wrapper.find('.el-drawer').exists()).toBe(true)
    const items = wrapper.findAll('.member-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.owner-tag').text()).toBe('群主')
    expect(items[1].find('.owner-tag').exists()).toBe(false)
    expect(wrapper.text()).toContain('G1')
    expect(wrapper.text()).toContain('开发群')
    expect(wrapper.text()).toContain('好好写码')
  })

  it('群公告为空 → 公示区兜底 "-"', async () => {
    const request = makeRequest(() => ({
      code: 0,
      data: { userContactList: MEMBERS, groupInfo: { ...GROUP, groupNotice: '' } }
    }))
    const { wrapper } = mountDetail({ request })
    await wrapper.vm.show('G1')
    await wrapper.vm.$nextTick()
    // [0] 是 AvatarBase 外层容器，[1] 群号、[2] 群聊名称、[3] 群公告
    expect(wrapper.findAll('.part-content')[3].text()).toBe('-')
  })

  it('拉取失败 → errorCallback 触发 Confirm(不可取消)，抽屉不打开', async () => {
    const request = makeRequest((opts) => {
      opts.errorCallback({ message: '群不存在' })
      return undefined
    })
    const { wrapper } = mountDetail({ request })
    await wrapper.vm.show('G404')
    await wrapper.vm.$nextTick()
    expect(ConfirmStub).toHaveBeenCalledWith({
      message: '群不存在',
      showCancelBtn: false
    })
    expect(wrapper.find('.el-drawer').exists()).toBe(false)
  })

  it('非群主：无添加/移除入口，显示「退出群聊」而非「解散群聊」', async () => {
    const request = makeRequest(() => ({
      code: 0,
      data: { userContactList: MEMBERS, groupInfo: GROUP }
    }))
    const { wrapper } = mountDetail({ userId: 'U_9', request })
    await wrapper.vm.show('G1')
    await wrapper.vm.$nextTick()
    const opNames = wrapper.findAll('.nick-name').map((n) => n.text())
    expect(opNames).not.toContain('添加')
    expect(opNames).not.toContain('移除')
    expect(wrapper.find('.leave-btn').text()).toBe('退出群聊')
    expect(wrapper.text()).not.toContain('解散群聊')
  })

  it('群主：有添加/移除入口，显示「解散群聊」', async () => {
    const request = makeRequest(() => ({
      code: 0,
      data: { userContactList: MEMBERS, groupInfo: GROUP }
    }))
    const { wrapper } = mountDetail({ userId: 'OWNER', request })
    await wrapper.vm.show('G1')
    await wrapper.vm.$nextTick()
    const opNames = wrapper.findAll('.nick-name').map((n) => n.text())
    expect(opNames).toContain('添加')
    expect(opNames).toContain('移除')
    expect(wrapper.find('.leave-btn').text()).toBe('解散群聊')
    expect(wrapper.text()).not.toContain('退出群聊')
  })

  it('退出群聊：Confirm(okfun) → leaveGroup + ipc delChatSession + emit + 关抽屉', async () => {
    const request = makeRequest(() => ({
      code: 0,
      data: { userContactList: MEMBERS, groupInfo: GROUP }
    }))
    const { wrapper } = mountDetail({ userId: 'U_9', request })
    await wrapper.vm.show('G1')
    await wrapper.vm.$nextTick()

    await wrapper.find('.leave-btn').trigger('click')
    expect(ConfirmStub).toHaveBeenCalledTimes(1)
    expect(ConfirmStub.mock.calls[0][0].message).toContain('退出群聊【开发群】')

    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()

    const leave = request.__calls__.find((c) => c.url === API.leaveGroup)
    expect(leave).toBeTruthy()
    expect(leave.params).toEqual({ groupId: 'G1' })
    const del = ipcCalls.find((c) => c.args[0] === 'delChatSession')
    expect(del).toBeTruthy()
    expect(del.args[1]).toBe('G1')
    expect(MessageStub.success).toHaveBeenCalledWith('退出成功')
    expect(wrapper.emitted('delChatSessionCallback')).toEqual([['G1']])
    expect(wrapper.find('.el-drawer').exists()).toBe(false)
  })

  it('解散群：okfun → dissolutionGroup，不发 delChatSession，不 emit', async () => {
    const request = makeRequest(() => ({
      code: 0,
      data: { userContactList: MEMBERS, groupInfo: GROUP }
    }))
    const { wrapper } = mountDetail({ userId: 'OWNER', request })
    await wrapper.vm.show('G1')
    await wrapper.vm.$nextTick()

    await wrapper.find('.leave-btn').trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toContain('解散群聊【开发群】')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()

    const dis = request.__calls__.find((c) => c.url === API.dissolutionGroup)
    expect(dis).toBeTruthy()
    expect(dis.params).toEqual({ groupId: 'G1' })
    expect(ipcCalls.find((c) => c.args[0] === 'delChatSession')).toBeUndefined()
    expect(MessageStub.success).toHaveBeenCalledWith('解散成功')
    expect(wrapper.emitted('delChatSessionCallback')).toBeUndefined()
    expect(wrapper.find('.el-drawer').exists()).toBe(false)
  })

  it('addUser：已在群成员被标记 disabled，show(opType=1)', async () => {
    const request = makeRequest((opts) => {
      if (opts.url === API.getGroupInfo4Chat) {
        return { code: 0, data: { userContactList: MEMBERS, groupInfo: GROUP } }
      }
      if (opts.url === API.loadContact) {
        return {
          code: 0,
          data: [
            { contactId: 'OWNER' },
            { contactId: 'U_2' },
            { contactId: 'U_3' }
          ]
        }
      }
      return { code: 0, data: null }
    })
    const { wrapper } = mountDetail({ userId: 'OWNER', request })
    await wrapper.vm.show('G1')
    await wrapper.vm.$nextTick()

    const ops = wrapper.findAll('.icon-op')
    await ops[0].trigger('click')
    await flush()

    expect(userSelectShow).toHaveBeenCalledTimes(1)
    const payload = userSelectShow.mock.calls[0][0]
    expect(payload.groupId).toBe('G1')
    expect(payload.opType).toBe(1)
    expect(payload.contactList.map((c) => [c.contactId, c.disabled])).toEqual([
      ['OWNER', true],
      ['U_2', true],
      ['U_3', undefined]
    ])
  })

  it('removeUser：排除第一位成员（群主），show(opType=0) 且 contactId=userId', async () => {
    const request = makeRequest(() => ({
      code: 0,
      data: { userContactList: MEMBERS, groupInfo: GROUP }
    }))
    const { wrapper } = mountDetail({ userId: 'OWNER', request })
    await wrapper.vm.show('G1')
    await wrapper.vm.$nextTick()

    const ops = wrapper.findAll('.icon-op')
    await ops[1].trigger('click')

    expect(userSelectShow).toHaveBeenCalledTimes(1)
    const payload = userSelectShow.mock.calls[0][0]
    expect(payload.opType).toBe(0)
    expect(payload.groupId).toBe('G1')
    // 群主（第一位）被排除，只剩成员乙，且 contactId 由 userId 映射
    expect(payload.contactList).toEqual([{ userId: 'U_2', contactName: '成员乙', contactId: 'U_2' }])
  })
})
