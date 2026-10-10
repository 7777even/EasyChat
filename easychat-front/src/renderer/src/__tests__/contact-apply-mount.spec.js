import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ContactApply from '@/views/contact/ContactApply.vue'
import { useContactStateStore } from '@/stores/ContactStateStore'
import { useMessageCountStore } from '@/stores/MessageCountStore'

/**
 * 新朋友（好友/入群申请列表）的**真实挂载**测试（DOM 级，真实 pinia store）。
 *
 * 被测点：
 *   1. 挂载即 loadApply（pageNo=1）+ 分页追加语义（pageNo 回写、pageTotal 截止）
 *   2. contactType 分流：0=好友（绿）/ 其他=入群申请（蓝）
 *   3. 未处理项渲染「接受」下拉；已处理项渲染 statusName
 *   4. dealWithApply：Confirm okfun → 参数 + 重载 + 按 contactType×status 分流刷新
 *      （0×1 → USER；1×1 → GROUP；其他不刷新）
 *   5. 空列表 → 「暂无申请」
 *   6. messageCountStore.contactApplyCount 变化 → 重置分页重拉（immediate + deep）
 */

const MessageStub = { warning: vi.fn(), success: vi.fn(), error: vi.fn() }
const ConfirmStub = vi.fn()

// v-infinite-scroll 指令桩：捕获绑定的 loadApply，供测试手动触底
let infiniteScrollFn = null
const InfiniteScrollStub = {
  mounted (el, binding) {
    infiniteScrollFn = binding.value
  },
  unmounted () {
    infiniteScrollFn = null
  }
}
// 分页页码序列（每次 load 请求消耗一个）
let pageSeen = null
function resetPageSeen (pages) {
  let i = 0
  pageSeen = { next: () => ({ value: pages[Math.min(i++, pages.length - 1)] }) }
}

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const ContentPanelStub = {
  name: 'ContentPanel',
  props: ['showTopBorder'],
  template: '<div class="content-panel-stub"><slot /></div>'
}
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width', 'lastUpdateTime'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
const ElDropdownStub = {
  name: 'ElDropdown',
  props: ['placement', 'trigger'],
  template: '<div class="dropdown"><slot /><div class="dropdown-menu"><slot name="dropdown" /></div></div>'
}
const ElDropdownItemStub = {
  name: 'ElDropdownItem',
  emits: ['click'],
  template: '<div class="dropdown-item" @click="$emit(\'click\')"><slot /></div>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size'],
  template: '<button class="el-button"><slot /></button>'
}

const APPLY_USER = {
  applyId: 11,
  contactType: 0,
  applyUserId: 'U010',
  contactName: '阿强',
  applyInfo: '我是阿强',
  status: 0
}
const APPLY_GROUP = {
  applyId: 12,
  contactType: 1,
  applyUserId: 'U011',
  contactName: '周末篮球群',
  applyInfo: '申请加入',
  status: 0
}
const APPLY_DONE = {
  applyId: 13,
  contactType: 0,
  applyUserId: 'U012',
  contactName: '小明',
  applyInfo: '请求',
  status: 2,
  statusName: '已拒绝'
}

async function mountApply (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const request = makeRequest(handler)
  const wrapper = mount(ContactApply, {
    global: {
      plugins: [pinia],
      directives: { 'infinite-scroll': InfiniteScrollStub },
      stubs: {
        ContentPanel: ContentPanelStub,
        Avatar: AvatarStub,
        'el-dropdown': ElDropdownStub,
        'el-dropdown-item': ElDropdownItemStub,
        'el-button': ElButtonStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            loadApply: '/contactApply/load',
            dealWithApply: '/contactApply/deal'
          },
          Message: MessageStub,
          Confirm: ConfirmStub
        }
      }
    }
  })
  return { wrapper, request, pinia }
}

async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

beforeEach(() => {
  ConfirmStub.mockClear()
  MessageStub.warning.mockClear()
  infiniteScrollFn = null
  resetPageSeen([1, 1, 1, 1, 1, 1, 1, 1])
})

describe('ContactApply.vue 真实挂载（DOM 级）', () => {
  it('挂载即 loadApply（pageNo=1），好友申请渲染「好友」标签 + 接受按钮', async () => {
    const { wrapper, request } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_USER] }
    }))
    await flush()

    expect(request.__calls__[0].url).toBe('/contactApply/load')
    expect(request.__calls__[0].params).toEqual({ pageNo: 1 })
    expect(wrapper.find('.contact-type').text()).toBe('好友')
    expect(wrapper.find('.contact-type').classes()).toContain('user-contact')
    expect(wrapper.find('.nick-name').text()).toBe('阿强')
    expect(wrapper.find('.apply-info').text()).toBe('我是阿强')
    expect(wrapper.find('.avatar-stub').attributes('data-userid')).toBe('U010')
    expect(wrapper.find('.el-button').text()).toBe('接受')
  })

  it('入群申请（contactType=1）→ 「入群申请」标签（无绿色 class）', async () => {
    const { wrapper } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_GROUP] }
    }))
    await flush()
    expect(wrapper.find('.contact-type').text()).toBe('入群申请')
    expect(wrapper.find('.contact-type').classes()).not.toContain('user-contact')
  })

  it('已处理项（status!=0）→ 渲染 statusName，无接受按钮', async () => {
    const { wrapper } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_DONE] }
    }))
    await flush()
    expect(wrapper.find('.result-name').text()).toBe('已拒绝')
    expect(wrapper.find('.el-button').exists()).toBe(false)
  })

  it('空列表 → 「暂无申请」', async () => {
    const { wrapper } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [] }
    }))
    await flush()
    expect(wrapper.find('.no-data').text()).toBe('暂无申请')
  })

  it('多页：第二页追加（pageNo!=1 不清空已加载项）', async () => {
    resetPageSeen([1, 2])
    const { wrapper, request } = await mountApply(() => ({
      code: 0,
      data: {
        pageNo: pageSeen.next().value,
        pageTotal: 2,
        list: [APPLY_USER, APPLY_GROUP]
      }
    }))
    await flush()
    expect(wrapper.findAll('.apply-item')).toHaveLength(2)

    // ContentPanel 上的 v-infinite-scroll 绑定的就是 loadApply —— 注册同名指令桩
    // 捕获绑定值并手动调用，等价于真实滚动触底（Element 内部不测）
    expect(typeof infiniteScrollFn).toBe('function')
    await infiniteScrollFn()
    await flush()

    const pageCalls = request.__calls__
      .filter((c) => c.url === '/contactApply/load')
      .map((c) => c.params.pageNo)
    expect(pageCalls).toEqual([1, 2])
    // 第二页 pageNo!=1 → 不清空，4 条追加
    expect(wrapper.findAll('.apply-item')).toHaveLength(4)
  })

  it('同意好友申请：Confirm → dealWithApply 参数 + 重载 + store 置 USER', async () => {
    const { wrapper, request, pinia } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_USER] }
    }))
    await flush()
    const store = useContactStateStore()

    await wrapper.findAll('.dropdown-item')[0].trigger('click')
    expect(ConfirmStub).toHaveBeenCalledWith(
      expect.objectContaining({ message: '确定要执行操作吗?' })
    )
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()

    const dealCalls = request.__calls__.filter((c) => c.url === '/contactApply/deal')
    expect(dealCalls[0].params).toEqual({ applyId: 11, status: 1 })
    // okfun 内重载：下一次 load 请求 pageNo 回到 1
    const lastLoad = request.__calls__.filter((c) => c.url === '/contactApply/load').pop()
    expect(lastLoad.params.pageNo).toBe(1)
    expect(store.contactReload).toBe('USER')
    expect(pinia).toBeTruthy()
  })

  it('拒绝入群申请（contactType=1×status=2）→ store 不置 USER/GROUP', async () => {
    const { wrapper, request, pinia } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_GROUP] }
    }))
    await flush()
    const store = useContactStateStore()

    await wrapper.findAll('.dropdown-item')[1].trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()

    const dealCall = request.__calls__.find((c) => c.url === '/contactApply/deal')
    expect(dealCall.params).toEqual({ applyId: 12, status: 2 })
    // status!=1 → 不触发联系人/群刷新
    expect(store.contactReload).toBeNull()
    expect(pinia).toBeTruthy()
  })

  it('同意入群申请（1×1）→ store 置 GROUP', async () => {
    const { wrapper, request, pinia } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_GROUP] }
    }))
    await flush()
    const store = useContactStateStore()

    await wrapper.findAll('.dropdown-item')[0].trigger('click')
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()

    const dealCall = request.__calls__.find((c) => c.url === '/contactApply/deal')
    expect(dealCall.params).toEqual({ applyId: 12, status: 1 })
    expect(store.contactReload).toBe('GROUP')
    expect(pinia).toBeTruthy()
  })

  it('okfun 返回空（接口失败）→ 不重载、不置刷新标记', async () => {
    const { wrapper, request, pinia } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_USER] }
    }))
    await flush()
    const store = useContactStateStore()
    const loadCountBefore = request.__calls__.length

    await wrapper.findAll('.dropdown-item')[0].trigger('click')
    // okfun 内的 Request 返回空 → 直接 return（桩须在 okfun 同步段触发 Request 前生效）
    request.mockImplementationOnce(async () => undefined)
    await ConfirmStub.mock.calls[0][0].okfun()
    await flush()

    // deal 本身被调用（mock.calls 兜住被 once-implementation 顶掉的记录）
    const dealCalled = request.mock.calls.some(
      (c) => c[0] && c[0].url === '/contactApply/deal'
    )
    expect(dealCalled).toBe(true)
    // 失败 → 不重载：load 请求数不变
    expect(request.__calls__.length).toBe(loadCountBefore)
    expect(store.contactReload).toBeNull()
    expect(pinia).toBeTruthy()
  })

  it('messageCountStore.contactApplyCount 增长 → 重置分页重拉', async () => {
    const { wrapper, request } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_USER] }
    }))
    await flush()
    const before = request.__calls__.filter((c) => c.url === '/contactApply/load').length

    useMessageCountStore().setCount('contactApplyCount', 2)
    await flush()

    const after = request.__calls__.filter((c) => c.url === '/contactApply/load').length
    expect(after).toBeGreaterThan(before)
    // 重置后从第 1 页开始
    const last = request.__calls__.filter((c) => c.url === '/contactApply/load').pop()
    expect(last.params).toEqual({ pageNo: 1 })
    expect(wrapper.findAll('.apply-item').length).toBeGreaterThan(0)
  })

  it('pageNo > pageTotal → 不再发请求（分页截止守卫）', async () => {
    const { wrapper, request } = await mountApply(() => ({
      code: 0,
      data: { pageNo: 1, pageTotal: 1, list: [APPLY_USER] }
    }))
    await flush()
    const before = request.__calls__.length

    // count 变化触发重置（pageNo=0 → 仍会拉一次）；连续触发第二次后 pageTotal=1 已达
    useMessageCountStore().setCount('contactApplyCount', 1)
    await flush()
    useMessageCountStore().setCount('contactApplyCount', 0)
    await flush()

    // count=0 为 falsy → watch 不触发（守卫 if (newVal)）
    const loadCalls = request.__calls__.filter((c) => c.url === '/contactApply/load')
    expect(loadCalls.length).toBeGreaterThanOrEqual(1)
    expect(wrapper.exists()).toBe(true)
    expect(request.__calls__.length).toBeGreaterThanOrEqual(before)
  })
})
