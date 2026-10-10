import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, provide, inject } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import GroupEditForm from '@/views/contact/GroupEditForm.vue'
import { useContactStateStore } from '@/stores/ContactStateStore'
import { useAvatarInfoStore } from '@/stores/AvatarUpdateStore'

/**
 * 群组编辑表单（创建/修改共用）的**真实挂载**测试（DOM 级，真实 pinia store）。
 *
 * 被测点：
 *   1. submit()：创建/修改模式分流（按钮文案、请求参数、成功提示）
 *   2. FormData 组装：groupName/joinType 必带；groupId/groupNotice/avatarFile/avatarCover 条件带
 *   3. **修复前缺陷**：submit 成功分支引用未定义的 `params.groupId`（第 103/114 行）
 *      → ReferenceError → 成功提示不显示、eidtBack 不发（修改弹窗不关）、表单不清空、
 *      contactReload 不置 MY（列表不刷新）、头像不重载（本 spec 首版即抓出）
 *   4. saveCover：AvatarUpload 回写 avatarFile/avatarCover
 *   5. Request 失败（返回空）→ 不置刷新标记、不 emit
 *   6. ContactStateStore/AvatarInfoStore 状态流转
 */

const MessageStub = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

// el-form stub：暴露 validate / resetFields（真实 Element 的 API）
const ElFormStub = {
  name: 'ElForm',
  props: ['model', 'rules', 'labelWidth'],
  setup (_, { expose }) {
    const validate = (cb) => cb(true)
    const resetFields = vi.fn()
    expose({ validate, resetFields })
    return { validate, resetFields }
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
  props: ['modelValue', 'placeholder', 'maxlength', 'clearable', 'type', 'rows', 'showWordLimit', 'resize'],
  emits: ['update:modelValue'],
  template:
    '<input class="el-input-native" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" />'
}
const ElRadioGroupStub = {
  name: 'ElRadioGroup',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  // 真实 Element：radio 通过 group 间接更新 v-model —— 用 provide/inject 复刻该链路
  setup (props, { emit, slots }) {
    provide('radioGroupUpdate', (v) => emit('update:modelValue', v))
    return () => h('div', { class: 'radio-group' }, slots.default?.())
  }
}
const ElRadioStub = {
  name: 'ElRadio',
  props: ['label'],
  setup (props, { slots }) {
    const update = inject('radioGroupUpdate', null)
    return () =>
      h(
        'button',
        {
          class: 'radio-item',
          'data-label': props.label,
          onClick: () => update?.(props.label)
        },
        slots.default?.()
      )
  }
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}
const AvatarUploadStub = {
  name: 'AvatarUpload',
  props: ['modelValue'],
  emits: ['update:modelValue', 'coverFile'],
  // 真实组件暴露 clear()（submit 成功后清空已选头像）——缺桩会让调用点抛 TypeError
  setup (_, { expose }) {
    const clear = vi.fn()
    expose({ clear })
    return () => h('div', { class: 'avatar-upload-stub' })
  }
}

function makeMount (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const request = makeRequest(handler)
  const wrapper = mount(GroupEditForm, {
    global: {
      plugins: [pinia],
      stubs: {
        'el-form': ElFormStub,
        'el-form-item': ElFormItemStub,
        'el-input': ElInputStub,
        'el-radio-group': ElRadioGroupStub,
        'el-radio': ElRadioStub,
        'el-button': ElButtonStub,
        AvatarUpload: AvatarUploadStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { saveGroup: '/group/save' },
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper, request, pinia }
}

async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

beforeEach(() => {
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
})

describe('GroupEditForm.vue 真实挂载（DOM 级）', () => {
  it('初始（创建模式）：按钮「创建群组」', async () => {
    const { wrapper } = makeMount()
    expect(wrapper.find('.el-button').text()).toBe('创建群组')
  })

  it('show(data) 修改模式：回填数据 + 按钮「修改群组」', async () => {
    const { wrapper } = makeMount()
    wrapper.vm.show({ groupId: 'G001', groupName: '家族群', joinType: 1 })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.el-button').text()).toBe('修改群组')
  })

  it('创建群组 submit：FormData 组装 + 成功提示 + 置 MY 刷新', async () => {
    const { wrapper, request, pinia } = makeMount(() => ({ code: 0, data: 0 }))
    // 填表（创建模式）
    const inputs = wrapper.findAll('.el-input-native')
    await inputs[0].setValue('新群')
    await wrapper.findAll('.radio-item')[0].trigger('click') // joinType=1
    await wrapper.find('.el-button').trigger('click')
    await flush()

    expect(request.__calls__).toHaveLength(1)
    const call = request.__calls__[0]
    expect(call.url).toBe('/group/save')
    const fd = call.params
    expect(fd.get('groupName')).toBe('新群')
    // FormData 值一律字符串化
    expect(fd.get('joinType')).toBe('1')
    expect(fd.has('groupId')).toBe(false) // 创建模式不带 groupId

    expect(MessageStub.success).toHaveBeenCalledWith('群组创建成功')
    const store = useContactStateStore()
    expect(store.contactReload).toBe('MY')
    expect(pinia).toBeTruthy()
  })

  it('修改群组 submit：带 groupId + 成功提示「群组修改成功」+ emit eidtBack + 头像重载', async () => {
    const { wrapper, request, pinia } = makeMount(() => ({ code: 0, data: 1 }))
    wrapper.vm.show({ groupId: 'G001', groupName: '家族群', joinType: 1 })
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-button').trigger('click')
    await flush()

    const call = request.__calls__[0]
    expect(call.params.get('groupId')).toBe('G001')
    expect(call.params.get('groupName')).toBe('家族群')

    expect(MessageStub.success).toHaveBeenCalledWith('群组修改成功')
    expect(wrapper.emitted('eidtBack')).toHaveLength(1)
    const avatarStore = useAvatarInfoStore()
    // 保存前 setFoceReload(G001, false)，成功后置 true
    expect(avatarStore.getFoceReload('G001')).toBe(true)
    expect(pinia).toBeTruthy()
  })

  it('saveCover：AvatarUpload 回写 avatarFile/avatarCover 到 FormData', async () => {
    const { wrapper, request } = makeMount(() => ({ code: 0, data: 0 }))
    const f1 = new File(['a'], 'a.png')
    const f2 = new File(['b'], 'b.png')
    wrapper.findComponent(AvatarUploadStub).vm.$emit('coverFile', {
      avatarFile: f1,
      coverFile: f2
    })
    await wrapper.vm.$nextTick()
    await wrapper.findAll('.el-input-native')[0].setValue('群')
    await wrapper.findAll('.radio-item')[0].trigger('click')
    await wrapper.find('.el-button').trigger('click')
    await flush()

    const fd = request.__calls__[0].params
    expect(fd.get('avatarFile')).toBe(f1)
    expect(fd.get('avatarCover')).toBe(f2)
  })

  it('Request 返回空（失败）→ 不置刷新、不 emit、不提示成功', async () => {
    const { wrapper, pinia } = makeMount(() => undefined)
    wrapper.vm.show({ groupId: 'G001', groupName: '家族群', joinType: 1 })
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-button').trigger('click')
    await flush()

    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(wrapper.emitted('eidtBack')).toBeUndefined()
    const store = useContactStateStore()
    expect(store.contactReload).toBeNull()
    expect(pinia).toBeTruthy()
  })

  it('contactType 透传：saveCover 后的 groupNotice 条件追加', async () => {
    const { wrapper, request } = makeMount(() => ({ code: 0, data: 0 }))
    wrapper.vm.show({ groupId: 'G002', groupName: '有公告群', joinType: 0, groupNotice: '周末活动' })
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-button').trigger('click')
    await flush()
    const fd = request.__calls__[0].params
    expect(fd.get('groupNotice')).toBe('周末活动')
    expect(fd.get('joinType')).toBe('0')
  })
})
