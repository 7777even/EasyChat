import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import Favorite from '@/views/setting/Favorite.vue'

/**
 * 我的收藏页的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 挂载即 listFavorite（get + showLoading:false）
 *   2. 列表渲染：条数、内容（空内容兜底「（无文本内容）」）、filePath、格式化时间
 *   3. 空态：el-empty 文案「暂无收藏，右键消息可收藏」
 *   4. 取消收藏 → Confirm → cancelFavorite 参数 → 成功提示 + 重新拉列表
 *   5. 取消失败（返回空）→ 不提示成功
 */

const MessageStub = { success: vi.fn(), warning: vi.fn() }
const ConfirmStub = vi.fn()

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: [] }
  })
  fn.__calls__ = calls
  return fn
}

const ContentPanelStub = {
  name: 'ContentPanel',
  props: ['showTopBorder'],
  template: '<div class="content-panel-stub"><slot /></div>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'plain', 'link'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}
const ElEmptyStub = {
  name: 'ElEmpty',
  props: ['description'],
  template: '<div class="el-empty-stub">{{ description }}</div>'
}

const LIST = [
  { id: 101, content: '明天开会', filePath: '', createTime: 1770000000000 },
  { id: 102, content: '', filePath: 'D:/a.png', createTime: 1770003600000 }
]

async function mountFavorite (handler) {
  const request = makeRequest(handler)
  const wrapper = mount(Favorite, {
    global: {
      stubs: {
        ContentPanel: ContentPanelStub,
        'el-button': ElButtonStub,
        'el-empty': ElEmptyStub
      },
      directives: { loading: {} },
      config: {
        globalProperties: {
          Request: request,
          Api: { listFavorite: '/favorite/list', cancelFavorite: '/favorite/cancel' },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  await new Promise((r) => setTimeout(r, 0))
  return { wrapper, request }
}

beforeEach(() => {
  MessageStub.success.mockClear()
  ConfirmStub.mockClear()
})

describe('Favorite.vue 真实挂载（DOM 级）', () => {
  it('挂载即 listFavorite（get）', async () => {
    const { request } = await mountFavorite()
    const call = request.__calls__[0]
    expect(call.url).toBe('/favorite/list')
    expect(call.method).toBe('get')
  })

  it('列表渲染：条数 / 内容 / 空内容兜底 / filePath / 时间', async () => {
    const { wrapper } = await mountFavorite(() => ({ code: 0, data: LIST }))
    expect(wrapper.find('.favorite-tip').text()).toBe('共 2 条收藏')
    const items = wrapper.findAll('.favorite-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.favorite-content').text()).toBe('明天开会')
    expect(items[1].find('.favorite-content').text()).toBe('（无文本内容）')
    expect(items[1].find('.favorite-file').text()).toBe('D:/a.png')
    // createTime 1770000000000 = 2026-02-03T01:46:40Z（本地时区渲染，只断言日期前缀形状）
    expect(items[0].find('.favorite-time').text()).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
  })

  it('空列表 → el-empty 文案', async () => {
    const { wrapper } = await mountFavorite(() => ({ code: 0, data: [] }))
    expect(wrapper.find('.el-empty-stub').text()).toBe('暂无收藏，右键消息可收藏')
    expect(wrapper.findAll('.favorite-item')).toHaveLength(0)
  })

  it('取消收藏 → Confirm → cancelFavorite 参数 → 提示 + 重拉列表', async () => {
    const { wrapper, request } = await mountFavorite((opts) => {
      if (opts.url === '/favorite/cancel') return { code: 0, data: 1 }
      return { code: 0, data: LIST }
    })
    await wrapper.findAll('.el-button').find((b) => b.text() === '取消收藏').trigger('click')
    expect(ConfirmStub.mock.calls[0][0].message).toBe('确定取消收藏吗?')
    await ConfirmStub.mock.calls[0][0].okfun()
    await new Promise((r) => setTimeout(r, 0))

    const cancelCall = request.__calls__.find((c) => c.url === '/favorite/cancel')
    expect(cancelCall.params).toEqual({ favoriteId: 101 })
    expect(MessageStub.success).toHaveBeenCalledWith('已取消收藏')
    // 成功后重拉：list 出现两次（挂载 + 取消后）
    expect(request.__calls__.filter((c) => c.url === '/favorite/list')).toHaveLength(2)
  })

  it('取消失败（返回空）→ 不提示成功', async () => {
    const { wrapper, request } = await mountFavorite((opts) => {
      if (opts.url === '/favorite/cancel') return undefined
      return { code: 0, data: LIST }
    })
    await wrapper.findAll('.el-button').find((b) => b.text() === '取消收藏').trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await new Promise((r) => setTimeout(r, 0))

    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(request.__calls__.filter((c) => c.url === '/favorite/list')).toHaveLength(1)
  })

  it('接口失败（返回空）→ 列表留空、页面不崩', async () => {
    const { wrapper } = await mountFavorite(() => undefined)
    expect(wrapper.findAll('.favorite-item')).toHaveLength(0)
    expect(wrapper.find('.el-empty-stub').text()).toBe('暂无收藏，右键消息可收藏')
  })
})
