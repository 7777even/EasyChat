import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SearchAdd from '@/views/contact/SearchAdd.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { useContactStateStore } from '@/stores/ContactStateStore'

/**
 * 「提交申请」弹窗（加好友 / 加群附言）的**真实挂载**测试（DOM 级，真实 pinia store）。
 *
 * 被测点：
 *   1. show(data)：打开弹窗 + resetFields + 回填 data + 附言预填「我是{昵称}」
 *   2. submitApply 参数：contactId / applyInfo / contactType 原样透传
 *   3. result.data==0（直接加入成功）→ 提示「加入成功」+ setContactReload(contactType)
 *   4. result.data!=0（需审核）→ 提示「申请成功，等待对方同意」+ **不**置刷新标记
 *   5. 失败（返回空）→ 不关窗、不 emit reload、不提示
 *   6. 成功后：关窗 + emit('reload')
 */

const MessageStub = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: 1 }
  })
  fn.__calls__ = calls
  return fn
}

// Dialog 桩：照抄真实 Dialog 的透传契约（show/title/buttons 进，close 出）
const DialogStub = {
  name: 'Dialog',
  props: ['show', 'title', 'buttons', 'width', 'showCancel'],
  emits: ['close'],
  template:
    '<div class="dialog-stub" v-if="show"><div class="d-title">{{ title }}</div>' +
    '<div class="d-body"><slot /></div>' +
    '<button v-for="b in buttons" :key="b.text" class="d-btn" @click="b.click">{{ b.text }}</button>' +
    '<button class="d-close" @click="$emit(\'close\')">X</button></div>'
}
const ElFormStub = {
  name: 'ElForm',
  props: ['model', 'ref'],
  setup (_, { expose }) {
    const resetFields = vi.fn()
    expose({ resetFields })
    return { resetFields }
  },
  template: '<form class="el-form-stub"><slot /></form>'
}
const ElFormItemStub = {
  name: 'ElFormItem',
  props: ['label', 'prop'],
  template: '<div class="el-form-item-stub"><slot /></div>'
}
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'maxlength', 'type', 'rows', 'clearable', 'showWordLimit', 'resize'],
  emits: ['update:modelValue'],
  template:
    '<input class="el-input-native" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" />'
}

async function makeMount (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const request = makeRequest(handler)
  const userInfo = useUserInfoStore()
  userInfo.setInfo({ userId: 'U001', nickName: '小明' })
  const wrapper = mount(SearchAdd, {
    global: {
      plugins: [pinia],
      stubs: {
        Dialog: DialogStub,
        'el-form': ElFormStub,
        'el-form-item': ElFormItemStub,
        'el-input': ElInputStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { applyAdd: '/contact/applyAdd' },
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper, request, pinia }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
})

describe('SearchAdd.vue 真实挂载（DOM 级）', () => {
  it('初始：弹窗不显示', async () => {
    const { wrapper } = await makeMount()
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
  })

  it('show(data)：开窗（标题「提交申请」）+ 回填 contactId/contactType + 附言预填昵称', async () => {
    const { wrapper } = await makeMount()
    wrapper.vm.show({ contactId: 'U010', contactType: 'USER' })
    await flush()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.find('.d-title').text()).toBe('提交申请')
    const input = wrapper.find('.el-input-native')
    expect(input.element.value).toBe('我是小明')
  })

  it('提交申请（需审核 data=1）：参数透传 + 提示「申请成功」+ 关窗 + emit reload', async () => {
    const { wrapper, request } = await makeMount(() => ({ code: 0, data: 1 }))
    wrapper.vm.show({ contactId: 'U010', contactType: 'USER' })
    await flush()
    await wrapper.find('.el-input-native').setValue('我是小明，加个好友')
    await wrapper.find('.d-btn').trigger('click')
    await flush()

    expect(request.__calls__[0].url).toBe('/contact/applyAdd')
    expect(request.__calls__[0].params).toEqual({
      contactId: 'U010',
      applyInfo: '我是小明，加个好友',
      contactType: 'USER'
    })
    expect(MessageStub.success).toHaveBeenCalledWith('申请成功，等待对方同意')
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })

  it('直接加入成功（data=0）→ 提示「加入成功」+ setContactReload(contactType)', async () => {
    const { wrapper, pinia } = await makeMount(() => ({ code: 0, data: 0 }))
    wrapper.vm.show({ contactId: 'G001', contactType: 'GROUP' })
    await flush()
    await wrapper.find('.d-btn').trigger('click')
    await flush()

    expect(MessageStub.success).toHaveBeenCalledWith('加入成功')
    const store = useContactStateStore()
    expect(store.contactReload).toBe('GROUP')
    expect(pinia).toBeTruthy()
  })

  it('需审核（data=1）→ 不置刷新标记（不打扰联系人列表）', async () => {
    const { wrapper, pinia } = await makeMount(() => ({ code: 0, data: 1 }))
    wrapper.vm.show({ contactId: 'U010', contactType: 'USER' })
    await flush()
    await wrapper.find('.d-btn').trigger('click')
    await flush()

    const store = useContactStateStore()
    expect(store.contactReload).toBeNull()
    expect(pinia).toBeTruthy()
  })

  it('提交前先清空刷新标记（防上一次残留误触发刷新）', async () => {
    const { wrapper, pinia } = await makeMount(() => ({ code: 0, data: 1 }))
    const store = useContactStateStore()
    store.setContactReload('MY')
    wrapper.vm.show({ contactId: 'U010', contactType: 'USER' })
    await flush()
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    // submitApply 第一步即置 null；后续因 data=1 不再置值
    expect(store.contactReload).toBeNull()
    expect(pinia).toBeTruthy()
  })

  it('Request 返回空（失败）→ 不关窗、不 emit、不提示成功', async () => {
    const { wrapper, request, pinia } = await makeMount(() => undefined)
    wrapper.vm.show({ contactId: 'U010', contactType: 'USER' })
    await flush()
    await wrapper.find('.d-btn').trigger('click')
    await flush()

    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.emitted('reload')).toBeUndefined()
    expect(request).toBeTruthy()
    expect(pinia).toBeTruthy()
  })

  it('Dialog close（取消/X）→ 仅关窗，不 emit reload', async () => {
    const { wrapper } = await makeMount()
    wrapper.vm.show({ contactId: 'U010', contactType: 'USER' })
    await flush()
    await wrapper.find('.d-close').trigger('click')
    await flush()
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    expect(wrapper.emitted('reload')).toBeUndefined()
  })
})
