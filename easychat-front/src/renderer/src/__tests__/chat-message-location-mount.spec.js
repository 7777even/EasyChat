import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessageLocation from '@/views/chat/ChatMessageLocation.vue'

/**
 * 位置消息组件的**真实挂载**测试（DOM 级）。
 *
 * 为什么选它：
 *   1. 位置消息是聊天核心交互，点击气泡打开详情、在地图中打开
 *   2. extraData 解析有兼容逻辑（JSON 对象 vs 纯字符串 vs 非法 JSON）
 *   3. 与已测组件不同族（位置消息 vs 对话框/选择器/媒体消息）
 *
 * 测试模式参照 contact-picker-mount.spec.js：
 *   mount + globalProperties（Message）+ Element Plus 组件 stub
 */

const MessageStub = { error: vi.fn(), warning: vi.fn(), success: vi.fn() }

function mountLocation (data = {}) {
  const wrapper = mount(ChatMessageLocation, {
    props: { data },
    global: {
      stubs: {
        'el-dialog': {
          template: '<div v-if="modelValue" class="el-dialog"><div class="el-dialog__title">{{ title }}</div><slot /><slot name="footer" /></div>',
          props: ['modelValue', 'title', 'width', 'appendToBody']
        },
        'el-button': {
          template: '<button :class="[\'el-button\', type ? \'el-button--\' + type : \'\']" :disabled="disabled"><slot /></button>',
          props: ['type', 'disabled', 'loading']
        }
      },
      config: {
        globalProperties: {
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper }
}

describe('ChatMessageLocation.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    MessageStub.warning.mockClear()
  })

  it('挂载后渲染位置气泡（位置名）', () => {
    const { wrapper } = mountLocation({
      extraData: JSON.stringify({ location: '北京市朝阳区', latitude: 39.9042, longitude: 116.4074 })
    })
    expect(wrapper.find('.location-bubble').exists()).toBe(true)
    expect(wrapper.text()).toContain('北京市朝阳区')
  })

  it('有经纬度时显示坐标', () => {
    const { wrapper } = mountLocation({
      extraData: JSON.stringify({ location: '北京市朝阳区', latitude: 39.9042, longitude: 116.4074 })
    })
    expect(wrapper.find('.location-coord').exists()).toBe(true)
    expect(wrapper.text()).toContain('39.90420, 116.40740')
  })

  it('无经纬度时不显示坐标', () => {
    const { wrapper } = mountLocation({
      extraData: JSON.stringify({ location: '北京市朝阳区' })
    })
    expect(wrapper.find('.location-coord').exists()).toBe(false)
  })

  it('点击气泡打开详情对话框', async () => {
    const { wrapper } = mountLocation({
      extraData: JSON.stringify({ location: '北京市朝阳区', latitude: 39.9042, longitude: 116.4074 })
    })
    await wrapper.find('.location-bubble').trigger('click')
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('位置详情')
  })

  it('详情对话框显示位置名和经纬度', async () => {
    const { wrapper } = mountLocation({
      extraData: JSON.stringify({ location: '北京市朝阳区', latitude: 39.9042, longitude: 116.4074 })
    })
    await wrapper.find('.location-bubble').trigger('click')
    expect(wrapper.text()).toContain('北京市朝阳区')
    expect(wrapper.text()).toContain('经度：116.4074')
    expect(wrapper.text()).toContain('纬度：39.9042')
  })

  it('在地图中打开：有经纬度时调 window.ipcRenderer.send', async () => {
    const { wrapper } = mountLocation({
      extraData: JSON.stringify({ location: '北京市朝阳区', latitude: 39.9042, longitude: 116.4074 })
    })
    await wrapper.find('.location-bubble').trigger('click')
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    const sendCalls = window.ipcRenderer.__calls__.filter(
      (c) => c.channel === 'send' && c.args[0] === 'openUrl'
    )
    expect(sendCalls.length).toBe(1)
    expect(sendCalls[0].args[1].url).toContain('uri.amap.com/marker')
    expect(sendCalls[0].args[1].url).toContain('116.4074,39.9042')
  })

  it('在地图中打开：无经纬度时按钮禁用', async () => {
    const { wrapper } = mountLocation({
      extraData: JSON.stringify({ location: '北京市朝阳区' })
    })
    await wrapper.find('.location-bubble').trigger('click')
    const btn = wrapper.find('.el-dialog .el-button--primary')
    expect(btn.attributes('disabled')).toBeDefined()
  })
})
