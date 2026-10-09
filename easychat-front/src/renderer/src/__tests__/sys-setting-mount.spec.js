import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import SysSetting from '@/views/admin/SysSetting.vue'

/**
 * 管理端系统设置的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 挂载即拉取当前设置（顶层调用 getSysSetting，非 onMounted）
 *   2. 回填时 robotFile 取自 robotUid（字段名映射）
 *   3. rules 结构与文案（8 字段、5 个数字校验）
 *   4. saveCover → robotFile + robotCover 双写
 *   5. saveSysSetting：校验不通过不发请求；通过则带全量参数保存
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
        for (const r of fieldRules) {
          if (value === undefined || value === null || value === '') {
            if (r.required) {
              errors.push({ field, message: r.message })
              break
            }
            continue
          }
          if (r.validator && !r.validator(value)) {
            errors.push({ field, message: r.message })
            break
          }
        }
      }
      cb(errors.length === 0, errors)
    }
  },
  template: '<form><slot /></form>'
}

const stubs = {
  'el-form': elFormStub,
  'el-form-item': { name: 'el-form-item', props: ['label', 'prop'], template: '<div class="el-form-item"><slot /></div>' },
  'el-input': {
    name: 'el-input',
    props: ['modelValue', 'clearable', 'placeholder', 'maxlength', 'rows', 'showWordLimit', 'type', 'resize'],
    emits: ['update:modelValue'],
    template: `<div class="el-input">
      <input :value="modelValue" @input="$emit('update:modelValue', $event.target.value.trim())" />
      <span class="append-slot"><slot name="append" /></span>
    </div>`
  },
  'el-button': {
    name: 'el-button',
    props: ['type'],
    emits: ['click'],
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`
  },
  AvatarUpload: {
    name: 'AvatarUpload',
    props: ['modelValue'],
    emits: ['update:modelValue', 'coverFile'],
    template: '<div class="avatar-upload" />'
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

function mountPage(handler) {
  request = makeRequest(handler)
  const wrapper = mount(SysSetting, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { getSysSetting4Admin: '/admin/getSysSetting4Admin', saveSysSetting: '/admin/saveSysSetting' },
          Message: { success: vi.fn(), warning: vi.fn() },
          Verify: {
            number: (v) => /^\d+$/.test(v)
          }
        }
      }
    }
  })
  return wrapper
}

function settingData() {
  return {
    code: 0,
    data: {
      maxGroupCount: 5,
      maxGroupMemberCount: 200,
      maxImageSize: 10,
      maxVideoSize: 50,
      maxFileSize: 100,
      robotNickName: '小助手',
      robotUid: 'Urobot',
      robotWelcome: '欢迎使用',
      robotCover: 'cover-old'
    }
  }
}

describe('SysSetting.vue 真实挂载（DOM 级）', () => {
  it('挂载即拉取设置（setup 顶层调用）', () => {
    mountPage(() => settingData())
    expect(request.__calls__).toHaveLength(1)
    expect(request.__calls__[0].url).toBe('/admin/getSysSetting4Admin')
  })

  it('表单回填 + robotFile 映射自 robotUid', async () => {
    const wrapper = mountPage(() => settingData())
    await new Promise((r) => setTimeout(r, 0))
    const form = wrapper.findComponent({ name: 'el-form' }).props('model')
    expect(form.maxGroupCount).toBe(5)
    expect(form.maxGroupMemberCount).toBe(200)
    expect(form.robotNickName).toBe('小助手')
    // robotFile 单独取自 robotUid，而非透传 robotFile 字段
    expect(form.robotFile).toBe('Urobot')
  })

  it('8 个配置项 + 保存按钮 + 3 个 MB 单位插槽', () => {
    const wrapper = mountPage(() => settingData())
    // 8 个字段项 + 1 个按钮项
    expect(wrapper.findAll('.el-form-item')).toHaveLength(9)
    expect(wrapper.findComponent({ name: 'AvatarUpload' }).exists()).toBe(true)
    const appends = wrapper.findAll('.el-input .append-slot').filter((s) => s.text() === 'MB')
    expect(appends).toHaveLength(3)
    expect(wrapper.findAll('.el-button').map((b) => b.text())).toEqual(['保存设置'])
  })

  it('rules：8 字段 + 5 个数字校验 + 文案与字段对应', () => {
    const wrapper = mountPage(() => settingData())
    const rules = wrapper.findComponent({ name: 'el-form' }).props('rules')
    expect(Object.keys(rules)).toHaveLength(8)
    const numberFields = Object.entries(rules)
      .filter(([, rs]) => rs.some((r) => r.message === '只能是数字'))
      .map(([f]) => f)
    expect(numberFields).toEqual([
      'maxGroupCount',
      'maxGroupMemberCount',
      'maxImageSize',
      'maxVideoSize',
      'maxFileSize'
    ])
    // 必填文案与字段语义对应
    expect(rules.robotNickName[0].message).toBe('请输入机器人昵称')
    expect(rules.robotFile[0].message).toBe('请选择机器人头像')
    expect(rules.robotWelcome[0].message).toBe('请输入新用户注册机器人欢迎消息')
  })

  it('saveCover → robotFile + robotCover 双写', async () => {
    const wrapper = mountPage(() => settingData())
    await new Promise((r) => setTimeout(r, 0))
    const upload = wrapper.findComponent({ name: 'AvatarUpload' })
    await upload.vm.$emit('coverFile', { avatarFile: 'U_new', coverFile: 'cover-new' })
    const form = wrapper.findComponent({ name: 'el-form' }).props('model')
    expect(form.robotFile).toBe('U_new')
    expect(form.robotCover).toBe('cover-new')
  })

  it('保存 → 校验不通过则不发请求', async () => {
    // 空表单：required 全部不满足
    const wrapper = mountPage(() => ({ code: 0, data: {} }))
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.el-button').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.filter((c) => c.url === '/admin/saveSysSetting')).toHaveLength(0)
  })

  it('数字字段非数字 → 校验失败（不发请求）', async () => {
    const wrapper = mountPage(() => ({ code: 0, data: { ...settingData().data, maxGroupCount: 'abc' } }))
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.el-button').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.filter((c) => c.url === '/admin/saveSysSetting')).toHaveLength(0)
  })

  it('保存成功 → 带全量参数 + 成功提示', async () => {
    const wrapper = mountPage(() => settingData())
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.el-button').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const save = request.__calls__.find((c) => c.url === '/admin/saveSysSetting')
    expect(save).toBeTruthy()
    // params 含全部配置字段（含上传映射后的 robotFile）
    expect(save.params).toMatchObject({
      maxGroupCount: 5,
      maxGroupMemberCount: 200,
      maxImageSize: 10,
      maxVideoSize: 50,
      maxFileSize: 100,
      robotNickName: '小助手',
      robotUid: 'Urobot',
      robotFile: 'Urobot',
      robotWelcome: '欢迎使用'
    })
    const Message = wrapper.vm.$.appContext.config.globalProperties.Message
    expect(Message.success).toHaveBeenCalledWith('保存成功')
  })
})
