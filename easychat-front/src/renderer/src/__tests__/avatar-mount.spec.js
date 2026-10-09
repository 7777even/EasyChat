import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import Avatar from '@/components/Avatar.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'

/**
 * 头像组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. Urobot / 群头像（contactType==1）→ 直接 AvatarBase，无 popover
 *   2. 普通用户 → popover 结构（reference + default）
 *   3. getContactInfo 自己 → 直接用 store 信息，不 Request
 *   4. getContactInfo 他人 → Request getContactInfo
 *   5. 加为好友 → hide popover + SearchAdd.show({contactId, contactType:'USER'})
 *   6. 发送消息 → hide popover + emit closeDrawer + router.push
 */

const MessageStub = { error: vi.fn(), warning: vi.fn(), success: vi.fn() }

function makeRequestStub(handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const stubs = {
  AvatarBase: {
    name: 'AvatarBase',
    template: '<div class="avatar-base" :data-userid="userId"></div>',
    props: ['userId', 'width', 'borderRadius', 'showDetail']
  },
  UserBaseInfo: {
    name: 'UserBaseInfo',
    template: '<div class="user-base-info"></div>',
    props: ['userInfo']
  },
  SearchAdd: {
    name: 'SearchAdd',
    template: '<div class="search-add"></div>',
    data() { return { showArgs: null, hideCalled: false } },
    methods: {
      show(opts) { this.showArgs = opts }
    }
  },
  'el-popover': {
    name: 'el-popover',
    template: '<div class="el-popover"><slot name="reference" /><slot /></div>',
    props: ['width', 'placement', 'showArrow', 'trigger', 'transition', 'hideAfter'],
    emits: ['show', 'hide'],
    data() { return { hidden: false } },
    methods: {
      hide() { this.hidden = true; this.$emit('hide') }
    }
  },
  'el-button': {
    name: 'el-button',
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`,
    props: ['type']
  }
}

let pinia
let router
let request

function mountAvatar(props = {}, handler) {
  request = makeRequestStub(handler)
  const wrapper = mount(Avatar, {
    props: { userId: 'U_other', width: 40, borderRadius: 0, contactType: 0, ...props },
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { getContactInfo: '/user/getContactInfo' },
          Message: MessageStub
        }
      },
      plugins: [pinia, router]
    }
  })
  return wrapper
}

describe('Avatar.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({ history: createMemoryHistory(), routes: [] })
    vi.spyOn(router, 'push').mockResolvedValue()
    useUserInfoStore().setInfo({ userId: 'U_mine' })
  })

  it('Urobot → 直接 AvatarBase，无 popover', () => {
    const wrapper = mountAvatar({ userId: 'Urobot' })
    expect(wrapper.find('.avatar-base').exists()).toBe(true)
    expect(wrapper.find('.el-popover').exists()).toBe(false)
  })

  it('群头像（contactType==1）→ 直接 AvatarBase，无 popover', () => {
    const wrapper = mountAvatar({ contactType: 1 })
    expect(wrapper.find('.avatar-base').exists()).toBe(true)
    expect(wrapper.find('.el-popover').exists()).toBe(false)
  })

  it('普通用户 → popover 结构', () => {
    const wrapper = mountAvatar()
    expect(wrapper.find('.el-popover').exists()).toBe(true)
    expect(wrapper.findAll('.avatar-base').length).toBeGreaterThan(0)
  })

  it('getContactInfo 自己 → 不 Request，直接用 store 信息', async () => {
    const wrapper = mountAvatar({ userId: 'U_mine' })
    await wrapper.findComponent({ name: 'el-popover' }).vm.$emit('show')
    await new Promise((r) => setTimeout(r, 0))
    const loads = request.__calls__.filter((c) => c.url === '/user/getContactInfo')
    expect(loads.length).toBe(0)
  })

  it('getContactInfo 他人 → Request getContactInfo', async () => {
    const wrapper = mountAvatar()
    await wrapper.findComponent({ name: 'el-popover' }).vm.$emit('show')
    await new Promise((r) => setTimeout(r, 0))
    const loads = request.__calls__.filter((c) => c.url === '/user/getContactInfo')
    expect(loads.length).toBe(1)
    expect(loads[0].params.contactId).toBe('U_other')
  })

  it('加为好友 → hide popover + SearchAdd.show', async () => {
    const wrapper = mountAvatar()
    const btn = wrapper.findAll('.el-button').find((b) => b.text() === '加为好友')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    const sa = wrapper.findComponent({ name: 'SearchAdd' })
    expect(sa.vm.showArgs).toBeTruthy()
    expect(sa.vm.showArgs.contactId).toBe('U_other')
    expect(sa.vm.showArgs.contactType).toBe('USER')
    expect(wrapper.findComponent({ name: 'el-popover' }).vm.hidden).toBe(true)
  })

  it('发送消息 → hide popover + emit closeDrawer + router.push', async () => {
    const wrapper = mountAvatar()
    // 先触发 show 拉取联系人信息（返回 contactStatus=1 好友）
    request = makeRequestStub(() => ({ code: 0, data: { contactStatus: 1 } }))
    wrapper.vm.$.appContext.config.globalProperties.Request = request
    await wrapper.findComponent({ name: 'el-popover' }).vm.$emit('show')
    await new Promise((r) => setTimeout(r, 0))
    const btn = wrapper.findAll('.el-button').find((b) => b.text() === '发送消息')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    expect(wrapper.emitted('closeDrawer')).toBeTruthy()
    expect(wrapper.findComponent({ name: 'el-popover' }).vm.hidden).toBe(true)
    expect(router.push).toHaveBeenCalled()
  })
})
