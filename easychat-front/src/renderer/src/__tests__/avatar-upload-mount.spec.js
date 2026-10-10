import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import AvatarUpload from '@/components/AvatarUpload.vue'
import { ipcCalls } from './setup'

/**
 * 头像上传组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. modelValue=null → 渲染添加按钮（el-upload）
 *   2. modelValue 字符串 → ShowLocalImage
 *   3. modelValue 是 File → el-image 本地预览
 *   4. uploadImage → ipc createCover（带 file.path）
 *   5. createCoverCallback → emit('coverFile', {avatarFile, coverFile})
 *   6. clear() → localFile 清空（不抛异常）
 */

const stubs = {
  ShowLocalImage: {
    name: 'ShowLocalImage',
    template: '<div class="show-local-image"></div>',
    props: ['width', 'fileId', 'partType', 'forceGet', 'showPlay']
  },
  'el-upload': {
    name: 'el-upload',
    template: '<div class="el-upload"><slot /></div>',
    props: ['name', 'showFileList', 'accept', 'multiple', 'httpRequest', 'fileList']
  },
  'el-image': {
    name: 'el-image',
    template: '<div class="el-image"></div>',
    props: ['src', 'fit']
  },
  'el-button': {
    name: 'el-button',
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`,
    props: ['type', 'size']
  }
}

let router
let listeners

function mountAvatarUpload(props = {}) {
  const wrapper = mount(AvatarUpload, {
    props: { modelValue: null, ...props },
    global: { stubs, plugins: [router] }
  })
  return wrapper
}

describe('AvatarUpload.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    router = createRouter({ history: createMemoryHistory(), routes: [] })
    listeners = {}
    ipcCalls.length = 0
    // 覆盖 setup.js 的只读 Proxy 桩，使其可记录监听器
    window.ipcRenderer = {
      on: (ch, cb) => { listeners[ch] = cb },
      send: (...args) => { ipcCalls.push({ channel: 'send', args }) },
      removeAllListeners: () => { listeners = {} }
    }
  })

  it('modelValue=null → 渲染 el-upload 添加按钮', () => {
    const wrapper = mountAvatarUpload()
    expect(wrapper.find('.el-upload').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'ShowLocalImage' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'el-image' }).exists()).toBe(false)
  })

  it('modelValue 字符串 → ShowLocalImage', () => {
    const wrapper = mountAvatarUpload({ modelValue: 'U_x' })
    const img = wrapper.findComponent({ name: 'ShowLocalImage' })
    expect(img.exists()).toBe(true)
    expect(img.props('fileId')).toBe('U_x')
    expect(img.props('partType')).toBe('avatar')
  })

  it('modelValue 是 File → el-image 本地预览', () => {
    const file = new File(['x'], 'a.png', { type: 'image/png' })
    const wrapper = mountAvatarUpload({ modelValue: file })
    expect(wrapper.findComponent({ name: 'el-image' }).exists()).toBe(true)
  })

  it('uploadImage → ipc createCover', () => {
    const wrapper = mountAvatarUpload()
    const upload = wrapper.findComponent({ name: 'el-upload' })
    const httpRequest = upload.props('httpRequest')
    expect(typeof httpRequest).toBe('function')
    httpRequest({ file: { path: '/tmp/avatar.png' } })
    const call = ipcCalls.find((c) => c.args[0] === 'createCover')
    expect(call).toBeTruthy()
    expect(call.args[1]).toBe('/tmp/avatar.png')
  })

  it('createCoverCallback → emit coverFile 带 avatarFile/coverFile', async () => {
    const wrapper = mountAvatarUpload()
    // onMounted 注册了回调
    expect(listeners.createCoverCallback).toBeTruthy()
    listeners.createCoverCallback({}, {
      avatarStream: [1, 2, 3],
      coverStream: [4, 5, 6]
    })
    // FileReader 异步
    await new Promise((r) => setTimeout(r, 0))
    await new Promise((r) => setTimeout(r, 0))
    const emitted = wrapper.emitted('coverFile')
    expect(emitted).toBeTruthy()
    const payload = emitted[0][0]
    expect(payload.avatarFile).toBeTruthy()
    expect(payload.coverFile).toBeTruthy()
    expect(payload.coverFile.name).toBe('thumbnail.jpg')
  })

  it('clear() → 不抛异常且暴露', () => {
    const wrapper = mountAvatarUpload({ modelValue: 'U_x' })
    expect(typeof wrapper.vm.clear).toBe('function')
    wrapper.vm.clear()
  })
})
