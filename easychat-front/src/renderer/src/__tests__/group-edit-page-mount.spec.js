import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import GroupEdit from '@/views/contact/GroupEdit.vue'
import GroupEditForm from '@/views/contact/GroupEditForm.vue'

/**
 * 群组编辑页（ContentPanel + GroupEditForm 包装壳）的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. ContentPanel 正确透传（不吞掉子内容）——包装层无断裂
 *   2. GroupEditForm 以真实子组件形式挂载（Element 控件用 stub 兜底），
 *      其内部表单控件可达（form 根 + 5 个 form-item + 提交按钮）
 *   3. ContentPanel 的 showTopBorder 契约：GroupEdit 未显式绑该 prop（收到 undefined，
 *      默认值由 ContentPanel 自身 default:false 兜底）；slot 透传无断裂。
 *      ⚠ 已知覆盖缺口：border-top: 1px solid var(--ec-border) 这条 style 分支在 jsdom 下
 *      不可观测——jsdom 对值含 var() 的 style 属性整体丢弃（已实证 attributes('style') 恒为
 *      undefined），故此处只验 props 契约，不假装覆盖样式分支（AGENTS §2.1 第 14 条）。
 *
 * 逻辑本体（submit / saveCover / eidtBack）归 group-edit-form-mount.spec.js 与
 * group-edit-dialog-mount.spec.js；本 spec 只守「包装层接线不断裂」。
 */

const ContentPanelStub = {
  name: 'ContentPanel',
  props: ['showTopBorder'],
  template: '<div class="content-panel-stub"><slot /></div>'
}
// GroupEditForm 内部的 Element 组件（此处只验可达性，行为归 group-edit-form spec）
const ElFormStub = { name: 'ElForm', template: '<form class="el-form-stub"><slot /></form>' }
const ElFormItemStub = { name: 'ElFormItem', template: '<div class="el-form-item"><slot /></div>' }
const ElInputStub = { name: 'ElInput', template: '<input class="el-input-stub" />' }
const ElButtonStub = {
  name: 'ElButton',
  template: '<button class="el-button"><slot /></button>'
}
const AvatarUploadStub = { name: 'AvatarUpload', template: '<div class="avatar-upload-stub" />' }
const FormStubs = {
  'el-form': ElFormStub,
  'el-form-item': ElFormItemStub,
  'el-input': ElInputStub,
  'el-button': ElButtonStub,
  AvatarUpload: AvatarUploadStub
}

function makeMount (stubs = {}) {
  // GroupEditForm 内部用 useContactStateStore → 需要 active pinia
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(GroupEdit, {
    global: {
      plugins: [pinia],
      stubs: { ContentPanel: ContentPanelStub, ...FormStubs, ...stubs }
    }
  })
}

describe('GroupEdit.vue 真实挂载（DOM 级）', () => {
  it('渲染内部 GroupEditForm（包装层不吞内容）', () => {
    const wrapper = makeMount()
    expect(wrapper.findComponent(GroupEditForm).exists()).toBe(true)
  })

  it('GroupEditForm 为真实子组件：内部表单控件可达（5 个 form-item + 提交按钮）', () => {
    const wrapper = makeMount()
    const form = wrapper.findComponent(GroupEditForm)
    // el-form 渲染为 <form>（无 class），故断言真实表单控件而非选择器
    expect(form.find('form').exists()).toBe(true)
    expect(form.findAll('.el-form-item')).toHaveLength(5)
    expect(form.findAll('button').length).toBeGreaterThan(0)
  })

  it('GroupEdit 未显式传 showTopBorder → stub 收到 undefined（默认值由 ContentPanel 自身兜底）', () => {
    const wrapper = makeMount()
    const panel = wrapper.findComponent(ContentPanelStub)
    expect(panel.exists()).toBe(true)
    // GroupEdit 模板只写 <ContentPanel>，未绑该 prop → 传入值为 undefined
    expect(panel.props('showTopBorder')).toBeUndefined()
  })

  it('真实 ContentPanel：渲染 slot，表单落入 content-inner', async () => {
    const { default: RealContentPanel } = await import('@/components/ContentPanel.vue')
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(GroupEdit, {
      global: {
        plugins: [pinia],
        stubs: { ContentPanel: RealContentPanel, ...FormStubs }
      }
    })
    expect(wrapper.find('.content-panel').exists()).toBe(true)
    // slot 内容（真实表单）已落入 content-inner（ElFormStub 根为 form.el-form-stub）
    expect(wrapper.find('.content-inner form.el-form-stub').exists()).toBe(true)
  })

  it('ContentPanel showTopBorder → style 契约：jsdom 丢弃含 var() 的 style，故只验 props 契约', async () => {
    const { default: RealContentPanel } = await import('@/components/ContentPanel.vue')
    // 已实证：jsdom 对含 var(--x) 的 style 属性整体丢弃（attributes('style') 恒为 undefined），
    // 因此 border-top 分支在 jsdom 下**不可观测**，此处登记为已知覆盖缺口（不假装覆盖）。
    // 可观测面：showTopBorder 作为 Boolean prop 可被正确接收与默认化。
    const off = mount(RealContentPanel, { slots: { default: '<div class="probe" />' } })
    expect(off.props('showTopBorder')).toBe(false)
    const on = mount(RealContentPanel, {
      props: { showTopBorder: true },
      slots: { default: '<div class="probe" />' }
    })
    expect(on.props('showTopBorder')).toBe(true)
    // slot 两种状态均正常透传
    expect(off.find('.probe').exists()).toBe(true)
    expect(on.find('.probe').exists()).toBe(true)
  })
})
