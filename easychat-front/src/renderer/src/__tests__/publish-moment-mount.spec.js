import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import PublishMoment from '@/views/moment/PublishMoment.vue'

/**
 * 发表朋友圈弹窗的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. show() → 开窗 + 表单复位 + 拉好友昵称映射 + 拉用户级默认可见范围
 *   2. 默认可见范围继承：momentVisibility=null → 0；名单 JSON 解析（含非法 JSON 兜底）
 *   3. 发表守卫：无内容无媒体 → warning；白/黑名单未选人 → warning；上传中 → warning
 *   4. 白名单模式 → visibleList 提交且 invisibleList=null（反之亦然）
 *   5. 无媒体发表成功 → success + 关窗 + emit refresh
 *   6. 有媒体 → 走分片上传；上传中关窗 → Confirm 二次确认
 *   7. 选文件守卫：超 9 个 / 非图片视频 / 视频超 100MB
 */

const uploadMedia = vi.fn()
vi.mock('@/utils/MomentChunkUploadApi', () => ({
  default: { uploadMedia: (...args) => uploadMedia(...args) }
}))

const MessageStub = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
const ConfirmStub = vi.fn()

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const DialogStub = {
  name: 'Dialog',
  props: ['show', 'title', 'buttons', 'width', 'showCancel'],
  emits: ['close'],
  template:
    '<div class="dialog-stub" v-if="show"><div class="d-title">{{ title }}</div><div class="d-body"><slot /></div>' +
    '<div class="d-actions"><button v-for="(b, i) in buttons" :key="i" class="d-btn" @click="b.click()">{{ b.text }}</button>' +
    '<button class="d-close" @click="$emit(\'close\')">X</button></div></div>'
}
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'type', 'size', 'readonly', 'maxlength', 'autosize', 'showWordLimit'],
  emits: ['update:modelValue'],
  template:
    '<input class="el-input-native" :value="modelValue" :placeholder="placeholder" :readonly="readonly" ' +
    '@input="$emit(\'update:modelValue\', $event.target.value)" />'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'link'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}
const ElSelectStub = {
  name: 'ElSelect',
  props: ['modelValue', 'size'],
  emits: ['update:modelValue'],
  template: '<select class="select-stub" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>'
}
const ElOptionStub = {
  name: 'ElOption',
  props: ['label', 'value'],
  template: '<option class="option-stub" :value="value">{{ label }}</option>'
}
const ElProgressStub = {
  name: 'ElProgress',
  props: ['percentage', 'status'],
  template: '<div class="progress-stub" :data-percentage="percentage" :data-status="status" />'
}
const ContactPickerStub = {
  name: 'ContactPicker',
  props: ['modelValue', 'title', 'selected'],
  emits: ['update:modelValue', 'confirm'],
  template: '<div class="cp-stub" v-if="modelValue" />'
}

const FRIENDS = [
  { contactId: 'U010', contactName: '阿强' },
  { contactId: 'U020', contactName: '阿伟' }
]

const USER_INFO = {
  momentVisibility: 0,
  momentVisibleList: '',
  momentInvisibleList: ''
}

function baseHandler (info = USER_INFO) {
  return (opts) => {
    if (opts.url === '/user/getUserInfo') return { code: 0, data: info }
    if (opts.url === '/contact/load') return { code: 0, data: FRIENDS }
    if (opts.url === '/moment/publish') return { code: 0, data: { id: 555 } }
    return { code: 0, data: null }
  }
}

function makeHandler (overrides = {}, info = USER_INFO) {
  const base = baseHandler(info)
  return (opts) => (Object.prototype.hasOwnProperty.call(overrides, opts.url) ? overrides[opts.url](opts) : base(opts))
}

async function mountPublish (handler, info = USER_INFO) {
  const request = makeRequest(makeHandler(handler, info))
  const wrapper = mount(PublishMoment, {
    global: {
      stubs: {
        Dialog: DialogStub,
        ContactPicker: ContactPickerStub,
        'el-input': ElInputStub,
        'el-button': ElButtonStub,
        'el-select': ElSelectStub,
        'el-option': ElOptionStub,
        'el-progress': ElProgressStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { getUserInfo: '/user/getUserInfo', loadContact: '/contact/load', publishMoment: '/moment/publish' },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper, request }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

/** 往隐藏 file input 塞 files 并派发 change */
async function selectFiles (wrapper, files) {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
}

function makeFile (name, type, size = 1024) {
  const f = new File(['x'], name, { type })
  Object.defineProperty(f, 'size', { value: size })
  return f
}

beforeEach(() => {
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
  MessageStub.error.mockClear()
  ConfirmStub.mockClear()
  uploadMedia.mockReset()
})

describe('PublishMoment.vue 真实挂载（DOM 级）', () => {
  it('初始关窗；show() 开窗 + 拉好友昵称 + 拉用户级默认', async () => {
    const { wrapper, request } = await mountPublish()
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    wrapper.vm.show()
    await flush()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.find('.d-title').text()).toBe('发表朋友圈')
    const urls = request.__calls__.map((c) => c.url)
    expect(urls).toContain('/contact/load')
    expect(urls).toContain('/user/getUserInfo')
  })

  it('可见范围选项 5 项（公开…黑名单）', async () => {
    const { wrapper } = await mountPublish()
    wrapper.vm.show()
    await flush()
    const opts = wrapper.findAll('.option-stub').map((o) => o.text())
    expect(opts).toEqual(['公开', '好友可见', '仅自己可见', '自定义（白名单）', '黑名单'])
  })

  it('继承用户级可见范围：momentVisibility=3 + 白名单 → 选人行渲染且回显昵称', async () => {
    const { wrapper } = await mountPublish(
      {},
      { ...USER_INFO, momentVisibility: 3, momentVisibleList: JSON.stringify(['U010', 'U020']) }
    )
    wrapper.vm.show()
    await flush()
    const pick = wrapper.findAll('.visibility-pick')
    expect(pick).toHaveLength(1)
    expect(pick[0].find('.count-hint').text()).toBe('已选 2 人')
    // 白名单昵称映射来自 loadContact
    const readonlyInput = pick[0].find('.el-input-native')
    expect(readonlyInput.element.value).toBe('阿强、阿伟')
  })

  it('继承可见范围=黑名单(4) → 渲染「不让谁看」行', async () => {
    const { wrapper } = await mountPublish(
      {},
      { ...USER_INFO, momentVisibility: 4, momentInvisibleList: JSON.stringify(['U010']) }
    )
    wrapper.vm.show()
    await flush()
    expect(wrapper.findAll('.visibility-pick')).toHaveLength(1)
    expect(wrapper.find('.visibility-pick').find('.label').text()).toBe('不让谁看')
  })

  it('名单字段非法 JSON → 兜底空数组（不抛错）', async () => {
    const { wrapper } = await mountPublish({}, { ...USER_INFO, momentVisibility: 3, momentVisibleList: '{bad' })
    wrapper.vm.show()
    await flush()
    expect(wrapper.find('.visibility-pick').find('.count-hint').text()).toBe('已选 0 人')
  })

  it('momentVisibility=null → 默认公开(0)、不渲染选人行', async () => {
    const { wrapper } = await mountPublish({}, { ...USER_INFO, momentVisibility: null })
    wrapper.vm.show()
    await flush()
    expect(wrapper.findAll('.visibility-pick')).toHaveLength(0)
  })

  it('发表：空内容且无媒体 → warning、不发请求', async () => {
    const { wrapper, request } = await mountPublish()
    wrapper.vm.show()
    await flush()
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('请输入内容或选择图片/视频')
    expect(request.__calls__.find((c) => c.url === '/moment/publish')).toBeUndefined()
  })

  it('发表：白名单模式未选人 → warning「请先选择哪些人可以看到」、不发请求', async () => {
    const { wrapper, request } = await mountPublish({}, { ...USER_INFO, momentVisibility: 3 })
    wrapper.vm.show()
    await flush()
    await wrapper.find('.el-input-native').setValue('内容')
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('请先选择哪些人可以看到')
    expect(request.__calls__.find((c) => c.url === '/moment/publish')).toBeUndefined()
  })

  it('发表：黑名单模式未选人 → warning「请先选择不让谁看到」', async () => {
    const { wrapper } = await mountPublish({}, { ...USER_INFO, momentVisibility: 4 })
    wrapper.vm.show()
    await flush()
    await wrapper.find('.el-input-native').setValue('内容')
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('请先选择不让谁看到')
  })

  it('发表：白名单模式提交 visibleList 且 invisibleList=null', async () => {
    const { wrapper, request } = await mountPublish(
      {},
      { ...USER_INFO, momentVisibility: 3, momentVisibleList: JSON.stringify(['U010']) }
    )
    wrapper.vm.show()
    await flush()
    await wrapper.find('.el-input-native').setValue('今天天气不错')
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    const call = request.__calls__.find((c) => c.url === '/moment/publish')
    expect(call.params.content).toBe('今天天气不错')
    expect(call.params.visibility).toBe(3)
    expect(call.params.visibleList).toBe(JSON.stringify(['U010']))
    expect(call.params.invisibleList).toBeNull()
    expect(MessageStub.success).toHaveBeenCalledWith('发表成功')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
  })

  it('发表：黑名单模式提交 invisibleList 且 visibleList=null', async () => {
    const { wrapper, request } = await mountPublish(
      {},
      { ...USER_INFO, momentVisibility: 4, momentInvisibleList: JSON.stringify(['U020']) }
    )
    wrapper.vm.show()
    await flush()
    await wrapper.find('.el-input-native').setValue('内容')
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    const call = request.__calls__.find((c) => c.url === '/moment/publish')
    expect(call.params.invisibleList).toBe(JSON.stringify(['U020']))
    expect(call.params.visibleList).toBeNull()
  })

  it('发表失败（返回空）→ 不提示成功、不关窗', async () => {
    const { wrapper } = await mountPublish({ '/moment/publish': () => undefined })
    wrapper.vm.show()
    await flush()
    await wrapper.find('.el-input-native').setValue('内容')
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
  })

  it('选文件：超过 9 个 → warning 且不入列表', async () => {
    const { wrapper } = await mountPublish()
    wrapper.vm.show()
    await flush()
    const files = Array.from({ length: 10 }, (_, i) => makeFile(`a${i}.png`, 'image/png'))
    await selectFiles(wrapper, files)
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('最多只能选择9张图片/视频')
    expect(wrapper.findAll('.media-item')).toHaveLength(0)
  })

  it('选文件：非图片/视频 → warning 跳过', async () => {
    const { wrapper } = await mountPublish()
    wrapper.vm.show()
    await flush()
    await selectFiles(wrapper, [makeFile('a.pdf', 'application/pdf')])
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('只支持图片和视频格式')
    expect(wrapper.findAll('.media-item')).toHaveLength(0)
  })

  it('选文件：视频超 100MB → warning 跳过', async () => {
    const { wrapper } = await mountPublish()
    wrapper.vm.show()
    await flush()
    await selectFiles(wrapper, [makeFile('big.mp4', 'video/mp4', 101 * 1024 * 1024)])
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('视频大小不能超过100MB')
    expect(wrapper.findAll('.media-item')).toHaveLength(0)
  })

  it('选文件：合法图片 → 生成预览项；点 × 移除', async () => {
    const { wrapper } = await mountPublish()
    wrapper.vm.show()
    await flush()
    await selectFiles(wrapper, [makeFile('a.png', 'image/png')])
    await vi.waitFor(() => expect(wrapper.findAll('.media-item')).toHaveLength(1))
    expect(wrapper.find('.media-item img').attributes('src')).toMatch(/^data:/)
    // 未满 9 张 → 追加按钮常在
    expect(wrapper.find('.add-media').exists()).toBe(true)
    await wrapper.find('.media-item .icon-close').trigger('click')
    expect(wrapper.findAll('.media-item')).toHaveLength(0)
    // 清空后回到「照片/视频」入口
    expect(wrapper.find('.media-actions').exists()).toBe(true)
  })

  it('有媒体发表 → 走分片上传（带 momentId 与 mediaType），上传中关窗 → Confirm', async () => {
    let resolveUpload
    uploadMedia.mockImplementation(() => new Promise((r) => { resolveUpload = r }))
    const { wrapper, request } = await mountPublish()
    wrapper.vm.show()
    await flush()
    await selectFiles(wrapper, [makeFile('a.png', 'image/png')])
    await vi.waitFor(() => expect(wrapper.findAll('.media-item')).toHaveLength(1))
    await wrapper.find('.el-input-native').setValue('带图')
    await wrapper.find('.d-btn').trigger('click')
    await flush()

    const call = request.__calls__.find((c) => c.url === '/moment/publish')
    expect(call).toBeTruthy()
    expect(uploadMedia).toHaveBeenCalledTimes(1)
    // (file, momentId, mediaType=0 图片, callbacks)
    expect(uploadMedia.mock.calls[0][1]).toBe(555)
    expect(uploadMedia.mock.calls[0][2]).toBe(0)
    // 上传中：进度面板 + 关窗需二次确认
    expect(wrapper.find('.uploading-panel').exists()).toBe(true)
    expect(wrapper.find('.upload-item .file-name').text()).toBe('a.png')
    await wrapper.find('.d-close').trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toBe('文件正在上传中，确定要关闭吗？')
    expect(wrapper.find('.dialog-stub').exists()).toBe(true) // 未确认前不关

    // 上传完成 → success（延迟 1s 关窗）
    resolveUpload({ fileId: 'F1' })
    await flush()
    expect(MessageStub.success).toHaveBeenCalledWith('发表成功')
  }, 10000)

  it('上传失败 → error 提示 + 进度面板保留（可重试）', async () => {
    uploadMedia.mockImplementation(async () => { throw new Error('断网') })
    const { wrapper } = await mountPublish()
    wrapper.vm.show()
    await flush()
    await selectFiles(wrapper, [makeFile('a.png', 'image/png')])
    await vi.waitFor(() => expect(wrapper.findAll('.media-item')).toHaveLength(1))
    await wrapper.find('.el-input-native').setValue('带图')
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    expect(MessageStub.error).toHaveBeenCalledWith('部分文件上传失败，请重试')
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
  })

  it('上传中再点发表 → warning「文件正在上传中，请稍候...」、不重复发请求', async () => {
    uploadMedia.mockImplementation(() => new Promise(() => {}))
    const { wrapper, request } = await mountPublish()
    wrapper.vm.show()
    await flush()
    await selectFiles(wrapper, [makeFile('a.png', 'image/png')])
    await vi.waitFor(() => expect(wrapper.findAll('.media-item')).toHaveLength(1))
    await wrapper.find('.el-input-native').setValue('带图')
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    const before = request.__calls__.filter((c) => c.url === '/moment/publish').length
    await wrapper.find('.d-btn').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('文件正在上传中，请稍候...')
    expect(request.__calls__.filter((c) => c.url === '/moment/publish')).toHaveLength(before)
  })
})
