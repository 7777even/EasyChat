import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ContactPicker from '@/components/ContactPicker.vue'

/**
 * 联系人选择器组件的**真实挂载**测试（DOM 级）。
 *
 * 为什么选它：
 *   1. 通用选择器组件，有明确交互（载入好友/选择/确定/取消）
 *   2. 隐私设置（朋友圈白/黑名单）与发布朋友圈共用，选择结果错误会导致隐私泄露
 *   3. 与已测组件不同族（选择器 vs 对话框/媒体消息）
 *
 * 测试模式参照 group-join-dialog-mount.spec.js：
 *   mount + globalProperties（Request/Api）+ Element Plus 组件 stub
 */

/** 可控的 Request 桩：按 url 返回预设结果，并记录调用 */
function makeRequestStub (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountPicker (props = {}, handler) {
  const request = makeRequestStub(handler)
  const wrapper = mount(ContactPicker, {
    props: {
      modelValue: true,
      title: '选择联系人',
      selected: [],
      ...props
    },
    global: {
      stubs: {
        'el-dialog': {
          template: '<div v-if="modelValue" class="el-dialog"><div class="el-dialog__title">{{ title }}</div><slot /><slot name="footer" /></div>',
          props: ['modelValue', 'title', 'width', 'closeOnClickModal']
        },
        'el-transfer': {
          template: '<div class="el-transfer"><div v-for="item in data" :key="item.id" class="el-transfer-item">{{ item.name }}</div></div>',
          props: ['modelValue', 'data', 'titles', 'filterable', 'loading']
        },
        'el-button': {
          template: '<button :class="[\'el-button\', type ? \'el-button--\' + type : \'\']"><slot /></button>',
          props: ['type', 'loading']
        },
        AvatarBase: {
          template: '<div class="avatar-base"></div>',
          props: ['userId', 'width', 'borderRadius', 'showDetail']
        }
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            loadContact: '/contact/loadContact'
          }
        }
      }
    }
  })
  return { wrapper, request }
}

/** 等待异步链完成（watch → loadFriends → Request → 渲染） */
async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

describe('ContactPicker.vue 真实挂载（DOM 级）', () => {
  it('挂载后渲染对话框标题「选择联系人」', () => {
    const { wrapper } = mountPicker()
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('选择联系人')
  })

  it('打开时载入好友并渲染选项', async () => {
    const { wrapper, request } = mountPicker({}, () => ({
      code: 0,
      data: [
        { contactId: 'U_f1', contactName: '好友1' },
        { contactId: 'U_f2', contactName: '好友2' }
      ]
    }))
    await flush()
    const load = request.__calls__.find((c) => c.url === '/contact/loadContact')
    expect(load).toBeTruthy()
    expect(wrapper.findAll('.el-transfer-item').length).toBe(2)
    expect(wrapper.text()).toContain('好友1')
    expect(wrapper.text()).toContain('好友2')
  })

  it('空好友时显示「你还没有好友，无法选择」', async () => {
    const { wrapper } = mountPicker({}, () => ({ code: 0, data: [] }))
    await flush()
    expect(wrapper.find('.picker-empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('你还没有好友，无法选择')
  })

  it('确定时 emit confirm', async () => {
    const { wrapper } = mountPicker()
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    expect(wrapper.emitted('confirm')).toBeTruthy()
  })

  it('取消时不 emit confirm', async () => {
    const { wrapper } = mountPicker()
    await wrapper.find('.el-dialog .el-button').trigger('click')
    expect(wrapper.emitted('confirm')).toBeFalsy()
  })
})
