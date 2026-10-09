import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Update from '@/views/Update.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { ipcCalls } from './setup'

/**
 * 更新面板的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. autoUpdate=true → 挂载即查版本；false → 不查
 *   2. 无新版本：auto 静默 / 手动 checkUpdate 弹 Confirm「已经是最新版本!」
 *   3. 有新版本：面板展示 + updateList 带序号
 *   4. startDownload 两分支：fileType=0 下载（ipc downloadUpdate + 进度条）、
 *      fileType=1 外链（ipc openUrl）
 *   5. updateDownloadCallback → 进度百分比（floor(loaded/size*100)）
 *   6. 100% → 「下载完成，准备安装」
 *   7. 取消 → 面板关闭
 */

const stubs = {
  'el-progress': {
    name: 'el-progress',
    props: ['percentage'],
    template: '<div class="el-progress" :data-percentage="percentage"></div>'
  }
}

let pinia
let request
let listeners
let confirmCalls

function makeRequest(handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountUpdate(props = {}, handler) {
  request = makeRequest(handler)
  const wrapper = mount(Update, {
    props: { autoUpdate: true, ...props },
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { checkVersion: '/checkVersion' },
          Utils: { size2Str: (n) => `${n}B` },
          Confirm: (opts) => { confirmCalls.push(opts) }
        }
      },
      plugins: [pinia]
    }
  })
  return wrapper
}

// 有新版本的默认 handler
function updateData(extra = {}) {
  return {
    code: 0,
    data: {
      id: 7,
      fileName: 'EasyChat-1.2.0.exe',
      fileType: 0,
      outerLink: 'https://example.com/download',
      size: 1000,
      updateList: ['修复若干问题', '新增语音消息'],
      ...extra
    }
  }
}

describe('Update.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    useUserInfoStore().setInfo({ userId: 'U_me' })
    listeners = {}
    confirmCalls = []
    ipcCalls.length = 0
    window.ipcRenderer = {
      on: (ch, cb) => { listeners[ch] = cb },
      send: (...args) => { ipcCalls.push({ channel: 'send', args }) },
      removeAllListeners: () => {}
    }
  })

  it('autoUpdate=true → 挂载即查版本', () => {
    mountUpdate({ autoUpdate: true }, () => updateData())
    expect(request.__calls__.some((c) => c.url === '/checkVersion')).toBe(true)
  })

  it('autoUpdate=false → 挂载不查版本', () => {
    mountUpdate({ autoUpdate: false }, () => updateData())
    expect(request.__calls__.length).toBe(0)
  })

  it('请求参数带 appVersion / token / uid', () => {
    localStorage.setItem('token', 'TK')
    mountUpdate({ autoUpdate: true }, () => updateData())
    const call = request.__calls__.find((c) => c.url === '/checkVersion')
    expect(call.params.uid).toBe('U_me')
    expect(call.params.token).toBe('TK')
    expect(call.params.appVersion).toBeTruthy()
  })

  it('无新版本 + 手动 checkUpdate → Confirm「已经是最新版本!」', async () => {
    const wrapper = mountUpdate({ autoUpdate: false }, () => ({ code: 0, data: null }))
    wrapper.vm.checkUpdate()
    await new Promise((r) => setTimeout(r, 0))
    expect(confirmCalls).toHaveLength(1)
    expect(confirmCalls[0].message).toBe('已经是最新版本!')
    expect(confirmCalls[0].showCancelBtn).toBe(false)
    // 不展示更新面板
    expect(wrapper.find('.update-panel').exists()).toBe(false)
  })

  it('无新版本 + 自动检查 → 静默（不弹 Confirm）', async () => {
    const wrapper = mountUpdate({ autoUpdate: true }, () => ({ code: 0, data: null }))
    await new Promise((r) => setTimeout(r, 0))
    expect(confirmCalls).toHaveLength(0)
    expect(wrapper.find('.update-panel').exists()).toBe(false)
  })

  it('有新版本 → 面板展示 + updateList 带序号', async () => {
    const wrapper = mountUpdate({ autoUpdate: true }, () => updateData())
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.update-panel').exists()).toBe(true)
    const items = wrapper.findAll('.update-list > div')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toBe('1、修复若干问题')
    expect(items[1].text()).toBe('2、新增语音消息')
  })

  it('fileType=0 → downloading + ipc downloadUpdate', async () => {
    const wrapper = mountUpdate({ autoUpdate: true }, () => updateData({ fileType: 0 }))
    await new Promise((r) => setTimeout(r, 0))
    // 下载前显示操作按钮
    expect(wrapper.find('.op-btn').exists()).toBe(true)
    await wrapper.find('.update').trigger('click')
    const call = ipcCalls.find((c) => c.args[0] === 'downloadUpdate')
    expect(call).toBeTruthy()
    expect(call.args[1]).toEqual({ id: 7, fileName: 'EasyChat-1.2.0.exe' })
    // 进入下载态，操作按钮换成进度区
    expect(wrapper.find('.op-btn').exists()).toBe(false)
    expect(wrapper.find('.el-progress').exists()).toBe(true)
  })

  it('fileType=1 → ipc openUrl 外链（不进下载态）', async () => {
    const wrapper = mountUpdate(
      { autoUpdate: true },
      () => updateData({ fileType: 1, outerLink: 'https://dl.example.com/x' })
    )
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.update').trigger('click')
    const call = ipcCalls.find((c) => c.args[0] === 'openUrl')
    expect(call).toBeTruthy()
    expect(call.args[1].url).toBe('https://dl.example.com/x')
    // 不进入下载态
    expect(wrapper.find('.el-progress').exists()).toBe(false)
  })

  it('updateDownloadCallback → 进度百分比与体积提示', async () => {
    const wrapper = mountUpdate({ autoUpdate: true }, () => updateData())
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.update').trigger('click')
    // 加载 250 / 1000 → floor(25%)
    listeners.updateDownloadCallback(null, 250)
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.el-progress').attributes('data-percentage')).toBe('25')
    // tips 展示 loaded/total（经 Utils.size2Str）
    expect(wrapper.find('.download-tips').text()).toContain('250B/1000B')
  })

  it('进度 100% → 「下载完成，准备安装」', async () => {
    const wrapper = mountUpdate({ autoUpdate: true }, () => updateData())
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.update').trigger('click')
    listeners.updateDownloadCallback(null, 1000)
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.el-progress').exists()).toBe(false)
    expect(wrapper.find('.download-progress').text()).toContain('下载完成，准备安装')
  })

  it('取消 → 面板关闭', async () => {
    const wrapper = mountUpdate({ autoUpdate: true }, () => updateData())
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.update-panel').exists()).toBe(true)
    await wrapper.find('.cancel').trigger('click')
    expect(wrapper.find('.update-panel').exists()).toBe(false)
  })
})
