import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CallWindow from '@/views/chat/CallWindow.vue'
import { useCallStore } from '@/stores/useCallStore'

/**
 * 通话窗口的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. idle → 整个窗口不渲染
 *   2. ringing → 来电面板（邀请类型/媒体文案 + 拒绝/忙线/接听三个入口）
 *   3. 通话中 → 标题（单聊/群）、endReason、静音/摄像头/挂断、群成员
 *   4. remoteStreams → 远程视频格；localStream → 本地 video 绑定 srcObject
 *   5. errorMsg 展示
 */

const stubs = {
  'el-button': {
    name: 'el-button',
    props: ['type', 'circle'],
    emits: ['click'],
    template: `<button class="el-button" :data-type="type" @click="$emit('click')"><slot /></button>`
  }
}

let callStore

function mountWindow() {
  const wrapper = mount(CallWindow, { global: { stubs, plugins: [createPinia()] } })
  callStore = useCallStore()
  return wrapper
}

describe('CallWindow.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('status=idle → 不渲染', () => {
    const wrapper = mountWindow()
    expect(wrapper.find('.call-window-mask').exists()).toBe(false)
  })

  it('ringing 单聊语音 → 邀请文案 + 三个操作入口', async () => {
    const wrapper = mountWindow()
    callStore.status = 'ringing'
    callStore.callType = 1
    callStore.mediaType = 1
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.call-incoming').exists()).toBe(true)
    expect(wrapper.find('.call-title').text()).toBe('好友通话邀请')
    expect(wrapper.find('.call-sub').text()).toBe('语音通话')
    expect(wrapper.findAll('.call-actions .el-button').map((b) => b.text())).toEqual(['拒绝', '忙线', '接听'])
  })

  it('ringing 群音视频 → 群邀请文案', async () => {
    const wrapper = mountWindow()
    callStore.status = 'ringing'
    callStore.callType = 2
    callStore.mediaType = 2
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.call-title').text()).toBe('群通话邀请')
    expect(wrapper.find('.call-sub').text()).toBe('音视频通话')
  })

  it('来电三个入口 → 分别调用 store action', async () => {
    const wrapper = mountWindow()
    callStore.status = 'ringing'
    await new Promise((r) => setTimeout(r, 0))
    const reject = vi.spyOn(callStore, 'rejectCall').mockImplementation(() => {})
    const busy = vi.spyOn(callStore, 'busy').mockImplementation(() => {})
    const accept = vi.spyOn(callStore, 'acceptCall').mockImplementation(() => {})
    const btns = wrapper.findAll('.call-actions .el-button')
    await btns[0].trigger('click')
    await btns[1].trigger('click')
    await btns[2].trigger('click')
    expect(reject).toHaveBeenCalled()
    expect(busy).toHaveBeenCalled()
    expect(accept).toHaveBeenCalled()
  })

  it('connected → 通话中面板（无来电按钮）', async () => {
    const wrapper = mountWindow()
    callStore.status = 'connected'
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.call-active').exists()).toBe(true)
    expect(wrapper.find('.call-incoming').exists()).toBe(false)
  })

  it('通话标题：单聊「通话中」/ 群「群通话」', async () => {
    const wrapper = mountWindow()
    callStore.status = 'connected'
    callStore.callType = 1
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.call-header span').text()).toBe('通话中')
    callStore.callType = 2
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.call-header span').text()).toBe('群通话')
  })

  it('endReason 非空 → 展示在头部', async () => {
    const wrapper = mountWindow()
    callStore.status = 'connected'
    callStore.endReason = '对方忙线'
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.call-end-reason').text()).toBe('对方忙线')
  })

  it('静音/关摄像头 → 按钮文案与样式切换', async () => {
    const wrapper = mountWindow()
    callStore.status = 'connected'
    await new Promise((r) => setTimeout(r, 0))
    let btns = wrapper.findAll('.call-controls .el-button')
    expect(btns[0].text()).toBe('静音')
    expect(btns[0].attributes('data-type')).toBe('primary')
    expect(btns[1].text()).toBe('关摄像头')
    // 本地视频标签显示静音/关摄像头状态
    expect(wrapper.find('.video-cell .video-tag').text()).toBe('我')
    callStore.isMuted = true
    callStore.isCameraOff = true
    await new Promise((r) => setTimeout(r, 0))
    btns = wrapper.findAll('.call-controls .el-button')
    expect(btns[0].text()).toBe('取消静音')
    expect(btns[0].attributes('data-type')).toBe('info')
    expect(btns[1].text()).toBe('开摄像头')
    expect(wrapper.find('.video-cell .video-tag').text()).toBe('我·静音·关摄像头')
  })

  it('通话中三个控制入口 → 调用 store action', async () => {
    const wrapper = mountWindow()
    callStore.status = 'connected'
    await new Promise((r) => setTimeout(r, 0))
    const mute = vi.spyOn(callStore, 'toggleMute').mockImplementation(() => {})
    const camera = vi.spyOn(callStore, 'toggleCamera').mockImplementation(() => {})
    const hangup = vi.spyOn(callStore, 'hangup').mockImplementation(() => {})
    const btns = wrapper.findAll('.call-controls .el-button')
    await btns[0].trigger('click')
    await btns[1].trigger('click')
    await btns[2].trigger('click')
    expect(mute).toHaveBeenCalled()
    expect(camera).toHaveBeenCalled()
    expect(hangup).toHaveBeenCalled()
  })

  it('群通话 → 成员列表展示', async () => {
    const wrapper = mountWindow()
    callStore.status = 'connected'
    callStore.callType = 2
    callStore.members = ['U1', 'U2', 'U3']
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.call-members').text()).toBe('成员：U1、U2、U3')
  })

  it('remoteStreams → 远程视频格展示 uid', async () => {
    const wrapper = mountWindow()
    callStore.status = 'connected'
    callStore.remoteStreams = { U_a: {}, U_b: {} }
    await new Promise((r) => setTimeout(r, 0))
    // 本地一格 + 远程两格
    const cells = wrapper.findAll('.video-cell')
    expect(cells).toHaveLength(3)
    expect(cells[1].find('.video-tag').text()).toBe('U_a')
    expect(cells[2].find('.video-tag').text()).toBe('U_b')
  })

  it('localStream → 绑定到本地 video.srcObject', async () => {
    const wrapper = mountWindow()
    callStore.status = 'connected'
    callStore.localStream = { id: 'stream-1' }
    await new Promise((r) => setTimeout(r, 0))
    await new Promise((r) => setTimeout(r, 0))
    const video = wrapper.find('.video-cell video')
    expect(video.element.srcObject).toEqual({ id: 'stream-1' })
  })

  it('errorMsg 非空 → 展示错误', async () => {
    const wrapper = mountWindow()
    callStore.status = 'calling'
    callStore.errorMsg = '无法获取摄像头/麦克风权限'
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.call-error').text()).toBe('无法获取摄像头/麦克风权限')
  })
})
