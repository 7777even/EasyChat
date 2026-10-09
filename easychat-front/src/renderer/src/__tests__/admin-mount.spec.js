import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import Admin from '@/views/admin/Admin.vue'
import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'
import { ipcCalls } from './setup'

/**
 * 管理后台外壳的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 8 个管理菜单渲染
 *   2. menuJump：命名路由跳转；同路由不重复跳
 *   3. 命名路由失败（非 NavigationDuplicated）→ 回退路径路由；
 *      NavigationDuplicated → 不回退（避免多余导航）
 *   4. tokenReady 门控：localStorage 有 token → router-view 渲染；
 *      无 token → 不渲染（防止未登录看到后台内容）
 *   5. pageInitData 回调：token 落库并开门 + localServerPort 入全局 store
 */

const stubs = {
  WinOp: { name: 'WinOp', props: ['showSetTop'], template: '<div class="win-op" />' }
}

let pinia
let router
let listeners

function mountAdmin() {
  const wrapper = mount(Admin, {
    global: { stubs, plugins: [pinia, router] }
  })
  return wrapper
}

describe('Admin.vue 真实挂载（DOM 级）', () => {
  beforeEach(async () => {
    pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/admin', component: { template: '<div class="v-admin" />' } },
        { path: '/admin/userList', name: '用户管理', component: { template: '<div class="v-userList" />' } },
        { path: '/admin/beautyAccount', name: '靓号管理', component: { template: '<div class="v-beauty" />' } },
        { path: '/admin/groupList', name: '群组管理', component: { template: '<div class="v-group" />' } },
        { path: '/admin/sysSetting', name: '系统设置', component: { template: '<div class="v-sys" />' } },
        { path: '/admin/update', name: '版本管理', component: { template: '<div class="v-update" />' } },
        { path: '/admin/reportList', name: '举报管理', component: { template: '<div class="v-report" />' } },
        { path: '/admin/sensitiveWord', name: '敏感词管理', component: { template: '<div class="v-word" />' } },
        { path: '/admin/callLog', name: '通话记录', component: { template: '<div class="v-callLog" />' } }
      ]
    })
    await router.push('/admin')
    await router.isReady()
    listeners = {}
    ipcCalls.length = 0
    localStorage.clear()
    window.ipcRenderer = {
      on: (ch, cb) => { listeners[ch] = cb },
      send: (...args) => { ipcCalls.push({ channel: 'send', args }) },
      removeAllListeners: () => {}
    }
  })

  it('8 个管理菜单渲染', () => {
    const wrapper = mountAdmin()
    const items = wrapper.findAll('.menu-item')
    expect(items).toHaveLength(8)
    const texts = items.map((i) => i.find('.text').text())
    expect(texts).toContain('用户管理')
    expect(texts).toContain('敏感词管理')
    expect(texts).toContain('通话记录')
  })

  it('menuJump → 命名路由跳转', async () => {
    const push = vi.spyOn(router, 'push')
    const wrapper = mountAdmin()
    const target = wrapper.findAll('.menu-item').find((i) => i.find('.text').text() === '群组管理')
    await target.trigger('click')
    expect(push).toHaveBeenCalledWith({ name: '群组管理' })
  })

  it('当前路由已是目标 → 不跳转', async () => {
    await router.push({ name: '群组管理' })
    await router.isReady()
    const push = vi.spyOn(router, 'push')
    const wrapper = mountAdmin()
    const target = wrapper.findAll('.menu-item').find((i) => i.find('.text').text() === '群组管理')
    await target.trigger('click')
    expect(push).not.toHaveBeenCalled()
  })

  it('命名路由失败（非 NavigationDuplicated）→ 回退路径路由', async () => {
    const push = vi
      .spyOn(router, 'push')
      .mockRejectedValueOnce({ name: 'ResolutionError' })
      .mockResolvedValueOnce(undefined)
    const wrapper = mountAdmin()
    const target = wrapper.findAll('.menu-item').find((i) => i.find('.text').text() === '系统设置')
    await target.trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(push).toHaveBeenCalledWith({ name: '系统设置' })
    expect(push).toHaveBeenCalledWith('/admin/sysSetting')
  })

  it('命名路由失败（NavigationDuplicated）→ 不回退', async () => {
    const push = vi
      .spyOn(router, 'push')
      .mockRejectedValueOnce({ name: 'NavigationDuplicated' })
    const wrapper = mountAdmin()
    const target = wrapper.findAll('.menu-item').find((i) => i.find('.text').text() === '版本管理')
    await target.trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith({ name: '版本管理' })
  })

  it('无 token → router-view 不渲染', () => {
    const wrapper = mountAdmin()
    expect(wrapper.find('.right-content .v-userList').exists()).toBe(false)
    expect(wrapper.vm.tokenReady).toBe(false)
  })

  it('localStorage 已有 token → tokenReady 开门', async () => {
    localStorage.setItem('token', 'TK')
    const wrapper = mountAdmin()
    await router.push({ name: '用户管理' })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.v-userList').exists()).toBe(true)
  })

  it('pageInitData 带 token → 落库 + 开门', async () => {
    const wrapper = mountAdmin()
    expect(wrapper.vm.tokenReady).toBe(false)
    listeners.pageInitData(null, { token: 'TK_FROM_MAIN' })
    await new Promise((r) => setTimeout(r, 0))
    expect(localStorage.getItem('token')).toBe('TK_FROM_MAIN')
    await router.push({ name: '用户管理' })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.v-userList').exists()).toBe(true)
  })

  it('pageInitData 带 localServerPort → 写入全局 store', async () => {
    mountAdmin()
    listeners.pageInitData(null, { localServerPort: 18099 })
    expect(useGlobalInfoStore().getInfo('localServerPort')).toBe(18099)
  })
})
