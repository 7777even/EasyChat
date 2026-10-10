import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import MomentNotify from '@/views/moment/MomentNotify.vue'

/**
 * 朋友圈通知中心的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. show() → 开窗 + pageNo 复位 + momentNotifyList 请求
 *   2. Tab 前端过滤（后端只有全量接口）：点赞(1)/评论(2)/@我(4)
 *   3. 未读行样式类 unread + 点击 → 置已读 + momentMarkRead + emit locateMoment(refId) + 关窗
 *   4. 已读行点击不发 markRead；无 refId 不 emit 不关窗
 *   5. 全部已读 → 全部置 1；失败保留
 *   6. 清空 → Confirm → momentClearNotify → 清列表 + 提示；失败保留
 *   7. typeLabel 五种文案；formatTime 档位（刚刚/分钟前/小时前/日期）
 *
 * ⚠ 夹具必须 freshList() 每次深拷贝：组件会就地改 item.readStatus，
 *   共享夹具会跨用例污染（首版即踩中：上一用例把全列表置已读）。
 */

const MessageStub = { success: vi.fn(), warning: vi.fn() }
const ConfirmStub = vi.fn()

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: { list: [] } }
  })
  fn.__calls__ = calls
  return fn
}

const DialogStub = {
  name: 'Dialog',
  props: ['show', 'title', 'buttons', 'width', 'showCancel'],
  emits: ['close'],
  template:
    '<div class="dialog-stub" v-if="show"><div class="d-title">{{ title }}</div><div class="d-body"><slot /></div>' +
    '<button class="d-close" @click="$emit(\'close\')">X</button></div>'
}
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width', 'borderRadius'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'link'],
  emits: ['click'],
  template: '<button class="el-button" :data-type="type" @click="$emit(\'click\')"><slot /></button>'
}
const ElScrollbarStub = {
  name: 'ElScrollbar',
  template: '<div class="el-scrollbar-stub"><div class="wrap" ref="wrapRef"><slot /></div></div>'
}

const NOW = Date.now()
const BASE_LIST = [
  { id: 1, type: 1, fromUserId: 'U010', fromNickName: '阿强', content: '赞了你的动态', refId: 901, readStatus: 0, createTime: NOW - 10 * 1000 },
  { id: 2, type: 2, fromUserId: 'U011', fromNickName: '', content: '评论：好看', refId: 902, readStatus: 1, createTime: NOW - 3 * 60 * 1000 },
  { id: 3, type: 4, fromUserId: 'U012', content: '@了你', refId: null, readStatus: 0, createTime: NOW - 3 * 60 * 60 * 1000 }
]

/** 每次返回全新副本（组件就地改 readStatus，共享夹具会跨用例污染） */
function freshList () {
  return BASE_LIST.map((n) => ({ ...n }))
}

async function mountNotify (handler) {
  const request = makeRequest(handler)
  const wrapper = mount(MomentNotify, {
    global: {
      stubs: {
        Dialog: DialogStub,
        Avatar: AvatarStub,
        'el-button': ElButtonStub,
        'el-scrollbar': ElScrollbarStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            momentNotifyList: '/momentNotify/list',
            momentMarkRead: '/momentNotify/markRead',
            momentMarkAllRead: '/momentNotify/markAllRead',
            momentClearNotify: '/momentNotify/clear'
          },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper, request }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  MessageStub.success.mockClear()
  ConfirmStub.mockClear()
})

describe('MomentNotify.vue 真实挂载（DOM 级）', () => {
  it('初始关窗；show() 开窗 + 请求列表', async () => {
    const { wrapper, request } = await mountNotify()
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    wrapper.vm.show()
    await flush()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.find('.d-title').text()).toBe('朋友圈消息')
    const call = request.__calls__.find((c) => c.url === '/momentNotify/list')
    expect(call.params).toEqual({ pageNo: 1, pageSize: 20 })
  })

  it('列表渲染：昵称兜底 userId + 类型标签 + 未读类 + 时间档位', async () => {
    const { wrapper } = await mountNotify(() => ({ code: 0, data: { list: freshList() } }))
    wrapper.vm.show()
    await flush()
    const items = wrapper.findAll('.notify-item')
    expect(items).toHaveLength(3)
    expect(items[0].classes()).toContain('unread')
    expect(items[1].classes()).not.toContain('unread')
    expect(items[0].find('.notify-type').text()).toBe('赞了你')
    expect(items[1].find('.notify-name').text()).toBe('U011') // 无昵称兜底 userId
    expect(items[0].find('.notify-time').text()).toBe('刚刚')
    expect(items[1].find('.notify-time').text()).toBe('3分钟前')
    expect(items[2].find('.notify-time').text()).toBe('3小时前')
  })

  it('Tab 切「点赞」→ 前端过滤只剩 type=1 且重新请求', async () => {
    const { wrapper, request } = await mountNotify(() => ({ code: 0, data: { list: freshList() } }))
    wrapper.vm.show()
    await flush()
    await wrapper.findAll('.notify-tab')[1].trigger('click')
    await flush()
    expect(wrapper.findAll('.notify-item')).toHaveLength(1)
    expect(wrapper.find('.notify-type').text()).toBe('赞了你')
    expect(request.__calls__.filter((c) => c.url === '/momentNotify/list')).toHaveLength(2)
  })

  it('点击未读项（有 refId）→ markRead + emit locateMoment + 关窗', async () => {
    const { wrapper, request } = await mountNotify(() => ({ code: 0, data: { list: freshList() } }))
    wrapper.vm.show()
    await flush()
    // handleItemClick 是 async：先 await markRead，再 emit + closeDialog，
    // 故关窗断言必须在 flush 之后（同步断言时窗口仍开）
    await wrapper.findAll('.notify-item')[0].trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/momentNotify/markRead').params).toEqual({ notifyId: 1 })
    expect(wrapper.emitted('locateMoment')[0]).toEqual([901])
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    expect(wrapper.findAll('.notify-item')).toHaveLength(0)
  })

  it('点击未读无 refId 项 → markRead + 本地置已读（unread 类消失）+ 不关窗', async () => {
    const { wrapper, request } = await mountNotify(() => ({ code: 0, data: { list: freshList() } }))
    wrapper.vm.show()
    await flush()
    expect(wrapper.findAll('.unread')).toHaveLength(2)
    await wrapper.findAll('.notify-item')[2].trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/momentNotify/markRead').params).toEqual({ notifyId: 3 })
    expect(wrapper.findAll('.unread')).toHaveLength(1) // 第 3 项已读，第 1 项仍读
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.emitted('locateMoment')).toBeUndefined()
  })

  it('点击已读项 → 不发 markRead 但照常 emit locateMoment + 关窗', async () => {
    const { wrapper, request } = await mountNotify(() => ({ code: 0, data: { list: freshList() } }))
    wrapper.vm.show()
    await flush()
    await wrapper.findAll('.notify-item')[1].trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/momentNotify/markRead')).toBeUndefined()
    expect(wrapper.emitted('locateMoment')[0]).toEqual([902])
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
  })

  it('markRead 失败（抛异常）→ 不影响 emit 跳转', async () => {
    const { wrapper } = await mountNotify((opts) => {
      if (opts.url === '/momentNotify/markRead') throw new Error('net')
      return { code: 0, data: { list: freshList() } }
    })
    wrapper.vm.show()
    await flush()
    await wrapper.findAll('.notify-item')[0].trigger('click')
    await flush()
    expect(wrapper.emitted('locateMoment')[0]).toEqual([901])
  })

  it('全部已读 → 全部置 1', async () => {
    const { wrapper, request } = await mountNotify(() => ({ code: 0, data: { list: freshList() } }))
    wrapper.vm.show()
    await flush()
    await wrapper.findAll('.el-button').find((b) => b.text() === '全部已读').trigger('click')
    await flush()
    expect(request.__calls__.find((c) => c.url === '/momentNotify/markAllRead')).toBeTruthy()
    expect(wrapper.findAll('.unread')).toHaveLength(0)
  })

  it('全部已读失败（返回空）→ 保留未读态', async () => {
    const { wrapper } = await mountNotify((opts) => {
      if (opts.url === '/momentNotify/markAllRead') return undefined
      return { code: 0, data: { list: freshList() } }
    })
    wrapper.vm.show()
    await flush()
    await wrapper.findAll('.el-button').find((b) => b.text() === '全部已读').trigger('click')
    await flush()
    expect(wrapper.findAll('.unread').length).toBeGreaterThan(0)
  })

  it('清空 → Confirm → clear 请求 → 清列表 + 提示', async () => {
    const { wrapper, request } = await mountNotify(() => ({ code: 0, data: { list: freshList() } }))
    wrapper.vm.show()
    await flush()
    await wrapper.findAll('.el-button').find((b) => b.text() === '清空').trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toBe('确定要清空全部朋友圈消息吗？')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(request.__calls__.find((c) => c.url === '/momentNotify/clear')).toBeTruthy()
    expect(MessageStub.success).toHaveBeenCalledWith('已清空')
    expect(wrapper.findAll('.notify-item')).toHaveLength(0)
  })

  it('清空失败 → 保留列表', async () => {
    const { wrapper } = await mountNotify((opts) => {
      if (opts.url === '/momentNotify/clear') return undefined
      return { code: 0, data: { list: freshList() } }
    })
    wrapper.vm.show()
    await flush()
    await wrapper.findAll('.el-button').find((b) => b.text() === '清空').trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()
    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(wrapper.findAll('.notify-item')).toHaveLength(3)
  })

  it('空列表 → 「暂无消息」', async () => {
    const { wrapper } = await mountNotify(() => ({ code: 0, data: { list: [] } }))
    wrapper.vm.show()
    await flush()
    expect(wrapper.find('.tip.empty').text()).toContain('暂无消息')
  })

  it('接口失败（返回空）→ 列表留空不崩', async () => {
    const { wrapper } = await mountNotify(() => undefined)
    wrapper.vm.show()
    await flush()
    expect(wrapper.findAll('.notify-item')).toHaveLength(0)
    expect(wrapper.find('.tip.empty').text()).toContain('暂无消息')
  })

  it('typeLabel：新动态/回复了你（0/3）+ 久远时间显示日期', async () => {
    const LIST = [
      { id: 11, type: 0, fromUserId: 'U010', content: '', refId: 1, readStatus: 1, createTime: NOW - 5 * 60 * 60 * 1000 },
      { id: 12, type: 3, fromUserId: 'U011', content: '', refId: 2, readStatus: 1, createTime: NOW - 2 * 24 * 60 * 60 * 1000 }
    ]
    const { wrapper } = await mountNotify(() => ({ code: 0, data: { list: LIST } }))
    wrapper.vm.show()
    await flush()
    const items = wrapper.findAll('.notify-item')
    expect(items[0].find('.notify-type').text()).toBe('新动态')
    expect(items[0].find('.notify-time').text()).toBe('5小时前')
    expect(items[1].find('.notify-type').text()).toBe('回复了你')
    expect(items[1].find('.notify-time').text()).toMatch(/^\d{2}-\d{2}$/) // 久远→日期
  })
})
