import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'

// ShowLocalImage 依赖 GlobalInfoStore 取 localServerPort，mock 掉避免 pinia 依赖
vi.mock('@/stores/GlobalInfoStore', () => ({
  useGlobalInfoStore: () => ({
    getInfo: (key) => (key === 'localServerPort' ? 5050 : undefined)
  })
}))

import ShowLocalImage from '@/components/ShowLocalImage.vue'

/**
 * 本地图片预览组件的**真实挂载**测试（DOM 级）。
 *
 * 修复缺陷：模板 `@click="showImageHandler"` 但 showImageHandler 未定义
 * （全前端 grep 确认仅模板一处引用），点击抛 TypeError。
 * 修复后 showImageHandler emit `click`，由父组件决定跳转行为。
 */

function mountImage (props = {}) {
  return mount(ShowLocalImage, {
    props: {
      fileId: '1001',
      partType: 'chat',
      fileType: 0,
      ...props
    },
    global: {
      stubs: {
        'el-image': {
          template: '<img :src="src" />',
          props: ['src', 'fit', 'width']
        }
      }
    }
  })
}

describe('ShowLocalImage.vue 真实挂载（DOM 级）', () => {
  it('挂载后渲染 <img> 且 src 指向本地文件服务器', () => {
    const wrapper = mountImage()
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    const src = img.attributes('src') || ''
    expect(src).toContain('fileId=1001')
    expect(src).toContain('partType=chat')
    expect(src).toContain('fileType=0')
    expect(src).toContain('localhost:5050')
  })

  it('无 fileId 时 src 为空（不生成坏 URL）', () => {
    const wrapper = mountImage({ fileId: null })
    // serverUrl 返回 undefined，el-image stub 的 src prop 为 undefined
    expect(wrapper.find('img').attributes('src')).toBeUndefined()
  })

  it('fileId 为 Urobot 时 forceGet 为 true（机器人头像强制刷新）', () => {
    const wrapper = mountImage({ fileId: 'Urobot' })
    const src = wrapper.find('img').attributes('src') || ''
    expect(src).toContain('forceGet=true')
  })

  it('forceGet prop 为 true 时 URL 带 forceGet=true', () => {
    const wrapper = mountImage({ forceGet: true })
    const src = wrapper.find('img').attributes('src') || ''
    expect(src).toContain('forceGet=true')
  })

  it('点击 image-panel 触发 click 事件（修复 showImageHandler 未定义）', async () => {
    const wrapper = mountImage()
    await wrapper.find('.image-panel').trigger('click')
    console.log('EMITTED:', JSON.stringify(wrapper.emitted()))
    expect(wrapper.emitted('click')).toBeTruthy()
  })

  it('showPlay 为 true 时渲染播放按钮', () => {
    const wrapper = mountImage({ showPlay: true })
    expect(wrapper.find('.play-panel').exists()).toBe(true)
  })
})
