import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ForwardSelect from '@/views/chat/ForwardSelect.vue'

/**
 * 转发选择器组件的**真实挂载**测试（DOM 级）。
 *
 * 为什么选它：
 *   1. 转发是聊天核心交互，选择结果错误会导致消息发到错误会话
 *   2. 打开时并发载入好友 + 群（两个 Request），合并渲染
 *   3. 有搜索过滤、确定/取消、回调传值等交互逻辑
 *   4. 与已测组件不同族（转发选择器 vs 对话框/选择器/位置消息）
 *
 * 测试模式参照 chat-message-voice-mount.spec.js：
 *   mount + globalProperties（Request/Api/Message）+ 组件 stub
 */

const MessageStub = { error: vi.fn(), warning: vi.fn(), success: vi.fn() }

/** 可控的 Request 桩：按 url 返回预设结果，并记录调用 */
function makeRequestStub(handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const dialogStub = {
  template: `<div v-if="show" class="dialog">
    <div class="dialog__title">{{ title }}</div>
    <button class="dialog__close" @click="$emit('close')">x</button>
    <slot />
    <div class="dialog__buttons">
      <button v-for="btn in buttons" :key="btn.text"
        :class="['el-button', btn.type ? 'el-button--' + btn.type : '']"
        @click="btn.click">{{ btn.text }}</button>
    </div>
  </div>`,
  props: ['show', 'title', 'buttons', 'width'],
  emits: ['close']
}

const transferStub = {
  template: `<div class="el-transfer">
    <div v-for="item in data" :key="item.contactId" class="el-transfer-item"
      @click="$emit('update:modelValue', [item.contactId])">
      <slot :option="item" />
    </div>
  </div>`,
  props: ['modelValue', 'data', 'titles', 'filterable', 'filterMethod', 'format', 'props'],
  emits: ['update:modelValue']
}

function mountForward(requestHandler) {
  const request = makeRequestStub(requestHandler)
  const wrapper = mount(ForwardSelect, {
    global: {
      stubs: {
        Dialog: dialogStub,
        AvatarBase: {
          template: '<div class="avatar-base"></div>',
          props: ['userId', 'width', 'borderRadius', 'showDetail']
        },
        'el-transfer': transferStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { loadContact: '/contact/loadContact' },
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper, request }
}

/** 等待异步链完成（show → loadContact → Request → 渲染） */
async function flush() {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

describe('ForwardSelect.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    MessageStub.warning.mockClear()
    MessageStub.error.mockClear()
    MessageStub.success.mockClear()
  })

  it('挂载后对话框初始隐藏', () => {
    const { wrapper } = mountForward()
    expect(wrapper.find('.dialog').exists()).toBe(false)
  })

  it('打开时并发载入好友 + 群并渲染选项', async () => {
    const { wrapper, request } = mountForward((opts) => {
      if (opts.params && opts.params.contactType === 'USER') {
        return { code: 0, data: [{ contactId: 'U_f1', contactName: '好友1' }] }
      }
      return { code: 0, data: [{ contactId: 'G001', contactName: '群1' }] }
    })
    await wrapper.vm.show(() => {})
    await flush()
    // 并发两个 loadContact 请求（USER + GROUP）
    const loads = request.__calls__.filter((c) => c.url === '/contact/loadContact')
    expect(loads.length).toBe(2)
    // 渲染出选项
    expect(wrapper.findAll('.el-transfer-item').length).toBe(2)
    expect(wrapper.text()).toContain('好友1')
    expect(wrapper.text()).toContain('群1')
  })

  it('确定时调用 callback 并传入选中的对象', async () => {
    const { wrapper } = mountForward((opts) => {
      if (opts.params && opts.params.contactType === 'USER') {
        return { code: 0, data: [{ contactId: 'U_f1', contactName: '好友1' }] }
      }
      return { code: 0, data: [{ contactId: 'G001', contactName: '群1' }] }
    })
    let callbackResult = null
    await wrapper.vm.show((selected) => { callbackResult = selected })
    await flush()
    // 模拟选择：点击 el-transfer-item 触发 update:modelValue
    await wrapper.find('.el-transfer-item').trigger('click')
    // 点击确定
    await wrapper.find('.dialog .el-button--primary').trigger('click')
    expect(callbackResult).toBeTruthy()
    expect(callbackResult.length).toBe(1)
    expect(callbackResult[0].contactId).toBe('U_f1')
    // 关闭对话框
    expect(wrapper.find('.dialog').exists()).toBe(false)
  })

  it('取消时关闭对话框且不调用 callback', async () => {
    const { wrapper } = mountForward(() => ({ code: 0, data: [] }))
    let callbackCalled = false
    await wrapper.vm.show(() => { callbackCalled = true })
    await flush()
    // 点击关闭按钮
    await wrapper.find('.dialog__close').trigger('click')
    expect(callbackCalled).toBe(false)
    expect(wrapper.find('.dialog').exists()).toBe(false)
  })

  it('未选择时点击确定提示「请选择转发的会话」', async () => {
    const { wrapper } = mountForward(() => ({ code: 0, data: [] }))
    await wrapper.vm.show(() => {})
    await flush()
    await wrapper.find('.dialog .el-button--primary').trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请选择转发的会话')
    // 对话框仍打开
    expect(wrapper.find('.dialog').exists()).toBe(true)
  })
})
