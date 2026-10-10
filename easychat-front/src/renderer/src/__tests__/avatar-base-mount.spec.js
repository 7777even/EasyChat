import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AvatarBase from '@/components/AvatarBase.vue'
import { useAvatarInfoStore } from '@/stores/AvatarUpdateStore'
import { ipcCalls } from './setup'

/**
 * 头像基础组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点（showDetailHandler 守卫）：
 *   1. showDetail=false → 点击无副作用（不 ipc、不 setFoceReload）
 *   2. showDetail=true → 点击后 setFoceReload(userId,false) + ipc newWindow
 *   3. ShowLocalImage 渲染 + partType="avatar"
 */

const stubs = {
  ShowLocalImage: {
    name: 'ShowLocalImage',
    template: '<div class="show-local-image"></div>',
    props: ['width', 'fileId', 'partType', 'forceGet', 'showPlay']
  }
}

let pinia
let avatarInfoStore

function mountAvatarBase(props = {}) {
  const wrapper = mount(AvatarBase, {
    props: { userId: 'U_x', width: 40, borderRadius: 0, showDetail: false, ...props },
    global: { stubs, plugins: [pinia] }
  })
  return wrapper
}

describe('AvatarBase.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    avatarInfoStore = useAvatarInfoStore()
    ipcCalls.length = 0
  })

  it('挂载后渲染 ShowLocalImage（partType=avatar）', () => {
    const wrapper = mountAvatarBase()
    const img = wrapper.findComponent({ name: 'ShowLocalImage' })
    expect(img.exists()).toBe(true)
    expect(img.props('partType')).toBe('avatar')
    expect(img.props('fileId')).toBe('U_x')
  })

  it('showDetail=false → 点击无 ipc 调用', async () => {
    const wrapper = mountAvatarBase({ showDetail: false })
    await wrapper.find('.user-avatar').trigger('click')
    expect(ipcCalls.length).toBe(0)
  })

  it('showDetail=true → 点击发 newWindow 且清 store 标记', async () => {
    // 预置强制更新标记为true
    avatarInfoStore.setFoceReload('U_x', true)
    const wrapper = mountAvatarBase({ showDetail: true })
    await wrapper.find('.user-avatar').trigger('click')
    // store 标记被清
    expect(avatarInfoStore.getFoceReload('U_x')).toBe(false)
    // ipc newWindow 被调用
    const newWindow = ipcCalls.find((c) => c.args[0] === 'newWindow')
    expect(newWindow).toBeTruthy()
    expect(newWindow.args[1].path).toBe('/showMedia')
    const fileList = newWindow.args[1].data.fileList
    expect(fileList[0].fileId).toBe('U_x')
    expect(fileList[0].partType).toBe('avatar')
    expect(fileList[0].forceGet).toBe(true)
  })

  it('showDetail=true 但标记已为false → 点击仍正常开窗（幂等）', async () => {
    avatarInfoStore.setFoceReload('U_x', false)
    const wrapper = mountAvatarBase({ showDetail: true })
    await wrapper.find('.user-avatar').trigger('click')
    expect(ipcCalls.find((c) => c.args[0] === 'newWindow')).toBeTruthy()
  })
})
