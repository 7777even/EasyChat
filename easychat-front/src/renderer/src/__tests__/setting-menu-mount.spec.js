import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import Setting from '@/views/setting/Setting.vue'

/**
 * 设置外壳（左侧菜单 + 右侧 router-view）的**真实挂载**测试（DOM 级，真实 router）。
 *
 * 被测点：
 *   1. 菜单渲染：6 项、名称与 iconclass、每项带 bgColor 内联样式
 *   2. menu-active 跟随当前路由（route.path == item.path）
 *   3. jump → router.push(item.path)（点击「隐私」跳 /setting/privacy）
 *   4. 右侧 router-view 真实渲染当前路由组件（外壳不吞子页面）
 */

const LayoutStub = {
  name: 'Layout',
  template:
    '<div class="layout-stub"><div class="left"><slot name="left-content" /></div><div class="right"><slot name="right-content" /></div></div>'
}

const MENU_NAMES = ['账号设置', '文件管理', '数据备份', '我的收藏', '隐私', '关于EasyChat']
const MENU_PATHS = [
  '/setting/userInfo',
  '/setting/fileManage',
  '/setting/dataBackup',
  '/setting/favorite',
  '/setting/privacy',
  '/setting/about'
]

async function mountSetting (startPath = '/setting/fileManage') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      ...MENU_PATHS.map((p) => ({ path: p, component: { template: '<div class="child-page">子页面</div>' } })),
      { path: '/setting', component: { template: '<div />' } }
    ]
  })
  await router.push(startPath)
  await router.isReady()
  const wrapper = mount(Setting, {
    global: {
      plugins: [router],
      stubs: {
        Layout: LayoutStub,
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<div><slot /></div>' },
        'el-button': {
          template: '<button @click="$emit(\'click\')"><slot /></button>',
          emits: ['click']
        }
      }
    }
  })
  return { wrapper, router }
}

describe('Setting.vue 真实挂载（DOM 级）', () => {
  it('菜单渲染：6 项，名称与路径齐全，每项 icon 带 bgColor 内联样式', async () => {
    const { wrapper } = await mountSetting()
    const items = wrapper.findAll('.menu-item')
    expect(items).toHaveLength(6)
    expect(items.map((i) => i.find('.menu-name').text())).toEqual(MENU_NAMES)
    // icon 背景色走 :style 绑定（缺失时 style 属性不存在）
    for (const item of items) {
      expect(item.find('.iconfont').attributes('style')).toBeTruthy()
    }
  })

  it('menu-active 跟随当前路由：/setting/fileManage 时第二项高亮', async () => {
    const { wrapper } = await mountSetting('/setting/fileManage')
    const items = wrapper.findAll('.menu-item')
    const active = items.filter((i) => i.classes().includes('menu-active'))
    expect(active).toHaveLength(1)
    expect(active[0].find('.menu-name').text()).toBe('文件管理')
  })

  it('点击菜单项 → router.push 对应 path', async () => {
    const { wrapper, router } = await mountSetting()
    const push = vi.spyOn(router, 'push')
    await wrapper.findAll('.menu-item')[4].trigger('click')
    expect(push).toHaveBeenCalledWith('/setting/privacy')
  })

  it('右侧 router-view 真实渲染当前路由组件（外壳不吞子页面）', async () => {
    const { wrapper } = await mountSetting('/setting/favorite')
    expect(wrapper.find('.child-page').exists()).toBe(true)
    expect(wrapper.find('.left').exists()).toBe(true)
  })
})
