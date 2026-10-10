import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import UserMoment from '@/views/moment/UserMoment.vue'

/**
 * 个人朋友圈主页弹窗的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. show(userId, name) → 标题「XX 的朋友圈」+ 昵称兜底 userId + pageNo 复位
 *   2. 列表渲染：时间（MM-DD HH:mm）/ 内容 / 赞评论计数（缺字段兜底 0）/ 媒体分流
 *   3. 空态：列表为空 → 「TA 还没有发布过动态」；不足一页 → 「没有更多了」
 *   4. 满页（=pageSize）→ 不显示「没有更多了」（noMore 只在 <pageSize 时置位）
 *   5. 打开图片 → 内置查看器（起始索引按图片序对齐）；视频 → newWindow /showMedia
 *   6. 接口失败（返回空）→ 列表留空不崩
 */

const MessageStub = { success: vi.fn(), warning: vi.fn() }

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: [] }
  })
  fn.__calls__ = calls
  return fn
}

const DialogStub = {
  name: 'Dialog',
  props: ['show', 'title', 'width', 'showCancel'],
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
const ElScrollbarStub = {
  name: 'ElScrollbar',
  template: '<div class="el-scrollbar-stub"><div class="wrap"><slot /></div></div>'
}
const ElImageViewerStub = {
  name: 'ElImageViewer',
  props: ['urlList', 'initialIndex'],
  emits: ['close'],
  template:
    '<div class="viewer-stub" :data-count="urlList.length" :data-index="initialIndex">' +
    '<button class="viewer-close" @click="$emit(\'close\')">X</button></div>'
}

const LIST = [
  {
    id: 1,
    content: '第一条',
    createTime: new Date(2026, 0, 2, 3, 4).getTime(),
    likeList: [{ userId: 'U010' }],
    commentList: [{ id: 1 }, { id: 2 }],
    mediaList: [
      { id: 11, mediaType: 0, filePath: 'F001' },
      { id: 12, mediaType: 1, filePath: 'F002' },
      { id: 13, mediaType: 0, filePath: 'F003' }
    ]
  },
  {
    id: 2,
    content: '第二条',
    createTime: new Date(2026, 0, 3, 5, 6).getTime(),
    likeList: [],
    commentList: []
  }
]

async function mountUserMoment (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const request = makeRequest(handler)
  const wrapper = mount(UserMoment, {
    global: {
      plugins: [pinia],
      stubs: {
        Dialog: DialogStub,
        Avatar: AvatarStub,
        'el-scrollbar': ElScrollbarStub,
        'el-image-viewer': ElImageViewerStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { userMomentList: '/moment/userList' },
          Message: MessageStub
        }
      }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper, request }
}

const flush = () => new Promise((r) => setTimeout(r, 0))
const freshList = () => JSON.parse(JSON.stringify(LIST))

beforeEach(() => {
  window.ipcRenderer.__reset__
})

describe('UserMoment.vue 真实挂载（DOM 级）', () => {
  it('初始关窗；show(userId, name) → 开窗 + 标题 + 昵称与条数', async () => {
    const { wrapper, request } = await mountUserMoment(() => ({ code: 0, data: freshList() }))
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    wrapper.vm.show('U010', '阿强')
    await flush()
    expect(wrapper.find('.d-title').text()).toBe('阿强 的朋友圈')
    expect(wrapper.find('.user-meta .name').text()).toBe('阿强')
    expect(wrapper.find('.user-meta .sub').text()).toBe('2 条动态')
    expect(request.__calls__[0].params).toEqual({ targetUserId: 'U010', pageNo: 1, pageSize: 20 })
  })

  it('show 无昵称 → 标题与头部昵称兜底 userId', async () => {
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: [] }))
    wrapper.vm.show('U020')
    await flush()
    expect(wrapper.find('.d-title').text()).toBe('U020 的朋友圈')
    expect(wrapper.find('.user-meta .name').text()).toBe('U020')
  })

  it('列表渲染：时间 MM-DD HH:mm + 内容 + 赞/评论计数', async () => {
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: freshList() }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    const items = wrapper.findAll('.moment-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.moment-time').text()).toBe('01-02 03:04')
    expect(items[0].find('.moment-content').text()).toBe('第一条')
    // 计数是两个 span，text() 拼接无分隔符 → 按 span 断
    expect(items[0].findAll('.moment-stat span').map((s) => s.text())).toEqual(['1 赞', '2 评论'])
    // 空数组计数为 0
    expect(items[1].findAll('.moment-stat span').map((s) => s.text())).toEqual(['0 赞', '0 评论'])
  })

  it('缺 likeList/commentList 字段 → 计数兜底 0、不报错', async () => {
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: [{ id: 9, content: '裸数据', createTime: 1770000000000 }] }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    expect(wrapper.find('.moment-stat').text()).toBe('0 赞0 评论')
  })

  it('媒体分流：图片走 img、视频走 video + 角标', async () => {
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: freshList() }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    const media = wrapper.findAll('.moment-media .media-item')
    expect(media).toHaveLength(3)
    expect(media[0].find('img').attributes('src')).toContain('fileId=F001')
    expect(media[1].find('video').attributes('src')).toContain('fileId=F002')
    expect(media[1].find('.video-icon').exists()).toBe(true)
    expect(media[2].find('img').attributes('src')).toContain('showCover=false')
  })

  it('点图片 → 内置查看器：只含图片、起始索引按图片序（跳过视频）', async () => {
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: freshList() }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    await wrapper.findAll('.moment-media .media-item')[2].trigger('click') // 第 2 张图片
    await wrapper.vm.$nextTick()
    const viewer = wrapper.find('.viewer-stub')
    expect(viewer.exists()).toBe(true)
    expect(viewer.attributes('data-count')).toBe('2')
    expect(viewer.attributes('data-index')).toBe('1')
    await wrapper.find('.viewer-close').trigger('click')
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
  })

  it('点视频 → 不开查看器，走 newWindow 媒体窗口通道', async () => {
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: freshList() }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    await wrapper.findAll('.moment-media .media-item')[1].trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
    const send = window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'newWindow')
    expect(send).toBeTruthy()
    expect(send.args[1].path).toBe('/showMedia')
    expect(send.args[1].data.currentFileId).toBe('F002')
    expect(send.args[1].data.fileList[0].fileType).toBe(1)
  })

  it('空列表 → 「TA 还没有发布过动态」', async () => {
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: [] }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    expect(wrapper.find('.tip.empty').exists()).toBe(true)
    expect(wrapper.find('.tip.empty').text()).toContain('TA 还没有发布过动态')
  })

  it('不足一页 → 列表下方显示「没有更多了」', async () => {
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: freshList() }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    expect(wrapper.text()).toContain('没有更多了')
  })

  it('满页（=pageSize）→ 不显示「没有更多了」（留给后续翻页）', async () => {
    const full = Array.from({ length: 20 }, (_, i) => ({ id: i + 1, content: 'c' + i, createTime: 1770000000000 }))
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: full }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    expect(wrapper.findAll('.moment-item')).toHaveLength(20)
    expect(wrapper.text()).not.toContain('没有更多了')
  })

  it('接口失败（返回空）→ 列表留空、落空态不崩', async () => {
    const { wrapper } = await mountUserMoment(() => undefined)
    wrapper.vm.show('U010', '阿强')
    await flush()
    expect(wrapper.findAll('.moment-item')).toHaveLength(0)
    expect(wrapper.find('.tip.empty').exists()).toBe(true)
  })

  it('再次 show 另一人 → 标题/列表整体替换（不残留）', async () => {
    let seq = 0
    const { wrapper } = await mountUserMoment(() => ({ code: 0, data: seq++ === 0 ? freshList() : [] }))
    wrapper.vm.show('U010', '阿强')
    await flush()
    expect(wrapper.findAll('.moment-item')).toHaveLength(2)
    wrapper.vm.show('U020', '阿伟')
    await flush()
    expect(wrapper.find('.d-title').text()).toBe('阿伟 的朋友圈')
    expect(wrapper.findAll('.moment-item')).toHaveLength(0)
    expect(wrapper.find('.tip.empty').exists()).toBe(true)
  })
})
