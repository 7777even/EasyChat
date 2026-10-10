import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import App from '@/App.vue'

/**
 * 根组件 App.vue 的**真实挂载**测试（DOM 级，真实 router）。
 *
 * 被测点：
 *   1. el-config-provider 收到中文 locale（element-plus 官方 zh-cn，非默认英文）
 *   2. message 配置 max=1（同一时刻只弹一条，避免错误提示互相顶掉）
 *   3. router-view 真实渲染当前路由组件（根组件只是壳，不吞页面）
 */

const ConfigProviderStub = {
  name: 'ElConfigProvider',
  props: ['locale', 'message', 'button', 'namespace'],
  template: '<div class="cp-stub"><slot /></div>'
}

async function mountApp (path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div class="login-page">登录页</div>' } },
      { path: '/chat', component: { template: '<div class="chat-page">聊天页</div>' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, {
    global: {
      plugins: [router],
      stubs: { 'el-config-provider': ConfigProviderStub }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper }
}

describe('App.vue 真实挂载（DOM 级）', () => {
  it('locale 取 element-plus 中文包（非默认英文）', async () => {
    const { wrapper } = await mountApp()
    const cp = wrapper.findComponent({ name: 'ElConfigProvider' })
    expect(cp.exists()).toBe(true)
    expect(cp.props('locale')).toBe(zhCn)
  })

  it('message 配置 max=1（同时只弹一条提示）', async () => {
    const { wrapper } = await mountApp()
    const cp = wrapper.findComponent({ name: 'ElConfigProvider' })
    expect(cp.props('message')).toEqual({ max: 1 })
  })

  it('router-view 真实渲染当前路由组件', async () => {
    const { wrapper } = await mountApp('/chat')
    expect(wrapper.find('.chat-page').exists()).toBe(true)
    expect(wrapper.find('.login-page').exists()).toBe(false)
  })

  it('切换路由后根组件随之更新', async () => {
    const { wrapper } = await mountApp('/')
    expect(wrapper.find('.login-page').exists()).toBe(true)
    // router.push 是异步的：须 await 其本身，再等一次渲染（不能只靠 nextTick）
    await wrapper.vm.$router.push('/chat')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.chat-page').exists()).toBe(true)
  })
})
