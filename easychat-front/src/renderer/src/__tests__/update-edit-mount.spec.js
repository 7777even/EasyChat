import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UpdateEdit from '@/views/admin/UpdateEdit.vue'

/**
 * 发布/编辑版本对话框的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. showEdit：新增默认一行空内容 / 编辑回填（含 updateDescArray→List 转换
 *      与 fileName 拼接）
 *   2. fileType 分支：0 文件选择 / 1 外链输入
 *   3. selectFile → file + fileName
 *   4. 更新内容行：新增 / 删除 / 按钮显隐（仅第一行可加、仅后续行可删）
 *   5. submitForm：校验守卫 + updateDesc 用 | 连接且不带 updateDescList
 */

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
        const list = Array.isArray(fieldRules) ? fieldRules : [fieldRules]
        for (const r of list) {
          if (value === undefined || value === null || value === '') {
            if (r.required) { errors.push({ field, message: r.message }); break }
            continue
          }
          if (r.validator && !r.validator(value)) { errors.push({ field, message: r.message }); break }
        }
      }
      cb(errors.length === 0, errors)
    }
  },
  template: '<form><slot /></form>'
}

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
  'el-form': elFormStub,
  'el-form-item': {
    name: 'el-form-item',
    // 不声明 class prop：class/file-select 等透传属性需自动落到根元素，
    // 一旦声明为 prop 就会被 stub 吃掉、与真实 el-form-item 行为不符
    props: ['label', 'prop', 'rules'],
    template: '<div class="el-form-item"><slot /></div>'
  },
  'el-input': {
    name: 'el-input',
    props: ['modelValue', 'maxLength', 'placeholder'],
    emits: ['update:modelValue'],
    template: `<input class="el-input" :value="modelValue" :placeholder="placeholder"
      @input="$emit('update:modelValue', $event.target.value)" />`
  },
  'el-radio-group': {
    name: 'el-radio-group',
    props: ['modelValue'],
    template: '<div class="el-radio-group"><slot /></div>'
  },
  'el-radio': { name: 'el-radio', props: ['label'], template: '<div class="el-radio"><slot /></div>' },
  'el-upload': {
    name: 'el-upload',
    props: ['name', 'showFileList', 'accept', 'multiple', 'httpRequest'],
    template: '<div class="el-upload"><slot /></div>'
  },
  'el-button': {
    name: 'el-button',
    props: ['type', 'size'],
    emits: ['click'],
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`
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
  const wrapper = mount(UpdateEdit, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { saveUpdate: '/admin/saveUpdate' },
          Verify: {
            version: (v) => /^[\d.]+$/.test(v)
          }
        }
      }
    }
  })
  return wrapper
}

describe('UpdateEdit.vue 真实挂载（DOM 级）', () => {
  it('初始不展示', () => {
    const wrapper = mountDialog()
    expect(wrapper.vm.dialogConfig.show).toBe(false)
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
  })

  it('showEdit() 无参 → 打开 + 默认一行空内容', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit()
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.dialogConfig.show).toBe(true)
    expect(wrapper.find('.dialog-title').text()).toBe('发布更新')
    expect(wrapper.vm.formData.updateDescList).toEqual([{ title: '' }])
    expect(wrapper.findAll('.update-desc-item')).toHaveLength(1)
  })

  it('showEdit(data) → 回填 + updateDescArray 转 List + fileName 拼接', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({
      id: 3,
      version: '1.5.0',
      updateDescArray: ['修了一个问题', '优化性能']
    })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.formData.id).toBe(3)
    expect(wrapper.vm.formData.version).toBe('1.5.0')
    expect(wrapper.vm.formData.updateDescList).toEqual([{ title: '修了一个问题' }, { title: '优化性能' }])
    expect(wrapper.vm.formData.fileName).toBe('EasyChat.1.5.0.exe')
    expect(wrapper.findAll('.update-desc-item')).toHaveLength(2)
  })

  it('新增未选文件类型 → 文件/外链两分支均不显示', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit()
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.file-select').exists()).toBe(false)
    expect(wrapper.findAll('.el-input').some((i) => i.attributes('placeholder') === '请输入完整的外链地址')).toBe(false)
  })

  it('fileType=0 → 文件选择区（含 http-request 回调）', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ fileType: 0, version: '1.0.0', updateDescArray: [] })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.file-select').exists()).toBe(true)
    // fileName 由版本号拼出
    expect(wrapper.find('.file-name').text()).toBe('EasyChat.1.0.0.exe')
    expect(wrapper.find('.el-upload').exists()).toBe(true)
    expect(typeof wrapper.findComponent({ name: 'el-upload' }).props('httpRequest')).toBe('function')
  })

  it('fileType=1 → 外链输入', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ fileType: 1 })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.file-select').exists()).toBe(false)
    const input = wrapper.findAll('.el-input').find((i) => i.attributes('placeholder') === '请输入完整的外链地址')
    expect(input).toBeTruthy()
  })

  it('selectFile → file + fileName 落表单', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ fileType: 0 })
    await new Promise((r) => setTimeout(r, 0))
    const upload = wrapper.findComponent({ name: 'el-upload' })
    upload.vm.$props.httpRequest({ file: { name: 'EasyChat.2.0.0.exe' } })
    expect(wrapper.vm.formData.fileName).toBe('EasyChat.2.0.0.exe')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.file-name').text()).toBe('EasyChat.2.0.0.exe')
  })

  it('更新内容行：新增 / 删除与按钮显隐', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit()
    await new Promise((r) => setTimeout(r, 0))
    // 第一行：只有添加按钮
    let rows = wrapper.findAll('.update-desc-item')
    expect(rows[0].find('.icon-add').exists()).toBe(true)
    expect(rows[0].find('.btn-del').exists()).toBe(false)
    // 新增一行
    await rows[0].find('.icon-add').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    rows = wrapper.findAll('.update-desc-item')
    expect(rows).toHaveLength(2)
    expect(rows[1].find('.btn-del').exists()).toBe(true)
    expect(rows[1].find('.icon-add').exists()).toBe(false)
    // 删除第二行
    await rows[1].find('.btn-del').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.findAll('.update-desc-item')).toHaveLength(1)
    expect(wrapper.vm.formData.updateDescList).toEqual([{ title: '' }])
  })

  it('空表单提交 → 校验不通过且不发请求', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit()
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.dialog-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.length).toBe(0)
    expect(wrapper.vm.dialogConfig.show).toBe(true)
  })

  it('版本号非法 → 校验不通过', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({
      version: 'v1.0-beta',
      fileType: 1,
      outerLink: 'https://x.com',
      updateDescArray: ['x']
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.dialog-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.length).toBe(0)
  })

  it('提交成功 → updateDesc 用 | 连接且不含 updateDescList，随后关闭并发 reload', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({
      id: 3,
      version: '1.5.0',
      fileType: 1,
      outerLink: 'https://dl.example.com/app',
      updateDescArray: ['修问题', '优化']
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.dialog-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__).toHaveLength(1)
    const params = request.__calls__[0].params
    expect(params.updateDesc).toBe('修问题|优化')
    expect('updateDescList' in params).toBe(false)
    expect(params.version).toBe('1.5.0')
    expect(params.outerLink).toBe('https://dl.example.com/app')
    expect(wrapper.vm.dialogConfig.show).toBe(false)
    expect(wrapper.emitted('reload')).toBeTruthy()
  })

  it('rules 四个字段与文案（updateType 无对应输入项，已按缺陷修复删除）', async () => {
    const wrapper = mountDialog()
    wrapper.vm.showEdit({ fileType: 1 })
    await new Promise((r) => setTimeout(r, 0))
    const rules = wrapper.findComponent({ name: 'el-form' }).props('rules')
    expect(Object.keys(rules)).toEqual(['version', 'fileType', 'fileName', 'outerLink'])
    expect(rules.version[0].message).toBe('请输入版本号')
    expect(rules.version[1].message).toBe('版本号只能是数字和点')
    expect(rules.fileType[0].message).toBe('请选择文件类型')
    expect(rules.outerLink[0].message).toBe('请输入外链地址')
  })
})
