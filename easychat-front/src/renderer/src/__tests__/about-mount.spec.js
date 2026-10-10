import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import About from '@/views/setting/About.vue'
import pkg from '../../../../package.json'

/**
 * 「关于 EasyChat」页的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 版本信息取自 package.json（版本号随包而非硬编码）
 *   2. 点击「检查更新」→ 委托 Update 实例的 checkUpdate()
 */

const checkUpdate = vi.fn()

const UpdateStub = {
  name: 'Update',
  props: ['autoUpdate'],
  setup (_, { expose }) {
    expose({ checkUpdate })
    return {}
  },
  template: '<div class="update-stub" />'
}
const ContentPanelStub = {
  name: 'ContentPanel',
  props: ['showTopBorder'],
  template: '<div class="content-panel-stub"><slot /></div>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'link'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}

async function mountAbout () {
  const wrapper = mount(About, {
    global: {
      stubs: {
        ContentPanel: ContentPanelStub,
        Update: UpdateStub,
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<div class="form-item-stub"><slot /></div>' },
        'el-button': ElButtonStub
      }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper }
}

describe('About.vue 真实挂载（DOM 级）', () => {
  it('版本信息取自 package.json', async () => {
    const { wrapper } = await mountAbout()
    expect(wrapper.text()).toContain(`EasyChat ${pkg.version}`)
    expect(pkg.version).toBeTruthy()
  })

  it('点击「检查更新」→ 委托 Update 实例 checkUpdate()', async () => {
    checkUpdate.mockClear()
    const { wrapper } = await mountAbout()
    const btn = wrapper.findAll('.el-button').find((b) => b.text() === '检查更新')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    expect(checkUpdate).toHaveBeenCalledTimes(1)
  })
})
