import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UserBaseInfo from '@/components/UserBaseInfo.vue'

/**
 * 用户基础信息组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. userId 优先，contactId 回退（ID 行与头像同源）
 *   2. 性别图标：sex=0 女 / sex=1 男 / 其他无
 *   3. showArea → proxy.Utils.getAreaInfo 调用
 */

const stubs = {
  AvatarBase: {
    name: 'AvatarBase',
    template: '<div class="avatar-base" :data-userid="userId"></div>',
    props: ['userId', 'width', 'borderRadius', 'showDetail']
  }
}

function mountUserBaseInfo(userInfo, props = {}) {
  const getAreaInfo = vi.fn(() => '北京市 朝阳区')
  const wrapper = mount(UserBaseInfo, {
    props: { userInfo, showArea: true, ...props },
    global: {
      stubs,
      config: {
        globalProperties: {
          Utils: { getAreaInfo }
        }
      }
    }
  })
  return { wrapper, getAreaInfo }
}

describe('UserBaseInfo.vue 真实挂载（DOM 级）', () => {
  it('userId 渲染昵称与 ID', () => {
    const { wrapper } = mountUserBaseInfo({ userId: 'U_x', nickName: '小明' })
    expect(wrapper.find('.nick-name').text()).toContain('小明')
    expect(wrapper.find('.info').text()).toContain('U_x')
    expect(wrapper.find('.avatar-base').attributes('data-userid')).toBe('U_x')
  })

  it('无 userId 时 contactId 回退', () => {
    const { wrapper } = mountUserBaseInfo({ contactId: 'C_x', nickName: '联系人' })
    expect(wrapper.find('.info').text()).toContain('C_x')
    expect(wrapper.find('.avatar-base').attributes('data-userid')).toBe('C_x')
  })

  it('sex=0 → 女图标', () => {
    const { wrapper } = mountUserBaseInfo({ userId: 'U_x', sex: 0 })
    expect(wrapper.find('.icon-woman').exists()).toBe(true)
    expect(wrapper.find('.icon-man').exists()).toBe(false)
  })

  it('sex=1 → 男图标', () => {
    const { wrapper } = mountUserBaseInfo({ userId: 'U_x', sex: 1 })
    expect(wrapper.find('.icon-man').exists()).toBe(true)
    expect(wrapper.find('.icon-woman').exists()).toBe(false)
  })

  it('showArea=true → Utils.getAreaInfo 被调用', () => {
    const { wrapper, getAreaInfo } = mountUserBaseInfo(
      { userId: 'U_x', areaName: ['北京市', '北京市', '朝阳区'] },
      { showArea: true }
    )
    expect(getAreaInfo).toHaveBeenCalled()
    expect(wrapper.text()).toContain('地区：北京市 朝阳区')
  })

  it('showArea=false → 不渲染地区行', () => {
    const { wrapper, getAreaInfo } = mountUserBaseInfo(
      { userId: 'U_x', areaName: ['北京市'] },
      { showArea: false }
    )
    expect(wrapper.text()).not.toContain('地区：')
    expect(getAreaInfo).not.toHaveBeenCalled()
  })
})
