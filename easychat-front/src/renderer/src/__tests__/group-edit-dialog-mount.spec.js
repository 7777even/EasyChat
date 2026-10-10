import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GroupEditDialog from '@/views/contact/GroupEditDialog.vue'

/**
 * 群组编辑弹窗的**真实挂载**测试（DOM 级）。
 *
 * 被测点（本组件是纯编排层，逻辑全在两个子组件的接缝上）：
 *   1. show(data)：打开弹窗 + nextTick 后把 data 透传给 GroupEditForm.show
 *   2. eidtBack（表单保存成功回传）：关弹窗 + emit reloadGroupInfo
 *   3. Dialog 的 close（右上角 X）：仅关弹窗，不 emit reloadGroupInfo
 *      （关键区分：关窗 ≠ 保存成功，二者触发的副作用不同）
 *
 * Dialog 的样式分支（var() style）在 jsdom 下不可观测，已在
 * group-edit-page-mount.spec.js 登记为已知覆盖缺口，此处不重复。
 */

// Dialog 桩：照抄真实 Dialog 的透传契约（props 进、close 出），只把 el-dialog 展开为可断言的 DOM
const DialogStub = {
  name: 'Dialog',
  props: ['show', 'title', 'buttons', 'showCancel'],
  emits: ['close'],
  template:
    '<div class="dialog-stub" v-if="show"><div class="d-title">{{ title }}</div>' +
    '<div class="d-body"><slot /></div>' +
    '<button class="d-close" @click="$emit(\'close\')">X</button></div>'
}

// GroupEditForm 桩：记录 show() 收到的 data，并可手动触发 eidtBack
let capturedShowData
const FormStub = {
  name: 'GroupEditForm',
  emits: ['eidtBack'],
  setup (_, { expose }) {
    const show = (data) => { capturedShowData = data }
    expose({ show })
    return {}
  },
  template: '<div class="form-stub" />'
}

function makeMount () {
  return mount(GroupEditDialog, {
    global: {
      stubs: { Dialog: DialogStub, GroupEditForm: FormStub }
    }
  })
}

const flush = () => new Promise((r) => setTimeout(r, 0))

describe('GroupEditDialog.vue 真实挂载（DOM 级）', () => {
  it('初始：弹窗不显示，GroupEditForm 未收到 show 调用', () => {
    capturedShowData = undefined
    const wrapper = makeMount()
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    expect(capturedShowData).toBeUndefined()
    expect(wrapper.find('.form-stub').exists()).toBe(false)
  })

  it('show(data)：打开弹窗（标题「修改群组」）+ 透传 data 给表单', async () => {
    capturedShowData = undefined
    const wrapper = makeMount()
    const payload = { groupId: 'G001', groupName: '研发群' }
    wrapper.vm.show(payload)
    await flush() // show() 内部用 nextTick 透传，需等一拍
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.find('.d-title').text()).toBe('修改群组')
    expect(capturedShowData).toBe(payload) // 同一引用，非拷贝
  })

  it('eidtBack（保存成功）：关弹窗 + emit reloadGroupInfo', async () => {
    capturedShowData = undefined
    const wrapper = makeMount()
    wrapper.vm.show({ groupId: 'G001' })
    await flush()

    // 表单保存成功 → 回传 eidtBack（GroupEditDialog 内部监听它）
    wrapper.findComponent(FormStub).vm.$emit('eidtBack')
    await flush()

    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    expect(wrapper.emitted('reloadGroupInfo')).toHaveLength(1)
  })

  it('Dialog 的 close（X 关窗）：只关弹窗，不 emit reloadGroupInfo', async () => {
    capturedShowData = undefined
    const wrapper = makeMount()
    wrapper.vm.show({ groupId: 'G001' })
    await flush()

    await wrapper.find('.d-close').trigger('click')
    await flush()

    // 关窗 ≠ 保存成功：不触发列表重载
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    expect(wrapper.emitted('reloadGroupInfo')).toBeUndefined()
  })

  it('多次 show(data)：每次都重新透传最新 data', async () => {
    capturedShowData = undefined
    const wrapper = makeMount()
    wrapper.vm.show({ groupId: 'A' })
    await flush()
    wrapper.vm.show({ groupId: 'B' })
    await flush()
    expect(capturedShowData).toEqual({ groupId: 'B' })
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
  })
})
