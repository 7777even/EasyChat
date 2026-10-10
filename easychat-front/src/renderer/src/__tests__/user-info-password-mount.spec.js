import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import UserInfoPassword from '@/views/setting/UserInfoPassword.vue'

/**
 * 修改密码表单的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 校验通过 → Confirm 二次确认 → updatePassword 透传三个字段
 *   2. 成功提示回调里 send('reLogin')（改密后强制重新登录）
 *   3. 失败（返回空）→ 不提示成功、不 reLogin
 *   4. 取消 → emit('editBack')（不发请求）
 *   5. 校验不通过（stub 判 invalid）→ 不弹 Confirm（守卫在 validate 回调内）
 */

const MessageStub = { success: vi.fn(), warning: vi.fn() }
const ConfirmStub = vi.fn()

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: 1 }
  })
  fn.__calls__ = calls
  return fn
}

// el-form 桩：真实 async-validator 不进本测（那是 Element 内部），
// 只验证「valid 分支走向」——故 valid 可控。
const formState = { valid: true }
const ElFormStub = {
  name: 'ElForm',
  props: ['model', 'rules', 'ref', 'labelWidth'],
  setup (_, { expose }) {
    expose({ validate: (cb) => cb(formState.valid) })
    return {}
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
  props: ['modelValue', 'placeholder', 'type', 'clearable', 'showPassword'],
  emits: ['update:modelValue'],
  template:
    '<input class="el-input-native" :type="type" :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'link'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}

async function mountPassword (handler) {
  formState.valid = true
  const request = makeRequest(handler)
  const wrapper = mount(UserInfoPassword, {
    global: {
      stubs: {
        'el-form': ElFormStub,
        'el-form-item': ElFormItemStub,
        'el-input': ElInputStub,
        'el-button': ElButtonStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { updatePassword: '/user/updatePassword' },
          Message: MessageStub,
          Confirm: ConfirmStub,
          // rules 构造期即取 proxy.Verify.password，缺它 setup 直接抛错
          Verify: { password: (rule, value, cb) => cb() }
        }
      }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper, request }
}

async function fillForm (wrapper) {
  const inputs = wrapper.findAll('.el-input-native')
  await inputs[0].setValue('old12345')
  await inputs[1].setValue('new67890')
  await inputs[2].setValue('new67890')
}

beforeEach(() => {
  MessageStub.success.mockClear()
  ConfirmStub.mockClear()
  window.ipcRenderer.__reset__
})

describe('UserInfoPassword.vue 真实挂载（DOM 级）', () => {
  it('渲染三个密码项与两个按钮', async () => {
    const { wrapper } = await mountPassword()
    expect(wrapper.findAll('.el-input-native')).toHaveLength(3)
    const btns = wrapper.findAll('.el-button').map((b) => b.text())
    expect(btns).toEqual(['修改密码', '取消'])
  })

  it('校验通过 → Confirm 二次确认 → updatePassword 透传三字段', async () => {
    const { wrapper, request } = await mountPassword()
    await fillForm(wrapper)
    await wrapper.findAll('.el-button')[0].trigger('click')
    expect(ConfirmStub).toHaveBeenCalledTimes(1)
    expect(ConfirmStub.mock.calls[0][0].message).toContain('修改密码后将退出登录')
    await ConfirmStub.mock.calls[0][0].okfun()
    await new Promise((r) => setTimeout(r, 0))

    const call = request.__calls__[0]
    expect(call.url).toBe('/user/updatePassword')
    expect(call.params).toEqual({ oldPassword: 'old12345', password: 'new67890', rePassword: 'new67890' })
  })

  it('成功 → 提示成功且回调内 send reLogin', async () => {
    const { wrapper } = await mountPassword(() => ({ code: 0, data: 1 }))
    await fillForm(wrapper)
    await wrapper.findAll('.el-button')[0].trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await new Promise((r) => setTimeout(r, 0))

    expect(MessageStub.success).toHaveBeenCalledTimes(1)
    expect(MessageStub.success.mock.calls[0][0]).toBe('修改成功请重新登录')
    // 第二实参是回调：手动触发以断言 reLogin 在成功回调内发出
    MessageStub.success.mock.calls[0][1]()
    expect(window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'reLogin')).toBeTruthy()
  })

  it('失败（返回空）→ 不提示成功、不 reLogin', async () => {
    const { wrapper } = await mountPassword(() => undefined)
    await fillForm(wrapper)
    await wrapper.findAll('.el-button')[0].trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await new Promise((r) => setTimeout(r, 0))

    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(window.ipcRenderer.__calls__.find((c) => c.args[0] === 'reLogin')).toBeUndefined()
  })

  it('校验不通过 → 不弹 Confirm、不发请求', async () => {
    const { wrapper, request } = await mountPassword()
    formState.valid = false
    await fillForm(wrapper)
    await wrapper.findAll('.el-button')[0].trigger('click')
    await wrapper.vm.$nextTick()
    expect(ConfirmStub).not.toHaveBeenCalled()
    expect(request.__calls__).toHaveLength(0)
  })

  it('取消 → emit editBack、不发请求', async () => {
    const { wrapper, request } = await mountPassword()
    await wrapper.findAll('.el-button')[1].trigger('click')
    expect(wrapper.emitted('editBack')).toHaveLength(1)
    expect(request.__calls__).toHaveLength(0)
  })
})
