import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import UserDetail from '@/views/contact/UserDetail.vue'
import { useContactStateStore } from '@/stores/ContactStateStore'
import { useUserInfoStore } from '@/stores/UserInfoStore'

/**
 * 用户详情页的**真实挂载**测试（DOM 级，真实 router + 真实 pinia store）。
 *
 * 被测点：
 *   1. 进页（route.query.contactId）→ 拉详情：签名/备注/分组回显
 *   2. 「我的状态」回显（loadMyStatus）—— 修复前该函数引用未定义的
 *      `userInfoStore` 必抛 ReferenceError，回显恒空（本 spec 首版即抓出）
 *   3. 保存备注/分组 → 参数 + success + ContactStateStore 通知刷新
 *   4. 删除/拉黑 → Confirm okfun 参数 + store 清理 + 黑名单去路提示
 *   5. 拍一拍参数；发消息跳转
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

const ME = 'U001'
const FRIEND = 'U002'

const Detail = {
  userId: FRIEND,
  nickName: '老王',
  personalSignature: '签名在此',
  remark: '王哥',
  groupName: '同事'
}

const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'size', 'placeholder', 'maxlength', 'showWordLimit', 'showPassword', 'clearable', 'type'],
  emits: ['update:modelValue', 'change'],
  template:
    '<input class="el-input-native" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" @change="$emit(\'change\', $event.target.value)" />'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\', $event)"><slot /></button>'
}
const ElDropdownStub = {
  name: 'ElDropdown',
  props: ['placement', 'trigger'],
  template: '<div class="el-dropdown"><slot /><div class="dropdown-panel"><slot name="dropdown" /></div></div>'
}
const ElDropdownMenuStub = { name: 'ElDropdownMenu', template: '<div class="dropdown-menu"><slot /></div>' }
const ElDropdownItemStub = {
  name: 'ElDropdownItem',
  emits: ['click'],
  template: '<div class="dropdown-item" @click="$emit(\'click\', $event)"><slot /></div>'
}
const ContentPanelStub = {
  name: 'ContentPanel',
  template: '<div class="content-panel"><slot /></div>'
}
const UserBaseInfoStub = {
  name: 'UserBaseInfo',
  props: ['userInfo', 'showArea'],
  template: '<div class="user-base-info-stub" :data-uid="userInfo.userId" />'
}

async function mountDetail (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useUserInfoStore().setInfo({ userId: ME, nickName: '我' })
  const request = makeRequest(handler)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/contact/detail', component: { template: '<div />' } }]
  })
  await router.push({ path: '/contact/detail', query: { contactId: FRIEND } })
  await router.isReady()

  const wrapper = mount(UserDetail, {
    global: {
      plugins: [pinia, router],
      stubs: {
        ContentPanel: ContentPanelStub,
        UserBaseInfo: UserBaseInfoStub,
        'el-input': ElInputStub,
        'el-button': ElButtonStub,
        'el-dropdown': ElDropdownStub,
        'el-dropdown-menu': ElDropdownMenuStub,
        'el-dropdown-item': ElDropdownItemStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            getContactUserInfo: '/contact/getUserInfo',
            getStatus: '/status/get',
            setStatus: '/status/set',
            clearStatus: '/status/clear',
            setContactRemark: '/contact/setRemark',
            setContactGroup: '/contact/setGroup',
            addContact2BlackList: '/contact/addBlack',
            delContact: '/contact/del',
            nudge: '/contact/nudge'
          },
          Message: MessageStub,
          Confirm: ConfirmStub
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

/** 按 url 分发的默认 handler：详情 + 双状态 */
function defaultHandler () {
  return (opts) => {
    if (opts.url === '/contact/getUserInfo') return { code: 0, data: Detail }
    if (opts.url === '/status/get') {
      if (opts.params.userId === ME) return { code: 0, data: { content: '搬砖中' } }
      return { code: 0, data: { content: '出差了', imageUrl: 'http://x/s.png' } }
    }
    return { code: 0, data: null }
  }
}

beforeEach(() => {
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
  ConfirmStub.mockClear()
})

describe('UserDetail.vue 真实挂载（DOM 级）', () => {
  it('进页拉详情：签名/备注/分组回显 + 好友状态渲染', async () => {
    const { wrapper, request } = await mountDetail(defaultHandler())
    await flush()

    const detailCall = request.__calls__.find((c) => c.url === '/contact/getUserInfo')
    expect(detailCall.params).toEqual({ contactId: FRIEND })
    expect(wrapper.text()).toContain('签名在此')

    const inputs = wrapper.findAll('.el-input-native')
    // inputs: [我的状态, 备注, 分组]
    const byPh = (p) => inputs.find((i) => i.attributes('placeholder') === p)
    expect(byPh('设置备注名').element.value).toBe('王哥')
    expect(byPh('设置分组，如：同事').element.value).toBe('同事')

    // 好友状态（非本人）渲染内容 + 图
    expect(wrapper.text()).toContain('出差了')
    expect(wrapper.find('.status-image').attributes('src')).toBe('http://x/s.png')
  })

  it('「我的状态」回显（loadMyStatus 用本人 userId 拉取并填入）', async () => {
    const { wrapper, request } = await mountDetail(defaultHandler())
    await flush()

    const myStatusCall = request.__calls__.find(
      (c) => c.url === '/status/get' && c.params.userId === ME
    )
    expect(myStatusCall).toBeTruthy()
    const input = wrapper
      .findAll('.el-input-native')
      .find((i) => i.attributes('placeholder') === '设置状态，24小时后过期')
    expect(input.element.value).toBe('搬砖中')
  })

  it('保存备注：参数 + success + ContactStateStore 通知刷新', async () => {
    const { wrapper, request, pinia } = await mountDetail(defaultHandler())
    await flush()
    const store = useContactStateStore()
    const remarkInput = wrapper
      .findAll('.el-input-native')
      .find((i) => i.attributes('placeholder') === '设置备注名')
    await remarkInput.setValue('新备注')
    await remarkInput.trigger('change')
    await flush()

    const call = request.__calls__.find((c) => c.url === '/contact/setRemark')
    expect(call.params).toEqual({ contactId: FRIEND, remark: '新备注' })
    expect(MessageStub.success).toHaveBeenCalledWith('备注已保存')
    await flush()
    expect(store.contactReload).toBe('USER')
    expect(pinia).toBeTruthy()
  })

  it('保存分组：参数 + success', async () => {
    const { wrapper, request } = await mountDetail(defaultHandler())
    await flush()
    const groupInput = wrapper
      .findAll('.el-input-native')
      .find((i) => i.attributes('placeholder') === '设置分组，如：同事')
    await groupInput.setValue('朋友')
    await groupInput.trigger('change')
    await flush()

    const call = request.__calls__.find((c) => c.url === '/contact/setGroup')
    expect(call.params).toEqual({ contactId: FRIEND, groupName: '朋友' })
    expect(MessageStub.success).toHaveBeenCalledWith('分组已保存')
  })

  it('删除联系人：Confirm okfun → delContact 参数 + store 清理', async () => {
    const { wrapper, request, pinia } = await mountDetail(defaultHandler())
    await flush()
    const store = useContactStateStore()
    store.delContact('INIT')

    await wrapper.findAll('.dropdown-item')[2].trigger('click')
    expect(ConfirmStub).toHaveBeenCalledWith(
      expect.objectContaining({ message: '确定要删除联系人？' })
    )
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()

    const call = request.__calls__.find((c) => c.url === '/contact/del')
    expect(call.params).toEqual({ contactId: FRIEND })
    expect(store.delContactId).toBe(FRIEND)
    expect(store.contactReload).toBe('REMOVE_USER')
    expect(pinia).toBeTruthy()
  })

  it('加入黑名单：okfun → 参数 + 去路提示文案', async () => {
    const { wrapper, request } = await mountDetail(defaultHandler())
    await flush()

    await wrapper.findAll('.dropdown-item')[1].trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toContain('黑名单')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()

    const call = request.__calls__.find((c) => c.url === '/contact/addBlack')
    expect(call.params).toEqual({ contactId: FRIEND })
    expect(MessageStub.success).toHaveBeenCalledWith(
      '已加入黑名单，可在「设置 → 黑名单」中解除'
    )
  })

  it('拍一拍：nudge 参数 + success', async () => {
    const { wrapper, request } = await mountDetail(defaultHandler())
    await flush()
    await wrapper.findAll('.dropdown-item')[0].trigger('click')
    await flush()
    const call = request.__calls__.find((c) => c.url === '/contact/nudge')
    expect(call.params).toEqual({ contactId: FRIEND })
    expect(MessageStub.success).toHaveBeenCalledWith('已发送拍一拍')
  })

  it('发消息 → 跳转 /chat 带 chatId', async () => {
    const { wrapper, router } = await mountDetail(defaultHandler())
    await flush()
    const push = vi.spyOn(router, 'push')
    await wrapper.find('.send-message').trigger('click')
    expect(push).toHaveBeenCalledTimes(1)
    expect(push.mock.calls[0][0].path).toBe('/chat')
    expect(push.mock.calls[0][0].query.chatId).toBe(FRIEND)
  })

  it('无签名 → 兜底 "-"', async () => {
    const { wrapper } = await mountDetail((opts) => {
      if (opts.url === '/contact/getUserInfo') {
        return { code: 0, data: { userId: FRIEND, nickName: '老王' } }
      }
      return { code: 0, data: null }
    })
    await flush()
    const contents = wrapper.findAll('.part-content').map((n) => n.text())
    expect(contents[0]).toBe('-')
  })
})
