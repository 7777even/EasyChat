import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { provide, inject, h } from 'vue'
import Privacy from '@/views/setting/Privacy.vue'

/**
 * 隐私设置统一页的**真实挂载**测试（DOM 级，四区块）。
 *
 * 被测点：
 *   1. 挂载即三连加载：getUserInfo / loadContact / loadBlackList
 *   2. 回填：joinType / momentVisibility / onlineStatusVisible（null 按保守默认）
 *   3. 加我的方式：改 → updateJoinType；失败回滚 origin
 *   4. 朋友圈范围：白/黑名单模式名单为空 → 前端先拦（warning + 开选人器 + 回滚），
 *      不发请求（否则后端 1001）；名单非空 → visibleList/invisibleList 只在对应模式提交
 *   5. 在线状态：visible 0/1 参数化；失败回滚
 *   6. 黑名单：解除 → Confirm → removeBlackList → 移出行 + 提示；失败保留旧行
 *   7. onPickerConfirm：选人后若当前即该模式 → 静默保存（silent 不提示）
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

const ContentPanelStub = {
  name: 'ContentPanel',
  props: ['showTopBorder'],
  template: '<div class="content-panel-stub"><slot /></div>'
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
        // EP：change 在 nextTick 发（v-model 已更新），桩按同序同步发
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
const ElSwitchStub = {
  name: 'ElSwitch',
  props: ['modelValue', 'loading'],
  emits: ['update:modelValue', 'change'],
  template:
    '<button class="switch-stub" @click="$emit(\'update:modelValue\', !modelValue); $emit(\'change\', !modelValue)" />'
}
const ElTagStub = {
  name: 'ElTag',
  props: ['type', 'size'],
  template: '<span class="el-tag-stub"><slot /></span>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'plain', 'link', 'loading'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width', 'lastUpdateTime'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
const ContactPickerStub = {
  name: 'ContactPicker',
  props: ['modelValue', 'title', 'selected'],
  emits: ['update:modelValue', 'confirm'],
  template:
    '<div class="cp-stub" v-if="modelValue" :data-title="title">' +
    '<button class="cp-confirm" @click="$emit(\'confirm\', [...(selected || []), \'U020\'])" /></div>'
}

const USER_INFO = {
  joinType: 1,
  momentVisibility: 0,
  onlineStatusVisible: 1,
  momentVisibleList: '',
  momentInvisibleList: ''
}
const FRIENDS = [
  { contactId: 'U010', contactName: '阿强' },
  { contactId: 'U020', contactName: '阿伟' }
]
const BLACKLIST = [
  { contactId: 'U099', contactName: '小黑', lastUpdateTime: 1770000000000 }
]

// 基础数据 handler：未特指的接口按真实数据返回（好友映射 / 黑名单行 / 用户信息）
function baseHandler (info = USER_INFO) {
  return (opts) => {
    if (opts.url === '/user/getUserInfo') return { code: 0, data: info }
    if (opts.url === '/contact/load') return { code: 0, data: FRIENDS }
    if (opts.url === '/blacklist/load') return { code: 0, data: BLACKLIST }
    return { code: 0, data: null }
  }
}

// 覆盖表合并：overrides 里显式声明的 url 才走覆盖（含「返回 undefined 模拟失败」），
// 其余回落 baseHandler。禁止用 `??` 合并——失败用例正是靠返回 undefined 表达的。
function makeHandler (overrides = {}, info = USER_INFO) {
  const base = baseHandler(info)
  return (opts) => {
    if (Object.prototype.hasOwnProperty.call(overrides, opts.url)) return overrides[opts.url](opts)
    return base(opts)
  }
}

async function mountPrivacy (handler, info = USER_INFO) {
  const request = makeRequest(makeHandler(handler, info))
  const wrapper = mount(Privacy, {
    global: {
      stubs: {
        ContentPanel: ContentPanelStub,
        'el-radio-group': ElRadioGroupStub,
        'el-radio': ElRadioStub,
        'el-switch': ElSwitchStub,
        'el-tag': ElTagStub,
        'el-button': ElButtonStub,
        Avatar: AvatarStub,
        ContactPicker: ContactPickerStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            getUserInfo: '/user/getUserInfo',
            loadContact: '/contact/load',
            loadBlackList: '/blacklist/load',
            updateJoinType: '/privacy/updateJoinType',
            updateMomentPrivacy: '/privacy/updateMomentPrivacy',
            updateOnlineStatusVisible: '/privacy/updateOnlineStatusVisible',
            removeBlackList: '/blacklist/remove'
          },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    },
    attachTo: document.body
  })
  await new Promise((r) => setTimeout(r, 0))
  return { wrapper, request }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
  ConfirmStub.mockClear()
})

describe('Privacy.vue 真实挂载（DOM 级）', () => {
  it('挂载即三连加载', async () => {
    const { request } = await mountPrivacy()
    const urls = request.__calls__.map((c) => c.url)
    expect(urls).toContain('/user/getUserInfo')
    expect(urls).toContain('/contact/load')
    expect(urls).toContain('/blacklist/load')
  })

  it('回填：黑名单行渲染 + 昵称回显', async () => {
    const { wrapper } = await mountPrivacy()
    const rows = wrapper.findAll('.bl-item')
    expect(rows).toHaveLength(1)
    expect(rows[0].find('.bl-name').text()).toBe('小黑')
    expect(rows[0].find('.bl-time').text()).toContain('拉黑于')
    // 可见范围=公开（0）时白/黑名单选择行不渲染（仅 3/4 模式渲染）
    expect(wrapper.text()).not.toContain('谁可以看（白名单）')
  })

  it('joinType null → 按保守默认「需验证」+ 提示历史数据', async () => {
    const { wrapper } = await mountPrivacy({}, { ...USER_INFO, joinType: null })
    expect(wrapper.text()).toContain('当前账号未设置过')
  })

  it('改加我的方式 → updateJoinType 参数 + 成功提示', async () => {
    const { wrapper, request } = await mountPrivacy({
      '/privacy/updateJoinType': () => ({ code: 0, data: 1 })
    })
    const radios = wrapper.findAll('.radio-stub')
    await radios[0].trigger('click') // 直接加入=0
    await flush()
    const call = request.__calls__.find((c) => c.url === '/privacy/updateJoinType')
    expect(call.params).toEqual({ joinType: 0 })
    expect(MessageStub.success).toHaveBeenCalledWith('已更新加我的方式')
  })

  it('改加我的方式失败 → 回滚 origin（不提示成功）', async () => {
    const { wrapper } = await mountPrivacy({ '/privacy/updateJoinType': () => undefined })
    await wrapper.findAll('.radio-stub')[0].trigger('click')
    await flush()
    expect(MessageStub.success).not.toHaveBeenCalled()
    // 回滚后 tips 文案仍在（结构完整），且回到 origin 的需验证分支
    expect(wrapper.find('.tips').text()).toContain('直接加入')
  })

  it('朋友圈切「白名单」且名单为空 → 前端先拦：warning + 开选人器 + 不发请求', async () => {
    const { wrapper, request } = await mountPrivacy({}, { ...USER_INFO, momentVisibleList: '[]' })
    // momentVisibility 选项：0公开 1好友 2自己 3白名单 4黑名单 → radios 索引 2 起
    const radios = wrapper.findAll('.radio-stub')
    await radios[2 + 3].trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('请先选择白名单成员')
    expect(wrapper.find('.cp-stub').exists()).toBe(true)
    expect(request.__calls__.find((c) => c.url === '/privacy/updateMomentPrivacy')).toBeUndefined()
  })

  it('切「黑名单」且名单非空 → updateMomentPrivacy 仅提交 invisibleList', async () => {
    const { wrapper, request } = await mountPrivacy(
      { '/privacy/updateMomentPrivacy': () => ({ code: 0, data: 1 }) },
      { ...USER_INFO, momentVisibility: 0, momentInvisibleList: JSON.stringify(['U099']) }
    )
    const radios = wrapper.findAll('.radio-stub')
    await radios[2 + 4].trigger('click')
    await flush()
    const call = request.__calls__.find((c) => c.url === '/privacy/updateMomentPrivacy')
    expect(call.params.momentVisibility).toBe(4)
    expect(call.params.invisibleList).toBe(JSON.stringify(['U099']))
    expect(call.params.visibleList).toBeNull()
  })

  it('白名单模式下展示名单行 + 好友昵称映射', async () => {
    const { wrapper } = await mountPrivacy({}, {
      ...USER_INFO,
      momentVisibility: 3,
      momentVisibleList: JSON.stringify(['U010'])
    })
    expect(wrapper.text()).toContain('谁可以看（白名单）')
    expect(wrapper.findAll('.el-tag-stub').map((t) => t.text())).toContain('阿强')
    expect(wrapper.find('.tips.warn').exists()).toBe(false)
  })

  it('白名单模式且名单为空 → 警示「没有任何人」', async () => {
    const { wrapper } = await mountPrivacy({}, {
      ...USER_INFO,
      momentVisibility: 3,
      momentVisibleList: '[]'
    })
    expect(wrapper.find('.tips.warn').exists()).toBe(true)
    expect(wrapper.text()).toContain('没有任何人')
  })

  it('在线状态关 → updateOnlineStatusVisible visible=0 + 提示已隐藏', async () => {
    const { wrapper, request } = await mountPrivacy({
      '/privacy/updateOnlineStatusVisible': () => ({ code: 0, data: 1 })
    })
    await wrapper.find('.switch-stub').trigger('click')
    await flush()
    const call = request.__calls__.find((c) => c.url === '/privacy/updateOnlineStatusVisible')
    expect(call.params).toEqual({ visible: 0 })
    expect(MessageStub.success).toHaveBeenCalledWith('已隐藏你的在线状态')
  })

  it('解除黑名单 → Confirm → removeBlackList 参数 → 移出行 + 提示', async () => {
    const { wrapper, request } = await mountPrivacy({
      '/blacklist/remove': () => ({ code: 0, data: 1 })
    })
    expect(wrapper.findAll('.bl-item')).toHaveLength(1)
    await wrapper.findAll('.el-button').find((b) => b.text() === '解除').trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toContain('「小黑」移出黑名单')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(request.__calls__.find((c) => c.url === '/blacklist/remove').params).toEqual({ contactId: 'U099' })
    expect(MessageStub.success).toHaveBeenCalledWith('已移出黑名单')
    expect(wrapper.findAll('.bl-item')).toHaveLength(0)
  })

  it('解除黑名单失败（返回空）→ 保留旧行、不提示成功', async () => {
    const { wrapper } = await mountPrivacy({ '/blacklist/remove': () => undefined })
    await wrapper.findAll('.el-button').find((b) => b.text() === '解除').trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(wrapper.findAll('.bl-item')).toHaveLength(1)
  })

  it('黑名单加载失败 → 不显示「黑名单为空」（Loaded 不置位）', async () => {
    const { wrapper } = await mountPrivacy({ '/blacklist/load': () => undefined })
    // 黑名单区块内无空态文案
    const blPart = wrapper.findAll('.part-item').find((p) => p.find('.part-title').text() === '黑名单')
    expect(blPart.find('.empty-hint').exists()).toBe(false)
  })

  it('onPickerConfirm：当前即白名单模式 → 选人后静默保存（无成功提示）', async () => {
    const { wrapper, request } = await mountPrivacy(
      { '/privacy/updateMomentPrivacy': () => ({ code: 0, data: 1 }) },
      { ...USER_INFO, momentVisibility: 3, momentVisibleList: JSON.stringify(['U010']) }
    )
    await wrapper.findAll('.el-button').find((b) => b.text() === '选择').trigger('click')
    expect(wrapper.find('.cp-stub').exists()).toBe(true)
    await wrapper.find('.cp-confirm').trigger('click')
    await flush()
    const call = request.__calls__.find((c) => c.url === '/privacy/updateMomentPrivacy')
    expect(call.params.visibleList).toBe(JSON.stringify(['U010', 'U020']))
    // silent=true：不弹「已更新朋友圈可见范围」
    expect(MessageStub.success).not.toHaveBeenCalled()
  })
})
