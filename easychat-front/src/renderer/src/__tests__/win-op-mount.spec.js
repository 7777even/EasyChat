import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import WinOp from '@/components/WinOp.vue'
import { ipcCalls } from './setup'

/**
 * 窗口操作按钮组的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 四个按钮默认渲染 + 置顶按钮的 win-top 态随点击切换
 *   2. close → ipc winTitleOp(close, closeType) + emit closeCallback
 *   3. minimize → ipc winTitleOp(minimize)
 *   4. maximize → 图标与 ipc 动作在 maximize/unmaximize 间切换
 *   5. top → ipc winTitleOp(top, {top}) 布尔随点击翻转
 *   6. showX=false → 对应按钮隐藏
 */

function mountWinOp(props = {}) {
  return mount(WinOp, { props: { ...props } })
}

describe('WinOp.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    ipcCalls.length = 0
  })

  it('四个按钮默认渲染', () => {
    const wrapper = mountWinOp()
    expect(wrapper.find('.icon-top').exists()).toBe(true)
    expect(wrapper.find('.icon-min').exists()).toBe(true)
    expect(wrapper.find('.icon-max').exists()).toBe(true)
    expect(wrapper.find('.icon-close').exists()).toBe(true)
  })

  it('close → ipc close(closeType) + emit closeCallback', async () => {
    const wrapper = mountWinOp({ closeType: 0 })
    await wrapper.find('.icon-close').trigger('click')
    const call = ipcCalls.find((c) => c.args[0] === 'winTitleOp')
    expect(call).toBeTruthy()
    expect(call.args[1].action).toBe('close')
    expect(call.args[1].data.closeType).toBe(0)
    expect(wrapper.emitted('closeCallback')).toBeTruthy()
  })

  it('minimize → ipc minimize', async () => {
    const wrapper = mountWinOp()
    await wrapper.find('.icon-min').trigger('click')
    const call = ipcCalls.find((c) => c.args[0] === 'winTitleOp')
    expect(call.args[1].action).toBe('minimize')
  })

  it('maximize → 图标与 ipc 动作切换', async () => {
    const wrapper = mountWinOp()
    // 初始未最大化：icon-max
    const el = () => wrapper.find('.iconfont.icon-max, .iconfont.icon-maximize')
    expect(el().classes()).toContain('icon-max')
    await wrapper.find('.icon-max').trigger('click')
    expect(ipcCalls.some((c) => c.args[1] && c.args[1].action === 'maximize')).toBe(true)
    expect(el().classes()).toContain('icon-maximize')
    // 再点还原
    await wrapper.find('.icon-maximize').trigger('click')
    expect(ipcCalls.some((c) => c.args[1] && c.args[1].action === 'unmaximize')).toBe(true)
    expect(el().classes()).toContain('icon-max')
  })

  it('top → win-top class 与 ipc top 布尔翻转', async () => {
    const wrapper = mountWinOp()
    const topEl = wrapper.find('.icon-top')
    expect(topEl.classes()).not.toContain('win-top')
    await topEl.trigger('click')
    expect(topEl.classes()).toContain('win-top')
    const call1 = ipcCalls.filter((c) => c.args[0] === 'winTitleOp').pop()
    expect(call1.args[1].action).toBe('top')
    expect(call1.args[1].data.top).toBe(true)
    await topEl.trigger('click')
    expect(topEl.classes()).not.toContain('win-top')
    const call2 = ipcCalls.filter((c) => c.args[0] === 'winTitleOp').pop()
    expect(call2.args[1].data.top).toBe(false)
  })

  it('showX=false → 对应按钮隐藏（可组合）', () => {
    const wrapper = mountWinOp({ showSetTop: false, showMin: false })
    expect(wrapper.find('.icon-top').exists()).toBe(false)
    expect(wrapper.find('.icon-min').exists()).toBe(false)
    expect(wrapper.find('.icon-max').exists()).toBe(true)
    expect(wrapper.find('.icon-close').exists()).toBe(true)
  })

  it('.win-op 容器渲染', () => {
    const wrapper = mountWinOp()
    expect(wrapper.find('.win-op').exists()).toBe(true)
  })
})
