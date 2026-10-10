import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MomentDetail from '@/views/moment/MomentDetail.vue'

/**
 * 朋友圈详情弹窗的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. show(data) → 开窗 + 回填 + 草稿/回复态复位
 *   2. 渲染：昵称兜底 userId / 内容 / 位置 / 图片与视频分流 / 点赞列表 / 评论数与空态
 *   3. 图片 URL 拼装：localServerPort + fileId + partType=moment + showCover=false
 *   4. 点图片 → 内置查看器（只含图片、起始索引对齐）；点视频 → newWindow 走 showMedia 通道
 *   5. 提交评论：空内容 → warning；回复态带 replyToUserId/parentId；成功后入列 + emit refresh
 *   6. 提交失败（返回空）→ 不入列、不 emit
 *   7. 回复/取消回复：占位符切换与「取消回复」按钮显隐
 */

const MessageStub = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }

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
    '<button class="d-close" @click="$emit(\'close\')">X</button></div>'
}
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width', 'borderRadius'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'type', 'autosize'],
  emits: ['update:modelValue'],
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
const ElDividerStub = { name: 'ElDivider', template: '<hr class="el-divider-stub" />' }
const ElImageViewerStub = {
  name: 'ElImageViewer',
  props: ['urlList', 'initialIndex'],
  emits: ['close'],
  template:
    '<div class="viewer-stub" :data-count="urlList.length" :data-index="initialIndex">' +
    '<button class="viewer-close" @click="$emit(\'close\')">X</button></div>'
}

const MOMENT = {
  id: 555,
  userId: 'U001',
  nickName: '',
  content: '今天天气不错',
  location: '北京市东城区',
  createTime: new Date(2026, 0, 2, 3, 4).getTime(),
  mediaList: [
    { id: 1, mediaType: 0, filePath: 'F001' },
    { id: 2, mediaType: 1, filePath: 'F002' }
  ],
  likeList: [{ userId: 'U010', nickName: '阿强' }, { userId: 'U011', nickName: '' }],
  commentList: [
    { id: 11, userId: 'U010', nickName: '阿强', content: '好看', createTime: 1770000000000 },
    { id: 12, userId: 'U011', nickName: '', content: '同感', replyToUserId: 'U010', replyToNickName: '阿强', createTime: 1770000000000 }
  ]
}

async function mountDetail (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const request = makeRequest(handler)
  const wrapper = mount(MomentDetail, {
    global: {
      plugins: [pinia],
      stubs: {
        Dialog: DialogStub,
        Avatar: AvatarStub,
        'el-input': ElInputStub,
        'el-button': ElButtonStub,
        'el-divider': ElDividerStub,
        'el-image-viewer': ElImageViewerStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { commentMoment: '/moment/comment' },
          Message: MessageStub
        }
      }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper, request }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  window.ipcRenderer.__reset__
  MessageStub.warning.mockClear()
})

/** 深拷贝夹具：组件会 push commentList（共享会跨用例污染） */
const freshMoment = () => JSON.parse(JSON.stringify(MOMENT))

describe('MomentDetail.vue 真实挂载（DOM 级）', () => {
  it('初始关窗；show(data) → 开窗 + 回填内容/位置/时间', async () => {
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.find('.d-title').text()).toBe('朋友圈详情')
    expect(wrapper.find('.moment-content').text()).toBe('今天天气不错')
    expect(wrapper.find('.location').text()).toContain('北京市东城区')
    // 昵称兜底 userId
    expect(wrapper.find('.user-meta .name').text()).toBe('U001')
    expect(wrapper.find('.user-meta .time').text()).toBe('2026-01-02 03:04')
  })

  it('媒体渲染：图片走 img、视频走 video 且带视频角标；URL 含本地端口与 partType=moment', async () => {
    const { wrapper } = await mountDetail()
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    const items = wrapper.findAll('.moment-media .media-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('img').attributes('src')).toContain('/file?fileId=F001')
    expect(items[0].find('img').attributes('src')).toContain('partType=moment')
    expect(items[0].find('img').attributes('src')).toContain('showCover=false')
    expect(items[1].find('video').attributes('src')).toContain('fileId=F002')
    expect(items[1].find('.video-icon').exists()).toBe(true)
  })

  it('点赞列表：昵称兜底 userId；无 likeList 时整段不渲染', async () => {
    const { wrapper } = await mountDetail()
    const m = freshMoment()
    wrapper.vm.show(m)
    await wrapper.vm.$nextTick()
    const likes = wrapper.findAll('.like-item')
    expect(likes).toHaveLength(2)
    expect(likes[0].find('.name').text()).toBe('阿强')
    expect(likes[1].find('.name').text()).toBe('U011')

    wrapper.vm.show({ ...freshMoment(), likeList: [] })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.like-section').exists()).toBe(false)
  })

  it('评论列表：条数 + 回复前缀「回复 阿强：」；无评论显示「暂无评论」', async () => {
    const { wrapper } = await mountDetail()
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.comment-section .section-title').text()).toContain('评论 2')
    const items = wrapper.findAll('.comment-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.comment-text').text()).toContain('好看')
    expect(items[1].find('.reply-name').text()).toBe('阿强')
    expect(items[1].find('.comment-text').text()).toContain('回复')

    wrapper.vm.show({ ...freshMoment(), commentList: [] })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.empty-comment').text()).toBe('暂无评论')
    // commentList 为 undefined 时同样落到空态（可选链）
    wrapper.vm.show({ ...freshMoment(), commentList: undefined })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.empty-comment').exists()).toBe(true)
  })

  it('点图片 → 内置查看器：只含图片 + 起始索引对齐；关闭后隐藏', async () => {
    const { wrapper } = await mountDetail()
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
    await wrapper.findAll('.moment-media .media-item')[0].trigger('click')
    await wrapper.vm.$nextTick()
    const viewer = wrapper.find('.viewer-stub')
    expect(viewer.exists()).toBe(true)
    // 2 个媒体里只有 1 个是图片
    expect(viewer.attributes('data-count')).toBe('1')
    expect(viewer.attributes('data-index')).toBe('0')
    await wrapper.find('.viewer-close').trigger('click')
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
  })

  it('点视频 → 不开内置查看器，改走 newWindow 媒体窗口通道', async () => {
    const { wrapper } = await mountDetail()
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    await wrapper.findAll('.moment-media .media-item')[1].trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
    const send = window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'newWindow')
    expect(send).toBeTruthy()
    expect(send.args[1].windowId).toBe('media')
    expect(send.args[1].path).toBe('/showMedia')
    expect(send.args[1].data.currentFileId).toBe('F002')
    expect(send.args[1].data.fileList[0].fileType).toBe(1)
    expect(send.args[1].data.fileList[0].partType).toBe('moment')
  })

  it('提交评论：空内容 → warning、不发请求', async () => {
    const { wrapper, request } = await mountDetail()
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    await wrapper.findAll('.el-button').find((b) => b.text() === '发送').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('请输入评论内容')
    expect(request.__calls__).toHaveLength(0)
  })

  it('提交评论成功 → 入列表 + 清草稿 + emit refresh', async () => {
    const newComment = { id: 99, userId: 'U001', nickName: '我', content: '同感同感', createTime: 1770000000000 }
    const { wrapper, request } = await mountDetail(() => ({ code: 0, data: newComment }))
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input-native').setValue('同感同感')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发送').trigger('click')
    await flush()
    const call = request.__calls__[0]
    expect(call.url).toBe('/moment/comment')
    expect(call.params).toEqual({ momentId: 555, content: '同感同感' })
    expect(wrapper.findAll('.comment-item')).toHaveLength(3)
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.find('.el-input-native').element.value).toBe('')
  })

  it('回复态提交：带 replyToUserId + parentId，且占位符切换、出现「取消回复」', async () => {
    const { wrapper, request } = await mountDetail(() => ({
      code: 0,
      data: { id: 100, userId: 'U001', nickName: '我', content: '回复你', createTime: 1770000000000 }
    }))
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.el-input-native').attributes('placeholder')).toBe('说点什么...')
    await wrapper.findAll('.comment-item')[0].find('.action').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.el-input-native').attributes('placeholder')).toBe('回复 阿强：')

    await wrapper.find('.el-input-native').setValue('回复你')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发送').trigger('click')
    await flush()
    expect(request.__calls__[0].params).toEqual({
      momentId: 555,
      content: '回复你',
      replyToUserId: 'U010',
      parentId: 11
    })
    // 成功后回复态复位
    expect(wrapper.find('.el-input-native').attributes('placeholder')).toBe('说点什么...')
    const cancelBtn = wrapper.findAll('.el-button').find((b) => b.text() === '取消回复')
    expect(cancelBtn).toBeUndefined()
  })

  it('「取消回复」→ 回复态清空、不发请求', async () => {
    const { wrapper, request } = await mountDetail()
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    await wrapper.findAll('.comment-item')[0].find('.action').trigger('click')
    await wrapper.findAll('.el-button').find((b) => b.text() === '取消回复').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.el-input-native').attributes('placeholder')).toBe('说点什么...')
    expect(request.__calls__).toHaveLength(0)
  })

  it('提交失败（返回空）→ 不入列、不 emit', async () => {
    const { wrapper } = await mountDetail(() => undefined)
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input-native').setValue('会失败')
    await wrapper.findAll('.el-button').find((b) => b.text() === '发送').trigger('click')
    await flush()
    expect(wrapper.findAll('.comment-item')).toHaveLength(2)
    expect(wrapper.emitted('refresh')).toBeUndefined()
  })

  it('show() 再次调用 → 草稿与回复态复位（不残留上一条）', async () => {
    const { wrapper } = await mountDetail(() => ({ code: 0, data: { id: 101, userId: 'U001', content: 'x' } }))
    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input-native').setValue('残留草稿')
    await wrapper.findAll('.comment-item')[0].find('.action').trigger('click')

    wrapper.vm.show(freshMoment())
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.el-input-native').element.value).toBe('')
    expect(wrapper.find('.el-input-native').attributes('placeholder')).toBe('说点什么...')
  })
})
