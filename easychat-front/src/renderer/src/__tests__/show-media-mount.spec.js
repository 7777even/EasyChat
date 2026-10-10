import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ShowMedia from '@/views/show/ShowMedia.vue'

/**
 * 媒体预览独立窗口的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 初始 fileList[0].status=0 → 显示「加载中....」
 *   2. pageInitData → 取端口 + 文件列表 + currentFileId 定位（找不到回落 0）+ URL 拼装
 *   3. 边界：首项 not-allow / 末项 not-allow；越界点击不换文件
 *   4. 图片 → viewer 渲染；视频 → dPlayer.switchVideo + 播放容器可见；fileType=2 → 文件面板
 *   5. 图片操作：放大/缩小/旋转/原始大小（viewer 实例方法）+ 标题随 isOne2One 切换
 *   6. saveAs → ipc send(saveAs) 带 partType/fileId/fileType（文件面板的下载按钮同源）
 *   7. closeWin → dPlayer.pause（关窗不停播放）
 *   8. 卸载 → removeAllListeners 两个通道 + 摘掉 wheel 监听
 */

const dPlayerPause = vi.fn()
const dPlayerSwitchVideo = vi.fn()
const dPlayerInstances = []

vi.mock('dplayer', () => ({
  default: class DPlayer {
    constructor (opts) {
      this.opts = opts
      dPlayerInstances.push(this)
    }

    pause () { dPlayerPause() }

    switchVideo (v) { dPlayerSwitchVideo(v) }
  }
}))

const viewerApi = { zoom: vi.fn(), rotate: vi.fn(), zoomTo: vi.fn(), initialImageData: { ratio: 0.5 } }
/** v-viewer 是全局注册组件（模板用小写 <viewer>），须在 stubs 里按名匹配 */
const ViewerStub = {
  name: 'Viewer',
  props: ['options', 'images'],
  emits: ['inited'],
  template: '<div class="viewer-stub"><slot /></div>'
}
const WinOpStub = {
  name: 'WinOp',
  emits: ['closeCallback'],
  template: '<div class="winop-stub"><button class="win-close" @click="$emit(\'closeCallback\')">X</button></div>'
}
const ElDividerStub = { name: 'ElDivider', props: ['direction'], template: '<span class="el-divider-stub" />' }
const ElButtonStub = {
  name: 'ElButton',
  props: ['type'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}

const FILE_LIST = [
  { partType: 'moment', fileId: 'F001', fileType: 0, fileName: 'a.png', fileSize: 1024, forceGet: false },
  { partType: 'moment', fileId: 'F002', fileType: 1, fileName: 'b.mp4', fileSize: 2048, forceGet: false },
  { partType: 'file', fileId: 'F003', fileType: 2, fileName: 'c.pdf', fileSize: 1048576, forceGet: false }
]

/**
 * 已挂载 wrapper 登记表。
 * ⚠ 必须回收：组件在 onMounted 往 **window** 上挂 wheel 监听、往 ipcRenderer 挂通道，
 *   VTU 默认不在用例间卸载 → 上一个用例的旧实例仍会响应后续 window 事件，
 *   其 viewerMy 为 null 直接抛 "Cannot read properties of null"（首版即踩中 12 个 error）。
 */
const mounted = []

async function mountMedia () {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div/>' } }] })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(ShowMedia, {
    global: {
      plugins: [pinia, router],
      stubs: { viewer: ViewerStub, WinOp: WinOpStub, 'el-divider': ElDividerStub, 'el-button': ElButtonStub },
      config: {
        globalProperties: {
          Utils: { size2Str: (n) => `${Math.round(n / 1024)}KB` }
        }
      }
    }
  })
  mounted.push(wrapper)
  await wrapper.vm.$nextTick()
  return { wrapper }
}

/** 手动卸载后从登记表摘除，避免 afterEach 二次 unmount */
function unmountAndForget (wrapper) {
  wrapper.unmount()
  const i = mounted.indexOf(wrapper)
  if (i > -1) mounted.splice(i, 1)
}

function ipcCallback (channel) {
  const reg = window.ipcRenderer.__calls__.find((c) => c.channel === 'on' && c.args[0] === channel)
  return reg ? reg.args[1] : null
}

const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  window.ipcRenderer.__reset__
  dPlayerPause.mockClear()
  dPlayerSwitchVideo.mockClear()
  // 实例表是跨用例累积的（每次挂载 new 一个），不清会数到全量而非本次
  dPlayerInstances.length = 0
  viewerApi.zoom.mockClear()
  viewerApi.rotate.mockClear()
  viewerApi.zoomTo.mockClear()
})

afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
})

describe('ShowMedia.vue 真实挂载（DOM 级）', () => {
  it('初始未收到 pageInitData → 显示「加载中....」', async () => {
    const { wrapper } = await mountMedia()
    expect(wrapper.find('.loading').text()).toBe('加载中....')
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
  })

  it('pageInitData：定位 currentFileId + URL 拼装（端口/partType/forceGet/时间戳）', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F001' })
    await flush()
    const img = wrapper.find('.viewer-stub img')
    expect(img.attributes('src')).toContain('http://localhost:7788/file?fileId=F001')
    expect(img.attributes('src')).toContain('partType=moment')
    expect(img.attributes('src')).toContain('fileType=0')
    expect(img.attributes('src')).toContain('forceGet=false')
    expect(wrapper.find('.loading').exists()).toBe(false)
  })

  it('currentFileId 不在列表 → 回落第 0 项', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'NOT_EXIST' })
    await flush()
    expect(wrapper.find('.viewer-stub img').attributes('src')).toContain('fileId=F001')
  })

  it('currentFileId 缺省 → 第 0 项；边界按钮 not-allow 态正确', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST })
    await flush()
    const prev = wrapper.find('.icon-left')
    const next = wrapper.find('.icon-right')
    expect(prev.classes()).toContain('not-allow')
    expect(next.classes()).not.toContain('not-allow')

    // 越界点击：不得进入 getCurrentFile（其首个副作用是 dPlayer.pause），
    // 也不能靠「DOM 没变」判定——删掉越界守卫时代码会抛错，DOM 同样不变（§2.1 第 3 条）
    dPlayerPause.mockClear()
    dPlayerSwitchVideo.mockClear()
    await prev.trigger('click')
    await flush()
    expect(dPlayerPause).not.toHaveBeenCalled()
    expect(dPlayerSwitchVideo).not.toHaveBeenCalled()
    expect(wrapper.find('.viewer-stub img').attributes('src')).toContain('fileId=F001')
  })

  it('切到末项 → 右侧 not-allow、左侧可点；越界右侧不换文件', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F003' })
    await flush()
    // fileType=2 → 文件面板
    expect(wrapper.find('.file-panel').exists()).toBe(true)
    expect(wrapper.find('.icon-left').classes()).not.toContain('not-allow')
    expect(wrapper.find('.icon-right').classes()).toContain('not-allow')

    const before = wrapper.find('.file-panel .file-item').text()
    dPlayerPause.mockClear()
    await wrapper.find('.icon-right').trigger('click')
    await flush()
    // 同上：必须断言「未进入 getCurrentFile」，而非仅看 DOM 未变
    expect(dPlayerPause).not.toHaveBeenCalled()
    expect(wrapper.find('.file-panel .file-item').text()).toBe(before)
  })

  it('文件面板：文件名 + size2Str + 下载按钮', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F003' })
    await flush()
    const items = wrapper.findAll('.file-panel .file-item')
    expect(items[0].text()).toBe('文件名：c.pdf')
    expect(items[1].text()).toBe('文件大小：1024KB')
    // 文件类型无缩放工具条
    expect(wrapper.find('.icon-enlarge').exists()).toBe(false)
  })

  it('切到视频 → dPlayer.switchVideo 带 URL，且先 pause 上一媒体', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F001' })
    await flush()
    await wrapper.find('.icon-right').trigger('click')
    await flush()
    expect(dPlayerPause).toHaveBeenCalled()
    expect(dPlayerSwitchVideo).toHaveBeenCalledTimes(1)
    const url = dPlayerSwitchVideo.mock.calls[0][0].url
    expect(url).toContain('http://localhost:7788/file?fileId=F002')
    expect(url).toContain('fileType=1')
    // 视频模式：viewer 不渲染
    expect(wrapper.find('.viewer-stub').exists()).toBe(false)
  })

  it('图片操作：放大/缩小/旋转调用 viewer 实例方法', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F001' })
    await flush()
    wrapper.findComponent(ViewerStub).vm.$emit('inited', viewerApi)
    await wrapper.find('.icon-enlarge').trigger('click')
    expect(viewerApi.zoom).toHaveBeenLastCalledWith(0.1, true)
    await wrapper.find('.icon-narrow').trigger('click')
    expect(viewerApi.zoom).toHaveBeenLastCalledWith(-0.1, true)
    await wrapper.find('.icon-rotate').trigger('click')
    expect(viewerApi.rotate).toHaveBeenCalledWith(90, true)
  })

  it('原始大小切换：标题随 isOne2One 变，zoomTo 在 ratio 与 1 之间切', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F001' })
    await flush()
    wrapper.findComponent(ViewerStub).vm.$emit('inited', viewerApi)
    const resizeBtn = wrapper.find('.icon-source-size')
    expect(resizeBtn.attributes('title')).toBe('图片原始大小')
    await resizeBtn.trigger('click')
    expect(viewerApi.zoomTo).toHaveBeenLastCalledWith(1, true)
    expect(wrapper.find('.icon-resize').attributes('title')).toBe('图片适应窗口大小')
    await wrapper.find('.icon-resize').trigger('click')
    expect(viewerApi.zoomTo).toHaveBeenLastCalledWith(0.5, true)
    expect(wrapper.find('.icon-source-size').attributes('title')).toBe('图片原始大小')
  })

  it('滚轮：图片放大缩小；非图片忽略', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F001' })
    await flush()
    wrapper.findComponent(ViewerStub).vm.$emit('inited', viewerApi)
    window.dispatchEvent(new WheelEvent('wheel', { deltaY: -120 }))
    expect(viewerApi.zoom).toHaveBeenLastCalledWith(0.1, true)
    window.dispatchEvent(new WheelEvent('wheel', { deltaY: 120 }))
    expect(viewerApi.zoom).toHaveBeenLastCalledWith(-0.1, true)

    // 切到文件类型后滚轮不再缩放（且不因 viewerMy 为空而抛错）
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F003' })
    await flush()
    viewerApi.zoom.mockClear()
    window.dispatchEvent(new WheelEvent('wheel', { deltaY: -120 }))
    expect(viewerApi.zoom).not.toHaveBeenCalled()
  })

  it('saveAs（工具栏）→ ipc send 带当前文件的 partType/fileId/fileType', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F002' })
    await flush()
    await wrapper.find('.icon-download').trigger('click')
    const send = window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'saveAs')
    expect(send.args[1]).toEqual({ partType: 'moment', fileId: 'F002', fileType: 1 })
  })

  it('saveAs（文件面板下载按钮）→ 同样走 saveAs 通道', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F003' })
    await flush()
    await wrapper.findAll('.el-button').find((b) => b.text() === '下载文件').trigger('click')
    const send = window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'saveAs')
    expect(send.args[1]).toEqual({ partType: 'file', fileId: 'F003', fileType: 2 })
  })

  it('closeWin → dPlayer.pause（关窗不清播放会留声）', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F002' })
    await flush()
    dPlayerPause.mockClear()
    await wrapper.find('.win-close').trigger('click')
    expect(dPlayerPause).toHaveBeenCalledTimes(1)
  })

  it('卸载 → removeAllListeners 两通道 + 摘 wheel 监听', async () => {
    const { wrapper } = await mountMedia()
    ipcCallback('pageInitData')(null, { localServerPort: 7788, fileList: FILE_LIST, currentFileId: 'F001' })
    await flush()
    wrapper.findComponent(ViewerStub).vm.$emit('inited', viewerApi)
    unmountAndForget(wrapper)
    const removed = window.ipcRenderer.__calls__.filter((c) => c.channel === 'removeAllListeners')
    expect(removed.map((c) => c.args[0]).sort()).toEqual(['checkFileCallback', 'pageInitData'])
    // wheel 已摘除：卸载后再派发不应触发 viewer
    viewerApi.zoom.mockClear()
    window.dispatchEvent(new WheelEvent('wheel', { deltaY: -120 }))
    expect(viewerApi.zoom).not.toHaveBeenCalled()
  })

  it('DPlayer 以 #player 容器初始化（渲染容器缺失即抛错，故必须存在）', async () => {
    const { wrapper } = await mountMedia()
    expect(dPlayerInstances).toHaveLength(1)
    expect(dPlayerInstances[0].opts.theme).toBe('#b7daff')
    expect(wrapper.find('#player').exists()).toBe(true)
  })
})
