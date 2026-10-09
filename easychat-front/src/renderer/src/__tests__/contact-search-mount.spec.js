import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import Search from '@/views/contact/Search.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'

/**
 * 搜索页（ID 搜索 + 关键词搜好友）的**真实挂载**测试（DOM 级，真实 router）。
 *
 * 被测点：
 *   1. 初始态：无 ID 结果 → 「没有搜索到任何结果」
 *   2. ID 搜索：参数 + contactType 标签（用户/群组/自己）+ 按钮分流
 *      （status 0/2/3/4 → 添加/申请；status 1 → 发消息；status 5/6 → 拉黑文案；
 *      自己 → 不渲染操作区）
 *   3. applyContact → SearchAdd.show；reload → resetForm 清空结果
 *   4. 关键词搜索：空关键词 warning 不发请求；正常 → 列表 + 标题计数 + 空态
 *   5. 点击好友/发消息 → 跳 /chat
 */

const MessageStub = { warning: vi.fn(), success: vi.fn(), error: vi.fn() }

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const ContentPanelStub = { name: 'ContentPanel', template: '<div class="content-panel"><slot /></div>' }
const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder', 'size', 'clearable'],
  emits: ['update:modelValue', 'keydown'],
  template:
    '<input class="el-input-native" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" @keydown="$emit(\'keydown\', $event)" />'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\', $event)"><slot /></button>'
}
const UserBaseInfoStub = {
  name: 'UserBaseInfo',
  props: ['userInfo', 'showArea'],
  template: '<div class="user-base-info-stub" :data-uid="userInfo.contactId" />'
}
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width', 'borderRadius'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}
// SearchAdd：暴露 show() 供 applyContact 调用，并可手动触发 reload
const searchAddShow = vi.fn()
let searchAddReload = null
const SearchAddStub = {
  name: 'SearchAdd',
  template: '<div class="search-add-stub" />',
  methods: {
    show: (data) => searchAddShow(data)
  },
  mounted () {
    searchAddReload = () => this.$emit('reload')
  }
}

const ME = 'U001'

async function mountSearch (handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useUserInfoStore().setInfo({ userId: ME, nickName: '我' })
  const request = makeRequest(handler)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/chat', component: { template: '<div />' } }]
  })
  await router.push('/contact/search')
  await router.isReady()
  const wrapper = mount(Search, {
    global: {
      plugins: [pinia, router],
      stubs: {
        ContentPanel: ContentPanelStub,
        'el-input': ElInputStub,
        'el-button': ElButtonStub,
        UserBaseInfo: UserBaseInfoStub,
        Avatar: AvatarStub,
        SearchAdd: SearchAddStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            search: '/contact/search',
            searchContactByKeyword: '/contact/searchByKeyword'
          },
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper, request, router }
}

async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

beforeEach(() => {
  MessageStub.warning.mockClear()
  searchAddShow.mockClear()
  searchAddReload = null
})

describe('Search.vue 真实挂载（DOM 级）', () => {
  it('初始态：searchResult={} → 结果区与 no-data 均不渲染（面板空态）', async () => {
    const { wrapper } = await mountSearch()
    expect(wrapper.find('.no-data').exists()).toBe(false)
    expect(wrapper.find('.search-result-panel').exists()).toBe(false)
    // 搜一个不存在的 → 接口回 data=null → no-data 兜底
    const { wrapper: w2, request } = await mountSearch(() => ({ code: 0, data: null }))
    await w2.find('.search-form .el-input-native').setValue('U404')
    await w2.find('.search-form .search-btn').trigger('click')
    await flush()
    expect(request).toHaveBeenCalledTimes(1)
    expect(w2.find('.no-data').text()).toBe('没有搜索到任何结果')
  })

  it('ID 搜索用户 status=0：参数 + 「用户」标签 + 「添加到联系人」', async () => {
    const { wrapper, request } = await mountSearch(() => ({
      code: 0,
      data: { contactId: 'U009', contactType: 'USER', status: 0, nickName: '小明' }
    }))
    await wrapper.find('.search-form .el-input-native').setValue('U009')
    await wrapper.find('.search-form .search-btn').trigger('click')
    await flush()

    expect(request.__calls__[0].url).toBe('/contact/search')
    expect(request.__calls__[0].params).toEqual({ contactId: 'U009' })
    expect(wrapper.find('.no-data').exists()).toBe(false)
    expect(wrapper.find('.contact-type').text()).toBe('用户')
    expect(wrapper.find('.el-button').text()).toBe('添加到联系人')
  })

  it('群组 status=2 → 「群组」标签 + 「申请加入群组」', async () => {
    const { wrapper } = await mountSearch(() => ({
      code: 0,
      data: { contactId: 'G009', contactType: 'GROUP', status: 2 }
    }))
    await wrapper.find('.search-form .el-input-native').setValue('G009')
    await wrapper.find('.search-form .search-btn').trigger('click')
    await flush()
    expect(wrapper.find('.contact-type').text()).toBe('群组')
    expect(wrapper.find('.el-button').text()).toBe('申请加入群组')
  })

  it('status=1 → 「发消息」；点击跳 /chat', async () => {
    const { wrapper, router } = await mountSearch(() => ({
      code: 0,
      data: { contactId: 'U010', contactType: 'USER', status: 1 }
    }))
    await wrapper.find('.search-form .el-input-native').setValue('U010')
    await wrapper.find('.search-form .search-btn').trigger('click')
    await flush()

    const sendBtn = wrapper.findAll('.op-btn .el-button')[0]
    expect(sendBtn.text()).toBe('发消息')
    const push = vi.spyOn(router, 'push')
    await sendBtn.trigger('click')
    expect(push.mock.calls[0][0].path).toBe('/chat')
    expect(push.mock.calls[0][0].query.chatId).toBe('U010')
  })

  it('status=5/6 → 「对方拉黑了你」且无操作按钮', async () => {
    const { wrapper } = await mountSearch(() => ({
      code: 0,
      data: { contactId: 'U011', contactType: 'USER', status: 5 }
    }))
    await wrapper.find('.search-form .el-input-native').setValue('U011')
    await wrapper.find('.search-form .search-btn').trigger('click')
    await flush()
    expect(wrapper.text()).toContain('对方拉黑了你')
    expect(wrapper.findAll('.op-btn .el-button')).toHaveLength(0)
  })

  it('搜到自己 → 标签「自己」且不渲染操作区', async () => {
    const { wrapper } = await mountSearch(() => ({
      code: 0,
      data: { contactId: ME, contactType: 'USER', status: 0 }
    }))
    await wrapper.find('.search-form .el-input-native').setValue(ME)
    await wrapper.find('.search-form .search-btn').trigger('click')
    await flush()
    expect(wrapper.find('.contact-type').text()).toBe('自己')
    expect(wrapper.find('.op-btn').exists()).toBe(false)
  })

  it('添加到联系人 → SearchAdd.show 传入完整结果', async () => {
    const result = { contactId: 'U009', contactType: 'USER', status: 0 }
    const { wrapper } = await mountSearch(() => ({ code: 0, data: result }))
    await wrapper.find('.search-form .el-input-native').setValue('U009')
    await wrapper.find('.search-form .search-btn').trigger('click')
    await flush()
    await wrapper.find('.op-btn .el-button').trigger('click')
    expect(searchAddShow).toHaveBeenCalledWith(result)
  })

  it('SearchAdd 触发 reload → 清空 ID 结果与输入', async () => {
    const { wrapper } = await mountSearch(() => ({
      code: 0,
      data: { contactId: 'U009', contactType: 'USER', status: 0 }
    }))
    await wrapper.find('.search-form .el-input-native').setValue('U009')
    await wrapper.find('.search-form .search-btn').trigger('click')
    await flush()
    expect(wrapper.find('.search-result-panel').exists()).toBe(true)

    searchAddReload()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.search-result-panel').exists()).toBe(false)
    expect(wrapper.find('.search-form .el-input-native').element.value).toBe('')
  })

  it('关键词空搜索 → warning 不发请求', async () => {
    const { wrapper, request } = await mountSearch()
    await wrapper.find('.keyword-form .search-btn').trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请输入搜索关键词')
    expect(request).not.toHaveBeenCalled()
    expect(wrapper.find('.keyword-result').exists()).toBe(false)
  })

  it('关键词搜索：trim + 参数 + 列表计数 + remark 优先展示', async () => {
    const { wrapper, request } = await mountSearch((opts) => {
      if (opts.url === '/contact/searchByKeyword') {
        return {
          code: 0,
          data: [
            { contactId: 'U020', contactName: '阿强', remark: '强子', groupName: '同事' },
            { contactId: 'U021', contactName: '阿伟', remark: '伟哥' },
            { contactId: 'U022' }
          ]
        }
      }
      return { code: 0, data: null }
    })
    await wrapper.find('.keyword-form .el-input-native').setValue('  强  ')
    await wrapper.find('.keyword-form .search-btn').trigger('click')
    await flush()

    const call = request.__calls__[0]
    expect(call.url).toBe('/contact/searchByKeyword')
    expect(call.params).toEqual({ keyword: '强' })
    expect(wrapper.find('.result-title').text()).toBe('好友（3）')
    const names = wrapper.findAll('.keyword-name').map((n) => n.text())
    // keyword-name = remark || contactName || contactId（remark 优先）
    expect(names).toEqual(['强子', '伟哥', 'U022'])
    const subs = wrapper.findAll('.keyword-sub').map((n) => n.text())
    expect(subs[0]).toContain('分组：同事')
    expect(subs[1]).toContain('昵称：阿伟')
    expect(subs[2]).toContain('U022')
  })

  it('关键词无匹配 → 「没有匹配的好友」', async () => {
    const { wrapper } = await mountSearch((opts) => {
      if (opts.url === '/contact/searchByKeyword') return { code: 0, data: [] }
      return { code: 0, data: null }
    })
    await wrapper.find('.keyword-form .el-input-native').setValue('不存在')
    await wrapper.find('.keyword-form .search-btn').trigger('click')
    await flush()
    expect(wrapper.find('.keyword-result .no-data').text()).toBe('没有匹配的好友')
  })

  it('点击好友条目 → 跳 /chat 带 chatId', async () => {
    const { wrapper, router } = await mountSearch((opts) => {
      if (opts.url === '/contact/searchByKeyword') {
        return { code: 0, data: [{ contactId: 'U020', contactName: '阿强' }] }
      }
      return { code: 0, data: null }
    })
    await wrapper.find('.keyword-form .el-input-native').setValue('阿')
    await wrapper.find('.keyword-form .search-btn').trigger('click')
    await flush()

    const push = vi.spyOn(router, 'push')
    await wrapper.find('.keyword-item').trigger('click')
    expect(push.mock.calls[0][0].path).toBe('/chat')
    expect(push.mock.calls[0][0].query.chatId).toBe('U020')
    expect(push.mock.calls[0][0].query.timestamp).toBeTruthy()
  })

  it('关键词接口返回空 data → 不渲染列表也不抛错', async () => {
    const { wrapper } = await mountSearch((opts) => {
      if (opts.url === '/contact/searchByKeyword') return { code: 0, data: null }
      return { code: 0, data: null }
    })
    await wrapper.find('.keyword-form .el-input-native').setValue('x')
    await wrapper.find('.keyword-form .search-btn').trigger('click')
    await flush()
    expect(wrapper.find('.result-title').text()).toBe('好友（0）')
    expect(wrapper.find('.keyword-result .no-data').text()).toBe('没有匹配的好友')
  })
})
