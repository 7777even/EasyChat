import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import UserInfoEdit from '@/views/setting/UserInfoEdit.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { useAvatarInfoStore } from '@/stores/AvatarUpdateStore'

/**
 * 编辑个人信息表单的**真实挂载**测试（DOM 级，真实 pinia store）。
 *
 * 被测点：
 *   1. props.data → formData 回填（avatarFile=userId、area 拆成 {areaCode,areaName} 数组）
 *   2. 保存 → FormData 串化：areaName/areaCode 用逗号拼接、非 File 头像不上传
 *   3. 失败 → 不提示、不置强制刷新、不 emit
 *   4. 成功 → 提示 + userInfoStore.setInfo(result.data) + 头像强制刷新置 true + emit editBack
 *   5. 头像文件（File 实例）→ append avatarFile / avatarCover
 *   6. 取消 → emit editBack
 *
 * 注：formData 是 computed 且直接改 props.data 引用（既有实现），本测以真实行为断言。
 */

const MessageStub = { success: vi.fn(), warning: vi.fn() }

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

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
  props: ['modelValue', 'placeholder', 'maxlength', 'type', 'rows', 'clearable', 'showWordLimit', 'resize'],
  emits: ['update:modelValue'],
  template:
    '<input class="el-input-native" :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'link'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}
const ElSwitchStub = {
  name: 'ElSwitch',
  props: ['modelValue', 'activeValue', 'inactiveValue'],
  emits: ['update:modelValue', 'change'],
  template: '<button class="switch-stub" @click="$emit(\'update:modelValue\', modelValue ? inactiveValue : activeValue); $emit(\'change\', modelValue ? inactiveValue : activeValue)" />'
}
// 单选组：真实 el-radio 经 group 间接更新 v-model，桩须 provide/inject 传递
const RadioGroupProvide = Symbol('radioGroup')
const ElRadioGroupStub = {
  name: 'ElRadioGroup',
  props: ['modelValue'],
  emits: ['update:modelValue', 'change'],
  setup (props, { emit, slots }) {
    return () => null
  },
  template: '<div class="radio-group-stub"><slot /></div>'
}

const AvatarUploadStub = {
  name: 'AvatarUpload',
  props: ['modelValue'],
  emits: ['update:modelValue', 'coverFile'],
  setup (_, { emit, expose }) {
    const setFile = (f) => {
      emit('update:modelValue', f)
    }
    expose({ setFile })
    return { setFile }
  },
  template: '<div class="avatar-upload-stub" />'
}
const AreaSelectStub = {
  name: 'AreaSelect',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<div class="area-select-stub" />'
}

const BASE_DATA = {
  userId: 'U001',
  nickName: '小明',
  sex: 1,
  joinType: 1,
  personalSignature: '你好',
  areaCode: '110000,110100',
  areaName: '北京市,东城区'
}

async function mountEdit (handler, data = { ...BASE_DATA }) {
  formState.valid = true
  const pinia = createPinia()
  setActivePinia(pinia)
  const request = makeRequest(handler)
  const userInfo = useUserInfoStore()
  userInfo.setInfo({ userId: 'U001', nickName: '小明' })
  const wrapper = mount(UserInfoEdit, {
    props: { data },
    global: {
      plugins: [pinia],
      stubs: {
        'el-form': ElFormStub,
        'el-form-item': ElFormItemStub,
        'el-input': ElInputStub,
        'el-button': ElButtonStub,
        'el-switch': ElSwitchStub,
        'el-radio-group': ElRadioGroupStub,
        'el-radio': { name: 'ElRadio', props: ['label'], template: '<label class="radio-stub"><slot /></label>' },
        AvatarUpload: AvatarUploadStub,
        AreaSelect: AreaSelectStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { saveUserInfo: '/user/saveUserInfo' },
          Message: MessageStub
        }
      }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper, request, pinia }
}

beforeEach(() => {
  MessageStub.success.mockClear()
})

describe('UserInfoEdit.vue 真实挂载（DOM 级）', () => {
  it('props.data 回填：昵称/签名 + areaCode/areaName 拆为数组（不写回原始 props）', async () => {
    const data = { ...BASE_DATA }
    const { wrapper } = await mountEdit(null, data)
    const nick = wrapper.findAll('.el-input-native')[0]
    expect(nick.element.value).toBe('小明')
    const sig = wrapper.findAll('.el-input-native').find((i) => i.element.value === '你好')
    expect(sig).toBeTruthy()
    expect(data.areaCode).toBe('110000,110100')
  })

  it('保存 → FormData：areaName/areaCode 逗号拼接、nickName 透传、非 File 头像不上传', async () => {
    const { wrapper, request } = await mountEdit((opts) => ({
      code: 0,
      data: { userId: 'U001', nickName: '改后昵称' }
    }))
    await wrapper.findAll('.el-input-native')[0].setValue('改后昵称')
    await wrapper.findAll('.el-button')[0].trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    const call = request.__calls__[0]
    expect(call.url).toBe('/user/saveUserInfo')
    const fd = call.params
    expect(fd.get('nickName')).toBe('改后昵称')
    // 代码先 append 空串占位、再 append 真值（既有实现）→ get() 取首项为空，须用 getAll 断真值
    expect(fd.getAll('areaCode')).toContain('110000,110100')
    expect(fd.getAll('areaName')).toContain('北京市,东城区')
    // avatarFile 初始值是 userId 字符串而非 File → 不应 append
    expect(fd.get('avatarFile')).toBeNull()
    expect(fd.get('avatarCover')).toBeNull()
  })

  it('成功 → 提示 + userInfoStore.setInfo(result.data) + 头像强制刷新 + emit editBack', async () => {
    const { wrapper, request, pinia } = await mountEdit((opts) => ({
      code: 0,
      data: { userId: 'U001', nickName: '新昵称' }
    }))
    const avatarStore = useAvatarInfoStore()
    avatarStore.setFoceReload('U001', false)
    await wrapper.findAll('.el-button')[0].trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(MessageStub.success).toHaveBeenCalledWith('保存成功')
    expect(useUserInfoStore().getInfo().nickName).toBe('新昵称')
    expect(avatarStore.getFoceReload('U001')).toBe(true)
    expect(wrapper.emitted('editBack')).toHaveLength(1)
    expect(request.__calls__).toHaveLength(1)
    expect(pinia).toBeTruthy()
  })

  it('失败（返回空）→ 不提示、不 emit、不强制刷新', async () => {
    const { wrapper, request, pinia } = await mountEdit(() => undefined)
    const avatarStore = useAvatarInfoStore()
    avatarStore.setFoceReload('U001', false)
    await wrapper.findAll('.el-button')[0].trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(wrapper.emitted('editBack')).toBeUndefined()
    expect(avatarStore.getFoceReload('U001')).toBe(false)
    expect(request.__calls__).toHaveLength(1)
    expect(pinia).toBeTruthy()
  })

  it('头像 File 实例 → append avatarFile（且 avatarCover 一并提交）', async () => {
    const { wrapper, request } = await mountEdit((opts) => ({ code: 0, data: { userId: 'U001' } }))
    // 经桩的 expose 直接触发 saveCover 分支（coverFile 事件）
    const upload = wrapper.findComponent({ name: 'AvatarUpload' })
    const file = new File(['x'], 'a.png', { type: 'image/png' })
    const cover = new File(['y'], 'a-cover.png', { type: 'image/png' })
    upload.vm.$emit('coverFile', { avatarFile: file, coverFile: cover })
    await wrapper.vm.$nextTick()
    await wrapper.findAll('.el-button')[0].trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    const fd = request.__calls__[0].params
    expect(fd.get('avatarFile').name).toBe('a.png')
    expect(fd.get('avatarCover').name).toBe('a-cover.png')
  })

  it('校验不通过 → 不发请求、不提示', async () => {
    const { wrapper, request } = await mountEdit()
    formState.valid = false
    await wrapper.findAll('.el-button')[0].trigger('click')
    await wrapper.vm.$nextTick()
    expect(request.__calls__).toHaveLength(0)
    expect(MessageStub.success).not.toHaveBeenCalled()
  })

  it('取消 → emit editBack、不发请求', async () => {
    const { wrapper, request } = await mountEdit()
    await wrapper.findAll('.el-button')[1].trigger('click')
    expect(wrapper.emitted('editBack')).toHaveLength(1)
    expect(request.__calls__).toHaveLength(0)
  })
})
