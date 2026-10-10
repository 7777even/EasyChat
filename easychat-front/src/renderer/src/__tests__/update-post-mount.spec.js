import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UpdatePost from '@/views/admin/UpdatePost.vue'

/**
 * 发布更新弹窗的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. showEdit：grayscaleUid 由逗号串转数组 / 缺失时空数组
 *   2. 灰度面板仅在 status==1 出现
 *   3. 灰度 UID：标签删除、新增（回车/失焦均提交）、空输入不添加
 *   4. submitForm：status 必填；提交时 grayscaleUid 以逗号串发出
 */

const stubs = {
  Dialog: {
    name: 'Dialog',
    props: ['title', 'buttons', 'show', 'width'],
    emits: ['close'],
    template: `<div class="dialog-stub" v-if="show">
      <div class="dialog-title">{{ title }}</div>
      <slot />
      <button v-for="(btn, bi) in buttons" :key="bi" class="dialog-btn" @click="btn.click">{{ btn.text }}</button>
    </div>`
  },
  'el-form': {
    name: 'el-form',
    props: ['model', 'rules', 'labelWidth'],
    data() {
      return { resetCalled: 0 }
    },
    methods: {
      resetFields() { this.resetCalled++ },
      validate(cb) {
        const model = this.model || {}
        const rules = this.rules || {}
        for (const [field, fieldRules] of Object.entries(rules)) {
          const value = model[field]
          const list = Array.isArray(fieldRules) ? fieldRules : [fieldRules]
          for (const r of list) {
            if (value === undefined || value === null || value === '') {
              if (r.required) { cb(false, [{ field, message: r.message }]); return }
              continue
            }
          }
        }
        cb(true, [])
      }
    },
    template: '<form><slot /></form>'
  },
  'el-form-item': {
    name: 'el-form-item',
    props: ['label', 'prop'],
    template: '<div class="el-form-item"><slot /></div>'
  },
  'el-radio-group': {
    name: 'el-radio-group',
    props: ['modelValue'],
    template: '<div class="el-radio-group"><slot /></div>'
  },
  'el-radio': { name: 'el-radio', props: ['label'], template: '<div class="el-radio"><slot /></div>' },
  'el-tag': {
    name: 'el-tag',
    props: ['closable', 'type'],
    emits: ['close'],
    template: '<span class="el-tag" @click="$emit(\'close\')"><slot /></span>'
  },
  'el-input': {
    name: 'el-input',
    props: ['modelValue', 'size', 'clearable', 'placeholder'],
    emits: ['update:modelValue', 'blur', 'keyup'],
    template: `<input class="el-input" :value="modelValue" :placeholder="placeholder"
      @input="$emit('update:modelValue', $event.target.value.trim())"
      @blur="$emit('blur', $event)" @keyup.enter="$emit('keyup', $event)" />`
  },
  'el-button': {
    name: 'el-button',
    props: ['type', 'size'],
    emits: ['click'],
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`
  }
}

let request

function makeRequest() {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountDialog() {
  request = makeRequest()
  const wrapper = mount(UpdatePost, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { postUpdate: '/admin/postUpdate' }
        }
      }
    }
  })
  return wrapper
}

describe('UpdatePost.vue 真实挂载（DOM 级）', () => {
  it('初始不展示', () => {
    const wrapper = mountDialog()
    expect(wrapper.vm.dialogConfig.show).toBe(false)
  })

  it('showEdit → 回填版本号并把 grayscaleUid 转数组', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 1, grayscaleUid: 'U1,U2' })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData).toMatchObject({ id: 5, version: '1.2.0', status: 1 })
    expect(wrapper.vm.formData.grayscaleUid).toEqual(['U1', 'U2'])
    expect(wrapper.find('.dialog-title').text()).toBe('发布更新')
  })

  it('无 grayscaleUid → 空数组', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 2 })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData.grayscaleUid).toEqual([])
  })

  it('grayscaleUid 传数组 → 不崩（入参容错）', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 1, grayscaleUid: ['U1', 'U2'] })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData.grayscaleUid).toEqual(['U1', 'U2'])
  })

  it('status != 1 → 不显示灰度面板', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 2 })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.tag-panel').exists()).toBe(false)
  })

  it('status == 1 → 已选 UID 标签 + 新增按钮', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 1, grayscaleUid: 'U1,U2' })
    await new Promise((r) => setTimeout(r, 0))
    const tags = wrapper.findAll('.el-tag')
    expect(tags.map((t) => t.text())).toEqual(['U1', 'U2'])
    expect(wrapper.findAll('.el-button').map((b) => b.text())).toEqual(['新增'])
    expect(wrapper.find('.dialog-btn').text()).toBe('确定')
  })

  it('closeTag → 删除指定 UID', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 1, grayscaleUid: 'U1,U2' })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.findAll('.el-tag')[0].trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData.grayscaleUid).toEqual(['U2'])
  })

  it('新增 UID：点新增 → 输入框；回车加入并收起', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 1, grayscaleUid: 'U1' })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.findAll('.el-button').find((b) => b.text() === '新增').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.tag-panel .el-input').exists()).toBe(true)
    await wrapper.find('.tag-panel .el-input').setValue('U3')
    await wrapper.find('.tag-panel .el-input').trigger('keyup.enter')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData.grayscaleUid).toEqual(['U1', 'U3'])
    // 输入框收起
    expect(wrapper.find('.tag-panel .el-input').exists()).toBe(false)
  })

  it('失焦也提交 UID；空输入不添加', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 1, grayscaleUid: [] })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.findAll('.el-button').find((b) => b.text() === '新增').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    // 空输入失焦：不加 tag
    await wrapper.find('.tag-panel .el-input').trigger('blur')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData.grayscaleUid).toEqual([])
    // 再开一次输入有值失焦：加入
    await wrapper.findAll('.el-button').find((b) => b.text() === '新增').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.tag-panel .el-input').setValue('U9')
    await wrapper.find('.tag-panel .el-input').trigger('blur')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData.grayscaleUid).toEqual(['U9'])
  })

  it('rules 仅 status 必填', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0' })
    await new Promise((r) => setTimeout(r, 0))
    const rules = wrapper.findComponent({ name: 'el-form' }).props('rules')
    expect(Object.keys(rules)).toEqual(['status'])
  })

  it('无 status → 校验不通过不发请求', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', grayscaleUid: '' })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.dialog-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.length).toBe(0)
  })

  it('提交 → grayscaleUid 以逗号串发出 + 关闭 + reload', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ id: 5, version: '1.2.0', status: 1, grayscaleUid: 'U1,U2' })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.dialog-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__).toHaveLength(1)
    expect(request.__calls__[0].url).toBe('/admin/postUpdate')
    expect(request.__calls__[0].params).toMatchObject({ id: 5, version: '1.2.0', status: 1 })
    expect(request.__calls__[0].params.grayscaleUid).toBe('U1,U2')
    expect(wrapper.vm.dialogConfig.show).toBe(false)
    expect(wrapper.emitted('reload')).toBeTruthy()
  })
})
