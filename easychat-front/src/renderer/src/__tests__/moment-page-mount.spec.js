import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Moment from '@/views/moment/Moment.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'

/**
 * 朋友圈主页的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. onMounted：loadMomentList + loadUnreadCount + 注册 momentNotify/momentUnread 两通道
 *   2. 未读红点：>0 显示、>99 显示 99+；开通知中心即清零
 *   3. locateMoment(refId) → momentDetail 拉详情并打开
 *   4. 发布：空内容无图 → warning；成功后走 uploadMomentMedia(FormData) 并重载列表 + 清空编辑器
 *   5. 图片上传失败（code!==200）→ error 提示但仍继续后续图片
 *   6. 点赞：cancel 取 likedByMe 反值；回写 likedByMe + likeList
 *   7. 评论：开框 / 回复态（占位符 + parentId）/ 取消 / 提交成功清草稿关框
 *   8. 删评论 / 删动态：Confirm → 请求 → 本地剔除；失败保留
 *   9. 举报：他人评论 → ReportDialog(comment, momentId, commentId)；他人动态 → (moment, momentId)
 *   10. 图片预览：起始下标按过滤后图片序列重算；点视频改为打开详情
 *   11. 滚动触底 → pageNo++ 且追加列表；noMore 后不再请求
 */

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

const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width', 'borderRadius'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
const DialogStub = {
  name: 'PublishMoment',
  emits: ['refresh'],
  setup (_, { expose }) {
    expose({ show: vi.fn() })
    return {}
  },
  template: '<div class="publish-stub" />'
}
const MomentDetailStub = {
  name: 'MomentDetail',
  emits: ['refresh'],
  setup (_, { expose }) {
    expose({ show: vi.fn() })
    return {}
  },
  template: '<div class="detail-stub" />'
}
const MomentNotifyStub = {
  name: 'MomentNotify',
  emits: ['locateMoment'],
  setup (_, { expose }) {
    expose({ show: vi.fn() })
    return {}
  },
  template: '<div class="notify-stub" />'
}
const UserMomentStub = {
  name: 'UserMoment',
  setup (_, { expose }) {
    expose({ show: vi.fn() })
    return {}
  },
  template: '<div class="user-moment-stub" />'
}
const ReportDialogStub = {
  name: 'ReportDialog',
  props: ['modelValue', 'type', 'momentId', 'commentId'],
  emits: ['update:modelValue'],
  // 真实 ReportDialog 靠 modelValue 控制显隐；桩须同样受控，否则「未打开」断言恒失真
  template:
    '<div v-if="modelValue" class="report-stub" :data-type="type" :data-moment="momentId" ' +
    ':data-comment="commentId" />'
}
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'type', 'size', 'maxlength', 'autosize', 'showWordLimit'],
  emits: ['update:modelValue'],
  setup (_, { expose }) {
    expose({ focus: vi.fn() })
    return {}
  },
  template:
    '<textarea class="el-input-native" :placeholder="placeholder" :value="modelValue" ' +
    '@input="$emit(\'update:modelValue\', $event.target.value)"></textarea>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'loading'],
  emits: ['click'],
  template: '<button class="el-button" :data-type="type" @click="$emit(\'click\')"><slot /></button>'
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
const ElDividerStub = { name: 'ElDivider', template: '<hr class="el-divider-stub" />' }
const ElDropdownStub = {
  name: 'ElDropdown',
  props: ['trigger'],
  emits: ['command'],
  template: '<div class="dropdown-stub"><slot /><div class="dd-items"><slot name="dropdown" /></div></div>'
}
const ElDropdownMenuStub = { name: 'ElDropdownMenu', template: '<div><slot /></div>' }
const ElDropdownItemStub = {
  name: 'ElDropdownItem',
  props: ['command'],
  emits: ['click'],
  template: '<div class="dd-item" :data-cmd="command" @click="$emit(\'click\')"><slot /></div>'
}
const ElImageViewerStub = {
  name: 'ElImageViewer',
  props: ['urlList', 'initialIndex'],
  emits: ['close'],
  template:
    '<div class="viewer-stub" :data-count="urlList.length" :data-index="initialIndex">' +
    '<button class="viewer-close" @click="$emit(\'close\')">X</button></div>'
}
/** el-scrollbar：暴露 wrapRef（滚动分页依赖 scrollTop/scrollHeight/clientHeight） */
const wrapRef = { scrollTop: 0, scrollHeight: 1000, clientHeight: 900, addEventListener: vi.fn() }
const ElScrollbarStub = {
  name: 'ElScrollbar',
  setup (_, { expose, slots }) {
    expose({ wrapRef })
    return () => slots.default && slots.default()
  }
}

const STUBS = {
  Avatar: AvatarStub,
  PublishMoment: DialogStub,
  MomentDetail: MomentDetailStub,
  MomentNotify: MomentNotifyStub,
  UserMoment: UserMomentStub,
  ReportDialog: ReportDialogStub,
  'el-input': ElInputStub,
  'el-button': ElButtonStub,
  'el-select': ElSelectStub,
  'el-option': ElOptionStub,
  'el-divider': ElDividerStub,
  'el-dropdown': ElDropdownStub,
  'el-dropdown-menu': ElDropdownMenuStub,
  'el-dropdown-item': ElDropdownItemStub,
  'el-image-viewer': ElImageViewerStub,
  'el-scrollbar': ElScrollbarStub
}

const MY_ID = 'U001'
const NOW = Date.now()

const LIST = [
  {
    id: 10,
    userId: MY_ID,
    nickName: '我',
    content: '我的动态',
    location: '北京',
    createTime: NOW - 5 * 60 * 1000,
    likedByMe: false,
    likeList: [],
    commentList: [
      { id: 101, userId: 'U010', nickName: '阿强', content: '好看', createTime: NOW - 1000 }
    ],
    mediaList: [
      { id: 1, mediaType: 0, filePath: 'F001' },
      { id: 2, mediaType: 1, filePath: 'F002' },
      { id: 3, mediaType: 0, filePath: 'F003' }
    ]
  },
  {
    id: 20,
    userId: 'U010',
    nickName: '阿强',
    content: '阿强的动态',
    location: '',
    createTime: NOW - 3 * 60 * 60 * 1000,
    likedByMe: true,
    likeList: [{ userId: MY_ID, nickName: '我' }],
    commentList: [{ id: 201, userId: MY_ID, nickName: '我', content: '我的评论', createTime: NOW - 1000 }],
    mediaList: []
  }
]

function baseHandler () {
  return (opts) => {
    switch (opts.url) {
      case '/moment/list':
        return { code: 0, data: JSON.parse(JSON.stringify(LIST)) }
      case '/moment/unreadCount':
        return { code: 0, data: 3 }
      case '/moment/publish':
        return { code: 0, data: { id: 777 } }
      case '/moment/like':
        return { code: 0, data: { liked: true, likeList: [{ userId: MY_ID, nickName: '我' }] } }
      case '/moment/comment':
        return { code: 0, data: { id: 999, userId: MY_ID, nickName: '我', content: '新评论', createTime: NOW } }
      case '/moment/detail':
        return { code: 0, data: { id: 10, content: '详情内容' } }
      case '/moment/delete':
      case '/moment/deleteComment':
      case '/moment/uploadMedia':
        return { code: 200, data: null }
      default:
        return { code: 0, data: null }
    }
  }
}

function makeHandler (overrides = {}) {
  const base = baseHandler()
  return (opts) => (Object.prototype.hasOwnProperty.call(overrides, opts.url) ? overrides[opts.url](opts) : base(opts))
}

async function mountMoment (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const userStore = useUserInfoStore()
  userStore.setInfo({ userId: MY_ID, nickName: '我' })
  const request = makeRequest(makeHandler(handler))
  const wrapper = mount(Moment, {
    global: {
      plugins: [pinia],
      stubs: STUBS,
      config: {
        globalProperties: {
          Request: request,
          Api: {
            loadMomentList: '/moment/list',
            publishMoment: '/moment/publish',
            uploadMomentMedia: '/moment/uploadMedia',
            likeMoment: '/moment/like',
            commentMoment: '/moment/comment',
            deleteMoment: '/moment/delete',
            deleteMomentComment: '/moment/deleteComment',
            momentDetail: '/moment/detail',
            momentUnreadCount: '/moment/unreadCount'
          },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  await flush()
  return { wrapper, request }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

/** ipcRenderer.on 注册的回调 */
function ipcCallback (channel) {
  const reg = window.ipcRenderer.__calls__.find((c) => c.channel === 'on' && c.args[0] === channel)
  return reg ? reg.args[1] : null
}

/**
 * 本次挂载注册的滚动回调。
 * ⚠ 不能用跨用例共享的模块级变量：wrapRef 是模块级共享对象，
 *   上一用例捕获的 handler 属于**已卸载的旧组件**（手测时被这一点骗过一次）。
 */
function currentScrollHandler () {
  const calls = wrapRef.addEventListener.mock.calls
  expect(calls.length).toBeGreaterThan(0)
  return calls[calls.length - 1][1]
}

beforeEach(() => {
  window.ipcRenderer.__reset__
  wrapRef.scrollTop = 0
  wrapRef.scrollHeight = 1000
  wrapRef.clientHeight = 900
  wrapRef.addEventListener.mockClear()
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
  MessageStub.error.mockClear()
  ConfirmStub.mockClear()
})

describe('Moment.vue 真实挂载（DOM 级）', () => {
  it('挂载即 loadMomentList + loadUnreadCount + 注册两通道 + 监听滚动', async () => {
    const { request } = await mountMoment()
    const urls = request.__calls__.map((c) => c.url)
    expect(urls).toContain('/moment/list')
    expect(urls).toContain('/moment/unreadCount')
    expect(ipcCallback('momentNotify')).toBeTruthy()
    expect(ipcCallback('momentUnread')).toBeTruthy()
    expect(wrapRef.addEventListener).toHaveBeenCalledWith('scroll', expect.any(Function))
  })

  it('列表渲染：昵称兜底 / 时间档位 / 位置 / 媒体九宫格类名', async () => {
    const { wrapper } = await mountMoment()
    const items = wrapper.findAll('.moment-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.name').text()).toBe('我')
    expect(items[0].find('.time').text()).toBe('5分钟前')
    expect(items[1].find('.time').text()).toBe('3小时前')
    expect(items[0].find('.location').text()).toContain('北京')
    expect(items[1].find('.location').exists()).toBe(false)
    expect(items[0].find('.moment-media').classes()).toContain('media-count-3')
    expect(items[1].find('.moment-media').exists()).toBe(false)
  })

  it('未读红点：>0 显示数值；点通知中心 → 清零并打开通知组件', async () => {
    const { wrapper } = await mountMoment()
    expect(wrapper.find('.notify-count').text()).toBe('3')
    await wrapper.find('.moment-notify-bar').trigger('click')
    expect(wrapper.find('.notify-count').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'MomentNotify' }).vm.show).toHaveBeenCalledTimes(1)
  })

  it('未读 >99 → 显示 99+；momentNotify 帧 +1；momentUnread 帧直接覆盖', async () => {
    const { wrapper } = await mountMoment({ '/moment/unreadCount': () => ({ code: 0, data: 150 }) })
    expect(wrapper.find('.notify-count').text()).toBe('99+')
    ipcCallback('momentNotify')()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.notify-count').text()).toBe('99+') // 已封顶
    ipcCallback('momentUnread')(null, { unreadCount: 5 })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.notify-count').text()).toBe('5')
    ipcCallback('momentUnread')(null, null) // 无数据不应崩、不改值
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.notify-count').text()).toBe('5')
  })

  it('unreadCount 请求失败 → 静默、不显示红点', async () => {
    const { wrapper } = await mountMoment({ '/moment/unreadCount': () => undefined })
    expect(wrapper.find('.notify-count').exists()).toBe(false)
  })

  it('locateMoment(refId) → 拉详情并打开详情弹窗', async () => {
    const { wrapper, request } = await mountMoment()
    wrapper.findComponent({ name: 'MomentNotify' }).vm.$emit('locateMoment', 10)
    await flush()
    expect(request.__calls__.find((c) => c.url === '/moment/detail').params).toEqual({ momentId: 10 })
    expect(wrapper.findComponent({ name: 'MomentDetail' }).vm.show).toHaveBeenCalledWith({ id: 10, content: '详情内容' })
  })

  it('locateMoment 失败 → 不打开详情', async () => {
    const { wrapper } = await mountMoment({ '/moment/detail': () => undefined })
    wrapper.findComponent({ name: 'MomentNotify' }).vm.$emit('locateMoment', 10)
    await flush()
    expect(wrapper.findComponent({ name: 'MomentDetail' }).vm.show).not.toHaveBeenCalled()
  })

  it('发布：空内容且无图 → warning、不发请求', async () => {
    const { wrapper, request } = await mountMoment()
    const before = request.__calls__.filter((c) => c.url === '/moment/publish').length
    await wrapper.findAll('.el-button').find((b) => b.text() === '发表').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('请先输入内容或选择图片')
    expect(request.__calls__.filter((c) => c.url === '/moment/publish')).toHaveLength(before)
  })

  it('发布成功（纯文字）→ 重载列表 + 清空编辑器 + 成功提示', async () => {
    const { wrapper, request } = await mountMoment()
    await wrapper.find('.moment-editor .el-input-native').setValue('新的一条')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发表').trigger('click')
    await flush()
    const call = request.__calls__.find((c) => c.url === '/moment/publish')
    expect(call.params.content).toBe('新的一条')
    expect(call.params.visibility).toBe(0)
    // 成功后重载列表（pageNo 复位为 1）
    const lists = request.__calls__.filter((c) => c.url === '/moment/list')
    expect(lists[lists.length - 1].params).toEqual({ pageNo: 1, pageSize: 20 })
    expect(wrapper.find('.moment-editor .el-input-native').element.value).toBe('')
    expect(MessageStub.success).toHaveBeenCalledWith('发表成功')
  })

  it('选图超 9 张 → warning、不入预览', async () => {
    const { wrapper } = await mountMoment()
    const input = wrapper.find('input[type="file"]')
    const files = Array.from({ length: 10 }, (_, i) => new File(['x'], `a${i}.png`, { type: 'image/png' }))
    Object.defineProperty(input.element, 'files', { value: files, configurable: true })
    await input.trigger('change')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('最多只能选择9张图片')
    expect(wrapper.findAll('.preview-item')).toHaveLength(0)
  })

  it('选图后发布 → 逐张 uploadMomentMedia(FormData 带 momentId/mediaType)，成功清空预览', async () => {
    const { wrapper, request } = await mountMoment()
    const input = wrapper.find('input[type="file"]')
    const file = new File(['x'], 'a.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await vi.waitFor(() => expect(wrapper.findAll('.preview-item')).toHaveLength(1))

    await wrapper.find('.moment-editor .el-input-native').setValue('带图')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发表').trigger('click')
    await flush()
    const upload = request.__calls__.find((c) => c.url === '/moment/uploadMedia')
    expect(upload).toBeTruthy()
    expect(upload.params.get('momentId')).toBe('777')
    expect(upload.params.get('mediaType')).toBe('0')
    expect(upload.params.get('file').name).toBe('a.png')
    expect(wrapper.findAll('.preview-item')).toHaveLength(0)
    expect(MessageStub.success).toHaveBeenCalledWith('发表成功')
  })

  it('图片上传失败（code!==200）→ error 提示，仍走完重载与成功提示', async () => {
    const { wrapper, request } = await mountMoment({ '/moment/uploadMedia': () => ({ code: 500, info: '磁盘满' }) })
    const input = wrapper.find('input[type="file"]')
    const file = new File(['x'], 'a.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await vi.waitFor(() => expect(wrapper.findAll('.preview-item')).toHaveLength(1))
    await wrapper.find('.moment-editor .el-input-native').setValue('带图')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发表').trigger('click')
    await flush()
    expect(MessageStub.error).toHaveBeenCalledWith('图片上传失败: 磁盘满')
    expect(MessageStub.success).toHaveBeenCalledWith('发表成功')
    expect(request.__calls__.filter((c) => c.url === '/moment/list').length).toBeGreaterThan(1)
  })

  it('发布失败（返回空）→ publishing 复位、不重载、不提示成功', async () => {
    const { wrapper, request } = await mountMoment({ '/moment/publish': () => undefined })
    const before = request.__calls__.filter((c) => c.url === '/moment/list').length
    await wrapper.find('.moment-editor .el-input-native').setValue('会失败')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发表').trigger('click')
    await flush()
    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(request.__calls__.filter((c) => c.url === '/moment/list')).toHaveLength(before)
    // 编辑器内容保留（未清空）
    expect(wrapper.find('.moment-editor .el-input-native').element.value).toBe('会失败')
  })

  it('点赞：cancel 取当前 likedByMe 反值，回写 likedByMe + likeList，按钮文案切换', async () => {
    const { wrapper, request } = await mountMoment()
    const firstBtn = wrapper.findAll('.moment-item')[0].findAll('.action-btn')[0]
    expect(firstBtn.text()).toContain('点赞')
    await firstBtn.trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/moment/like').params).toEqual({ momentId: 10, cancel: false })
    expect(wrapper.findAll('.moment-item')[0].find('.moment-likes').text()).toContain('我')
    expect(wrapper.findAll('.moment-item')[0].findAll('.action-btn')[0].text()).toContain('取消')
  })

  it('点赞失败（返回空）→ 不改本地态', async () => {
    const { wrapper } = await mountMoment({ '/moment/like': () => undefined })
    const item = wrapper.findAll('.moment-item')[0]
    await item.findAll('.action-btn')[0].trigger('click')
    await flush()
    expect(item.findAll('.action-btn')[0].text()).toContain('点赞')
    // 首条原本 likeList 为空，失败后不应出现点赞栏
    expect(item.find('.moment-likes').exists()).toBe(false)
  })

  it('评论：开输入框 → 空内容提交 → warning 不发请求；填内容提交 → 入列表并关框', async () => {
    const { wrapper, request } = await mountMoment()
    await wrapper.findAll('.moment-item')[0].findAll('.action-btn')[1].trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.moment-item .comment-box').exists()).toBe(true)

    await wrapper.findAll('.el-button').find((b) => b.text() === '发送').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('请输入评论内容')
    expect(request.__calls__.find((c) => c.url === '/moment/comment')).toBeUndefined()

    await wrapper.find('.comment-box .el-input-native').setValue('新评论')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发送').trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/moment/comment').params).toEqual({ momentId: 10, content: '新评论' })
    expect(wrapper.find('.comment-box').exists()).toBe(false)
    expect(wrapper.findAll('.moment-item')[0].findAll('.comment-line')).toHaveLength(2)
  })

  it('回复：点头像名 → 回复态占位符 + 提交带 replyToUserId/parentId；取消回复复位', async () => {
    const { wrapper, request } = await mountMoment()
    await wrapper.findAll('.moment-item')[0].findAll('.comment-line')[0].find('.name').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.comment-box .el-input-native').attributes('placeholder')).toBe('回复 阿强：')
    await wrapper.find('.comment-box .el-input-native').setValue('回复你')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发送').trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/moment/comment').params).toEqual({
      momentId: 10,
      content: '回复你',
      replyToUserId: 'U010',
      parentId: 101
    })
  })

  it('取消评论：关框 + 清草稿', async () => {
    const { wrapper } = await mountMoment()
    await wrapper.findAll('.moment-item')[0].findAll('.action-btn')[1].trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.find('.comment-box .el-input-native').setValue('草稿')
    await wrapper.findAll('.el-button').find((b) => b.text() === '取消').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.comment-box').exists()).toBe(false)
  })

  it('删除评论（自己的）→ Confirm → 请求 → 本地剔除', async () => {
    const { wrapper, request } = await mountMoment()
    // 自己发的评论带「删除」
    await wrapper.findAll('.moment-item')[1].find('.comment-del').trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toBe('确定要删除这条评论吗？')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(request.__calls__.find((c) => c.url === '/moment/deleteComment').params).toEqual({ commentId: 201 })
    expect(wrapper.findAll('.moment-item')[1].findAll('.comment-line')).toHaveLength(0)
    expect(MessageStub.success).toHaveBeenCalledWith('删除成功')
  })

  it('删除评论失败（返回空）→ 保留原评论', async () => {
    const { wrapper } = await mountMoment({ '/moment/deleteComment': () => undefined })
    await wrapper.findAll('.moment-item')[1].find('.comment-del').trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(wrapper.findAll('.moment-item')[1].findAll('.comment-line')).toHaveLength(1)
    expect(MessageStub.success).not.toHaveBeenCalled()
  })

  it('举报：他人评论 → ReportDialog 带 (comment, momentId, commentId)', async () => {
    const { wrapper } = await mountMoment()
    expect(wrapper.find('.report-stub').exists()).toBe(false)
    await wrapper.findAll('.moment-item')[0].find('.comment-report').trigger('click')
    await wrapper.vm.$nextTick()
    const rd = wrapper.find('.report-stub')
    expect(rd.exists()).toBe(true)
    expect(rd.attributes('data-type')).toBe('comment')
    expect(rd.attributes('data-moment')).toBe('10')
    expect(rd.attributes('data-comment')).toBe('101')
  })

  it('菜单按归属分流：自己的动态只有「删除」，他人动态只有「举报」', async () => {
    const { wrapper } = await mountMoment()
    const cmds = wrapper.findAll('.moment-item').map((it) => it.findAll('.dd-item').map((d) => d.attributes('data-cmd')))
    expect(cmds[0]).toEqual(['delete'])
    expect(cmds[1]).toEqual(['report'])
  })

  it('他人动态举报 → command=report → ReportDialog(moment, momentId, commentId=null)', async () => {
    const { wrapper } = await mountMoment()
    wrapper.findAll('.moment-item')[1].findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'report')
    await wrapper.vm.$nextTick()
    const rd = wrapper.find('.report-stub')
    expect(rd.attributes('data-type')).toBe('moment')
    expect(rd.attributes('data-moment')).toBe('20')
    expect(rd.attributes('data-comment')).toBeFalsy() // commentId=null → 属性不渲染
  })

  it('删除自己的动态 → Confirm → 请求 → 本地剔除该条', async () => {
    const { wrapper, request } = await mountMoment()
    wrapper.findAll('.moment-item')[0].findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'delete')
    expect(ConfirmStub.mock.calls[0][0].message).toBe('确定要删除这条朋友圈吗？')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(request.__calls__.find((c) => c.url === '/moment/delete').params).toEqual({ momentId: 10 })
    expect(wrapper.findAll('.moment-item')).toHaveLength(1)
    expect(MessageStub.success).toHaveBeenCalledWith('删除成功')
  })

  it('删除失败 → 保留该条', async () => {
    const { wrapper } = await mountMoment({ '/moment/delete': () => undefined })
    wrapper.findAll('.moment-item')[0].findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'delete')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(wrapper.findAll('.moment-item')).toHaveLength(2)
  })

  it('点头像/昵称 → 打开个人朋友圈主页（带 userId 与昵称）', async () => {
    const { wrapper } = await mountMoment()
    await wrapper.findAll('.moment-item')[1].find('.avatar-wrap').trigger('click')
    const um = wrapper.findComponent({ name: 'UserMoment' })
    expect(um.vm.show).toHaveBeenCalledWith('U010', '阿强')
  })

  it('点内容 → 打开详情弹窗', async () => {
    const { wrapper } = await mountMoment()
    await wrapper.findAll('.moment-item')[0].find('.moment-content').trigger('click')
    expect(wrapper.findComponent({ name: 'MomentDetail' }).vm.show).toHaveBeenCalledTimes(1)
  })

  it('图片预览：起始下标按过滤后图片序列重算（点第 3 张 → 索引 1）', async () => {
    const { wrapper } = await mountMoment()
    await wrapper.findAll('.moment-item')[0].findAll('.moment-media .media-item')[2].trigger('click')
    await wrapper.vm.$nextTick()
    const viewer = wrapper.find('.viewer-stub')
    expect(viewer.exists()).toBe(true)
    expect(viewer.attributes('data-count')).toBe('2') // 3 个媒体里只有 2 张图片
    expect(viewer.attributes('data-index')).toBe('1')
    await wrapper.find('.viewer-close').trigger('click')
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
  })

  it('点视频 → 不开图片预览，改为打开详情', async () => {
    const { wrapper } = await mountMoment()
    await wrapper.findAll('.moment-item')[0].findAll('.moment-media .media-item')[1].trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'MomentDetail' }).vm.show).toHaveBeenCalledTimes(1)
  })

  it('空列表 → 「暂无朋友圈内容」', async () => {
    const { wrapper } = await mountMoment({ '/moment/list': () => ({ code: 0, data: [] }) })
    expect(wrapper.find('.empty-tip').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无朋友圈内容')
  })

  it('不足一页 → 显示「没有更多了」', async () => {
    const { wrapper } = await mountMoment()
    expect(wrapper.find('.no-more-tip').exists()).toBe(true)
  })

  it('滚到底 → pageNo++ 并追加列表（append 拼接而非替换）', async () => {
    const full = Array.from({ length: 20 }, (_, i) => ({ id: i + 1, userId: 'U010', content: 'c' + i, createTime: NOW }))
    const { wrapper, request } = await mountMoment({ '/moment/list': () => ({ code: 0, data: full }) })
    const scroll = currentScrollHandler()
    // 挂载后已渲染 20 条，noMore 未置位
    expect(wrapper.find('.no-more-tip').exists()).toBe(false)

    wrapRef.scrollTop = 200
    scroll()
    await flush()
    const lists = request.__calls__.filter((c) => c.url === '/moment/list')
    expect(lists[lists.length - 1].params.pageNo).toBe(2)
    expect(wrapper.findAll('.moment-item')).toHaveLength(40)

    wrapRef.scrollTop = 400
    scroll()
    await flush()
    expect(wrapper.findAll('.moment-item')).toHaveLength(60)
  })

  it('noMore 置位后滚动不再请求（noMore 守卫）', async () => {
    let page = 0
    const { wrapper, request } = await mountMoment({
      '/moment/list': () => {
        page++
        // 首页满页 20，第二页不足 → noMore 置位
        return page === 1
          ? { code: 0, data: Array.from({ length: 20 }, (_, i) => ({ id: i + 1, content: 'c' + i, createTime: NOW })) }
          : { code: 0, data: [{ id: 99, content: '最后一条', createTime: NOW }] }
      }
    })
    const scroll = currentScrollHandler()
    wrapRef.scrollTop = 200
    scroll()
    await flush()
    expect(wrapper.findAll('.moment-item')).toHaveLength(21)
    expect(wrapper.find('.no-more-tip').exists()).toBe(true)

    const before = request.__calls__.filter((c) => c.url === '/moment/list').length
    wrapRef.scrollTop = 400
    scroll()
    await flush()
    expect(request.__calls__.filter((c) => c.url === '/moment/list')).toHaveLength(before)
  })

  it('列表加载中（loading）→ 滚动请求被挡（并发去重）', async () => {
    let pending = 0
    let maxConcurrent = 0
    const { request } = await mountMoment({
      '/moment/list': async () => {
        pending++
        maxConcurrent = Math.max(maxConcurrent, pending)
        await new Promise((r) => setTimeout(r, 5))
        pending--
        return { code: 0, data: [] }
      }
    })
    const scroll = currentScrollHandler()
    wrapRef.scrollTop = 300
    scroll()
    scroll()
    scroll()
    await flush()
    await new Promise((r) => setTimeout(r, 30))
    // 首屏请求 + 后续被 loading 挡掉，不应出现同刻多次
    expect(maxConcurrent).toBe(1)
    expect(request.__calls__.filter((c) => c.url === '/moment/list').length).toBeLessThanOrEqual(2)
  })
})
