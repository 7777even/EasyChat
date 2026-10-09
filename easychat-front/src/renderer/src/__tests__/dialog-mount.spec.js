import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Dialog from '@/components/Dialog.vue'

/**
 * 通用对话框组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. show 传递 → el-dialog 收到 modelValue（常驻渲染 stub，不依赖
 *      stub 内部 v-if 的 prop 匹配，避免 jsdom 侧误判）
 *   2. showCancel 默认 true → 取消按钮 → 点它 emit close
 *   3. buttons → 渲染 + btn.click 被调用
 *   4. showCancel=false 且无 buttons → 不渲染 footer
 *   5. slot 内容渲染到 dialog-body
 */

const stubs = {
  'el-dialog': {
    name: 'el-dialog',
    props: ['showClose', 'draggable', 'modelValue', 'closeOnClickModal', 'title', 'top', 'width'],
    emits: ['close'],
    template: `<div class="el-dialog">
      <div class="dialog-body"><slot /></div>
      <button class="dialog-x" @click="$emit('close')">x</button>
    </div>`
  },
  'el-button': {
    name: 'el-button',
    props: ['type', 'link'],
    emits: ['click'],
    template: `<button class="el-button" :data-type="type" @click="$emit('click')"><slot /></button>`
  }
}

function mountDialog(props = {}, slots = {}) {
  return mount(Dialog, { props: { show: true, ...props }, global: { stubs }, slots })
}

describe('Dialog.vue 真实挂载（DOM 级）', () => {
  it('show → 传递给 el-dialog', () => {
    const wrapper = mountDialog({ show: true })
    expect(wrapper.findComponent({ name: 'el-dialog' }).props('modelValue')).toBe(true)
    const closed = mountDialog({ show: false })
    expect(closed.findComponent({ name: 'el-dialog' }).props('modelValue')).toBe(false)
  })

  it('showCancel 默认 true → 取消按钮 emit close', async () => {
    const wrapper = mountDialog()
    const cancel = wrapper.findAll('.el-button').find((b) => b.text().includes('取消'))
    expect(cancel, '默认应渲染取消按钮').toBeTruthy()
    await cancel.trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('buttons → 渲染且点击调用 btn.click', async () => {
    const click = vi.fn()
    const wrapper = mountDialog({ buttons: [{ type: 'primary', text: '确定', click }] })
    const ok = wrapper.findAll('.el-button').find((b) => b.text() === '确定')
    expect(ok).toBeTruthy()
    expect(ok.attributes('data-type')).toBe('primary')
    await ok.trigger('click')
    expect(click).toHaveBeenCalled()
  })

  it('showCancel=false 且无 buttons → 不渲染 footer', () => {
    const wrapper = mountDialog({ showCancel: false })
    expect(wrapper.find('.dialog-footer').exists()).toBe(false)
  })

  it('slot 内容渲染到 dialog-body', () => {
    const wrapper = mountDialog({}, { default: '<div class="body-slot">B</div>' })
    expect(wrapper.find('.dialog-body .body-slot').exists()).toBe(true)
  })
})
