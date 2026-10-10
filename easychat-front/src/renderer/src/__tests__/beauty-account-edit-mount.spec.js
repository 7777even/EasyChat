import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import BeautyAccountEdit from '@/views/admin/BeautyAccountEdit.vue'

/**
 * 靓号新增/编辑对话框的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. showEdit(data) → 打开对话框并回填表单
 *   2. 确定 → submitForm：校验不通过不发请求
 *   3. 合法数据 → Request(saveBeautAccount) 带参数 + 关闭 + emit reload
 *   4. 校验规则：邮箱格式、靓号 11 位、必填文案
 */

// el-form stub 需真正执行 rules，否则校验分支不可观测
const elFormStub = {
  name: 'el-form',
  props: ['model', 'rules', 'labelWidth'],
  emits: ['submit'],
  data() {
    return { resetCalled: 0 }
  },
  methods: {
    resetFields() { this.resetCalled++ },
    validate(cb) {
      const errors = []
      const model = this.model || {}
      const rules = this.rules || {}
      for (const [field, fieldRules] of Object.entries(rules)) {
        const value = model[field]
        for (const r of fieldRules) {
          if (r.required && (value === undefined || value === null || value === '')) {
            errors.push({ field, message: r.message })
            break
          }
          if (r.validator && !r.validator(value)) {
            errors.push({ field, message: r.message })
            break
          }
          const len = String(value ?? '').length
          if (r.min != null && len < r.min) {
            errors.push({ field, message: r.message })
            break
          }
          if (r.max != null && len > r.max) {
            errors.push({ field, message: r.message })
            break
          }
        }
      }
      cb(errors.length === 0, errors)
    }
  },
  template: '<form class="el-form"><slot /></form>'
}

const stubs = {
  // Dialog 未 stub 时会退化成未知元素：其插槽仍渲染（所以 el-form 在场），
  // 但 title/buttons/@close 全都没经过 Dialog → 按钮链路实际没被测到
  Dialog: {
    name: 'Dialog',
    props: ['title', 'buttons', 'show', 'width'],
    emits: ['close'],
    template: `<div class="dialog-stub" v-if="show">
      <div class="dialog-title">{{ title }}</div>
      <slot />
      <button
        v-for="(btn, bi) in buttons"
        :key="bi"
        class="dialog-btn"
        @click="btn.click"
      >{{ btn.text }}</button>
    </div>`
  },
  'el-form': elFormStub,
  'el-form-item': { name: 'el-form-item', props: ['label', 'prop'], template: '<div class="el-form-item"><slot /></div>' },
  'el-input': {
    name: 'el-input',
    props: ['modelValue', 'maxLength', 'placeholder'],
    emits: ['update:modelValue'],
    template: `<input class="el-input" :value="modelValue" :placeholder="placeholder"
      @input="$emit('update:modelValue', $event.target.value.trim())" />`
  }
}

let request

function makeRequest(handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountDialog() {
  request = makeRequest()
  const wrapper = mount(BeautyAccountEdit, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { saveBeautAccount: '/admin/saveBeautAccount' },
          Verify: {
            email: (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v),
            number: (v) => /^\d+$/.test(v)
          }
        }
      }
    }
  })
  return wrapper
}

describe('BeautyAccountEdit.vue 真实挂载（DOM 级）', () => {
  it('初始不展示（show=false）', () => {
    const wrapper = mountDialog()
    expect(wrapper.vm.dialogConfig.show).toBe(false)
  })

  it('showEdit(data) → 打开 + 回填表单', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ email: 'a@b.com', userId: '10001' })
    expect(wrapper.vm.dialogConfig.show).toBe(true)
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData.email).toBe('a@b.com')
    expect(wrapper.vm.formData.userId).toBe('10001')
  })

  it('校验规则挂到 el-form：邮箱/靓号两条规则', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.showEdit({})
    const form = wrapper.findComponent({ name: 'el-form' })
    const rules = form.props('rules')
    expect(rules.email).toHaveLength(2)
    expect(rules.userId).toHaveLength(3)
    // 必填文案必须与字段语义相符（曾误抄版本管理页的文案）
    expect(rules.email[0].message).toBe('请输入邮箱')
    expect(rules.userId[0].message).toBe('请输入靓号')
    // 规则本体
    expect(rules.email[1].message).toBe('请输入正确的邮箱')
    expect(rules.userId[1]).toMatchObject({ min: 11, max: 11, message: '靓号必须11位' })
    expect(rules.userId[2].message).toBe('靓号只能是数字')
  })

  it('空表单提交 → 校验不通过且不发请求', async () => {
    const wrapper = mountDialog()
    // 打开对话框并等表单渲染（formDataRef 才有值）
    wrapper.vm.showEdit({})
    await new Promise((r) => setTimeout(r, 0))
    // 点 Dialog 上的「确定」按钮，链路经 stub 渲染的 dialog-btn
    await wrapper.find('.dialog-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.length).toBe(0)
    // 校验失败不关闭
    expect(wrapper.vm.dialogConfig.show).toBe(true)
  })

  it('邮箱非法 → 校验失败文案「请输入正确的邮箱」', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.showEdit({})
    // 非空但格式非法：跳过 required 规则，命中 validator 规则
    wrapper.vm.formData.email = 'not-an-email'
    const form = wrapper.findComponent({ name: 'el-form' })
    form.vm.validate((ok, errors) => {
      expect(ok).toBe(false)
      expect(errors[0]).toMatchObject({ field: 'email', message: '请输入正确的邮箱' })
    })
  })

  it('靓号非 11 位 → 校验失败文案「靓号必须11位」', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.showEdit({})
    wrapper.vm.formData.email = 'a@b.com'
    wrapper.vm.formData.userId = '123'
    const form = wrapper.findComponent({ name: 'el-form' })
    form.vm.validate((ok, errors) => {
      expect(ok).toBe(false)
      expect(errors[0]).toMatchObject({ field: 'userId', message: '靓号必须11位' })
    })
  })

  it('合法提交 → Request + 关闭 + emit reload', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ email: 'a@b.com', userId: '10000000001' })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.dialog-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__).toHaveLength(1)
    expect(request.__calls__[0].url).toBe('/admin/saveBeautAccount')
    expect(request.__calls__[0].params).toMatchObject({ email: 'a@b.com', userId: '10000000001' })
    expect(wrapper.vm.dialogConfig.show).toBe(false)
    expect(wrapper.emitted('reload')).toBeTruthy()
  })
})
