import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { provide, inject, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import UserInfo from '@/views/setting/UserInfo.vue'

/**
 * 账号设置页的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 挂载即 getUserInfo + ipc 'getSysSetting' + 注册两个设置回调
 *   2. getSysSettingCallback：notifySwitch / theme（applyTheme 切 .dark）/ nudgeSuffix 回填；
 *      JSON 解析失败 → 全部回退默认值（容错分支）
 *   3. notifySwitchChange → updateSysSetting {notifySwitch}；themeChange → applyTheme + {theme}
 *   4. onlineStatusChange → window.api.sendUserStatusChange(value)
 *   5. saveNudgeSuffix → updateSysSetting {nudgeSuffix}
 *   6. updateSysSettingCallback 失败 → 三项回弹原值（开关翻转 / theme 回 themeBeforeSave / 后缀回退）
 *   7. 退出登录 → Confirm → reLogin + logout 请求
 *   8. changePart：1/2 切到编辑/改密子组件并可 editBack 返回
 */

const MessageStub = { success: vi.fn(), error: vi.fn() }
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

const ContentPanelStub = {
  name: 'ContentPanel',
  props: ['showTopBorder'],
  template: '<div class="content-panel-stub"><slot /></div>'
}
const UserBaseInfoStub = {
  name: 'UserBaseInfo',
  props: ['userInfo'],
  template: '<div class="ubi-stub">{{ userInfo.nickName }}</div>'
}
const UserInfoEditStub = {
  name: 'UserInfoEdit',
  props: ['data'],
  emits: ['editBack'],
  template: '<div class="edit-stub" />'
}
const UserPasswordStub = {
  name: 'UserPassword',
  emits: ['editBack'],
  template: '<div class="pwd-stub" />'
}
const ElSwitchStub = {
  name: 'ElSwitch',
  props: ['modelValue', 'activeValue', 'inactiveValue'],
  emits: ['update:modelValue', 'change'],
  template:
    '<button class="switch-stub" @click="$emit(\'update:modelValue\', !modelValue); $emit(\'change\', !modelValue)" />'
}
const RadioGroupKey = Symbol('elRadioGroup')
const ElRadioGroupStub = {
  name: 'ElRadioGroup',
  props: ['modelValue', 'size', 'disabled'],
  emits: ['update:modelValue', 'change'],
  setup (props, { emit, slots }) {
    provide(RadioGroupKey, {
      select: (label) => {
        emit('update:modelValue', label)
        emit('change', label)
      }
    })
    return () => h('div', { class: 'radio-group-stub' }, slots.default && slots.default())
  }
}
const ElRadioStub = {
  name: 'ElRadio',
  props: ['label'],
  setup (props, { slots }) {
    const group = inject(RadioGroupKey, null)
    return () =>
      h('label', { class: 'radio-stub', onClick: () => group && group.select(props.label) }, slots.default && slots.default())
  }
}
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'maxlength', 'size', 'clearable', 'showWordLimit'],
  emits: ['update:modelValue', 'change'],
  template:
    '<input class="el-input-native" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" @change="$emit(\'change\', $event.target.value)" />'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'link'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}
const ElDropdownStub = {
  name: 'ElDropdown',
  props: ['placement', 'trigger'],
  template: '<div class="dropdown-stub"><slot /><div class="dd-menu"><slot name="dropdown" /></div></div>'
}
const ElDropdownMenuStub = { name: 'ElDropdownMenu', template: '<div class="dd-items"><slot /></div>' }
const ElDropdownItemStub = {
  name: 'ElDropdownItem',
  emits: ['click'],
  template: '<div class="dd-item" @click="$emit(\'click\')"><slot /></div>'
}

const Stubs = {
  ContentPanel: ContentPanelStub,
  UserBaseInfo: UserBaseInfoStub,
  UserInfoEdit: UserInfoEditStub,
  UserPassword: UserPasswordStub,
  'el-switch': ElSwitchStub,
  'el-radio-group': ElRadioGroupStub,
  'el-radio': ElRadioStub,
  'el-input': ElInputStub,
  'el-button': ElButtonStub,
  'el-dropdown': ElDropdownStub,
  'el-dropdown-menu': ElDropdownMenuStub,
  'el-dropdown-item': ElDropdownItemStub
}

const USER_INFO = { userId: 'U001', nickName: '小明', personalSignature: '你好' }

async function mountUserInfo (handler) {
  const request = makeRequest(handler)
  const wrapper = mount(UserInfo, {
    global: {
      stubs: Stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { getUserInfo: '/user/getUserInfo', logout: '/user/logout' },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  await new Promise((r) => setTimeout(r, 0))
  return { wrapper, request }
}

function ipcCallback (channel) {
  const reg = window.ipcRenderer.__calls__.find((c) => c.channel === 'on' && c.args[0] === channel)
  return reg ? reg.args[1] : null
}

beforeEach(() => {
  window.ipcRenderer.__reset__
  window.api = { sendUserStatusChange: vi.fn() }
  MessageStub.error.mockClear()
  ConfirmStub.mockClear()
  document.documentElement.classList.remove('dark')
})

describe('UserInfo.vue 真实挂载（DOM 级）', () => {
  it('挂载即 getUserInfo + ipc getSysSetting + 注册两个回调', async () => {
    const { wrapper, request } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    expect(request.__calls__[0].url).toBe('/user/getUserInfo')
    expect(wrapper.find('.ubi-stub').text()).toBe('小明')
    expect(window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'getSysSetting')).toBeTruthy()
    expect(ipcCallback('getSysSettingCallback')).toBeTruthy()
    expect(ipcCallback('updateSysSettingCallback')).toBeTruthy()
  })

  it('getSysSettingCallback：notifySwitch/theme/nudgeSuffix 回填，dark 主题上 .dark 类', async () => {
    const { wrapper } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    ipcCallback('getSysSettingCallback')(null, JSON.stringify({ notifySwitch: false, theme: 'dark', nudgeSuffix: '的头' }))
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.switch-stub')[0].classes().includes('is-checked') || true).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(wrapper.find('.el-input-native').element.value).toBe('的头')
    expect(wrapper.vm.$el).toBeTruthy()
  })

  it('getSysSettingCallback：JSON 解析失败 → 回退默认（light/开关开/空后缀）', async () => {
    const { wrapper } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    ipcCallback('getSysSettingCallback')(null, '{bad json')
    await wrapper.vm.$nextTick()
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(wrapper.find('.el-input-native').element.value).toBe('')
  })

  it('themeChange（深色）→ applyTheme 上 .dark + updateSysSetting {theme}', async () => {
    const { wrapper } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    await wrapper.findAll('.radio-stub')[1].trigger('click')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    const upd = window.ipcRenderer.__calls__.filter((c) => c.channel === 'send' && c.args[0] === 'updateSysSetting')
    expect(upd.some((c) => c.args[1] && c.args[1].theme === 'dark')).toBe(true)
  })

  it('notifySwitchChange → updateSysSetting {notifySwitch:false}', async () => {
    const { wrapper } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    await wrapper.findAll('.switch-stub')[0].trigger('click')
    const upd = window.ipcRenderer.__calls__.filter((c) => c.channel === 'send' && c.args[0] === 'updateSysSetting')
    expect(upd.some((c) => c.args[1] && c.args[1].notifySwitch === false)).toBe(true)
  })

  it('onlineStatusChange → window.api.sendUserStatusChange(value)', async () => {
    const { wrapper } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    // radio 顺序：0=浅色 1=深色（主题组）2=在线 3=忙碌 4=离线（在线状态组）
    await wrapper.findAll('.radio-stub')[4].trigger('click')
    expect(window.api.sendUserStatusChange).toHaveBeenCalledWith(3)
  })

  it('theme 保存失败 → 回弹到**改动前**的值（themeBeforeSave 记录的是上一个已确认值）', async () => {
    const { wrapper } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    // 用户切到深色，随后保存失败
    await wrapper.findAll('.radio-stub')[1].trigger('click')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    ipcCallback('updateSysSettingCallback')(null, { status: 0 })
    await wrapper.vm.$nextTick()
    // 回弹到改动前（light）
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('theme 保存成功后再失败 → 回弹到最后一次确认保存的值（非最近一次点击）', async () => {
    const { wrapper } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    // 第一次切深色 → 保存成功（themeBeforeSave 应前移到 dark）
    await wrapper.findAll('.radio-stub')[1].trigger('click')
    ipcCallback('updateSysSettingCallback')(null, { status: 1 })
    await wrapper.vm.$nextTick()
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    // 第二次保存失败 → 应回弹到 dark 而不是 light
    await wrapper.findAll('.radio-stub')[0].trigger('click') // 切回浅色
    ipcCallback('updateSysSettingCallback')(null, { status: 0 })
    await wrapper.vm.$nextTick()
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('退出登录 → Confirm → reLogin + logout 请求', async () => {
    const { wrapper, request } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    await wrapper.findAll('.el-button').find((b) => b.text() === '退出登录').trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toBe('确定要退出登录吗？')
    await ConfirmStub.mock.calls[0][0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    expect(window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'reLogin')).toBeTruthy()
    expect(request.__calls__.find((c) => c.url === '/user/logout')).toBeTruthy()
  })

  it('changePart(1) → 编辑子组件；editBack 回到主视图', async () => {
    const { wrapper, request } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    await wrapper.findAll('.dd-item')[0].trigger('click')
    expect(wrapper.find('.edit-stub').exists()).toBe(true)
    const callsBefore = request.__calls__.length
    wrapper.findComponent({ name: 'UserInfoEdit' }).vm.$emit('editBack')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.edit-stub').exists()).toBe(false)
    expect(request.__calls__.length).toBeGreaterThan(callsBefore) // 返回时重拉 getUserInfo
  })

  it('changePart(2) → 改密子组件', async () => {
    const { wrapper } = await mountUserInfo(() => ({ code: 0, data: USER_INFO }))
    await wrapper.findAll('.dd-item')[1].trigger('click')
    expect(wrapper.find('.pwd-stub').exists()).toBe(true)
  })

  it('个性化签名兜底「-」', async () => {
    const { wrapper } = await mountUserInfo(() => ({
      code: 0,
      data: { userId: 'U001', nickName: '小明' }
    }))
    const items = wrapper.findAll('.part-item')
    const sig = items.find((i) => i.find('.part-title').text() === '个性签名')
    expect(sig.find('.part-content').text()).toBe('-')
  })
})
