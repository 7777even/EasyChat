import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MessageSearch from '@/views/chat/MessageSearch.vue'

/**
 * 消息搜索弹窗的**真实挂载**测试（DOM 纯 DOM 级）。
 *
 * 被测点：
 *   1. show(sessionId)：开窗重置状态、初始提示「请输入搜索条件」
 *   2. 空条件搜索 → warning 不发请求；关键词搜索 → params 带 sessionId/keyword/pageNo
 *   3. messageType 筛选 / dateRange → startTime/endTime 透传
 *   4. 结果渲染：昵称/时间/高亮；分页 total>0 才出现
 *   5. 点击结果 → emit jumpToMessage(messageId) 并关窗
 *   6. **关键词含正则元字符（如 `(`）不得炸渲染**；messageContent 含 HTML 须转义（v-html 注入面）
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

const ElDialogStub = {
  props: ['modelValue', 'title', 'width'],
  emits: ['close', 'update:modelValue'],
  template:
    '<div v-if="modelValue" class="el-dialog"><div class="dialog-title">{{ title }}</div><slot /></div>'
}
const ElInputStub = {
  props: ['modelValue', 'placeholder', 'clearable'],
  emits: ['update:modelValue', 'keyup.enter'],
  template:
    '<input class="el-input" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" @keyup.enter="$emit(\'keyup.enter\', $event)" />'
}
const ElSelectStub = {
  name: 'ElSelect',
  props: ['modelValue', 'placeholder', 'clearable'],
  emits: ['update:modelValue'],
  template: '<div class="el-select"><slot /></div>'
}
const ElOptionStub = { props: ['label', 'value'], template: '<div class="el-option" />' }
const ElDatePickerStub = {
  name: 'ElDatePicker',
  props: ['modelValue', 'type'],
  emits: ['update:modelValue'],
  template: '<div class="date-picker" />'
}
const ElButtonStub = {
  props: ['type'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}
const ElPaginationStub = {
  name: 'ElPagination',
  props: ['total', 'pageSize', 'currentPage', 'layout', 'background'],
  emits: ['current-change', 'update:currentPage'],
  template: '<div class="el-pagination" :data-total="total" />'
}
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}

function mountSearch (handler) {
  const request = makeRequest(handler)
  const wrapper = mount(MessageSearch, {
    global: {
      stubs: {
        'el-dialog': ElDialogStub,
        'el-input': ElInputStub,
        'el-select': ElSelectStub,
        'el-option': ElOptionStub,
        'el-date-picker': ElDatePickerStub,
        'el-button': ElButtonStub,
        'el-pagination': ElPaginationStub,
        Avatar: AvatarStub
      },
      directives: { loading: {} },
      config: {
        globalProperties: {
          Request: request,
          Api: { searchMessage: '/message/search' },
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper, request }
}

async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

beforeEach(() => {
  MessageStub.warning.mockClear()
  MessageStub.error.mockClear()
})

describe('MessageSearch.vue 真实挂载（DOM 级）', () => {
  it('show(sessionId)：开窗并重置为初始提示「请输入搜索条件」', async () => {
    const { wrapper } = mountSearch()
    expect(wrapper.find('.el-dialog').exists()).toBe(false)
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('请输入搜索条件')
    expect(wrapper.find('.el-input').attributes('placeholder')).toBe('搜索消息内容')
  })

  it('空条件搜索 → warning 且不发请求', async () => {
    const { wrapper, request } = mountSearch()
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-button').trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请输入搜索条件')
    expect(request).not.toHaveBeenCalled()
  })

  it('关键词搜索：params 带 sessionId/keyword/pageNo，结果渲染昵称+高亮', async () => {
    const { wrapper, request } = mountSearch(() => ({
      code: 0,
      data: {
        totalCount: 1,
        list: [
          {
            messageId: 7,
            sendUserId: 'U_1',
            sendUserNickName: '李四',
            messageContent: '记得喝水',
            sendTime: Date.now() - 60 * 1000
          }
        ]
      }
    }))
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()

    await wrapper.find('.el-input').setValue('喝水')
    await wrapper.find('.el-button').trigger('click')
    await flush()

    expect(request.__calls__[0].url).toBe('/message/search')
    expect(request.__calls__[0].params).toEqual({
      sessionId: 'S1',
      keyword: '喝水',
      messageType: null,
      pageNo: 1
    })
    const item = wrapper.find('.result-item')
    expect(item.find('.sender-name').text()).toBe('李四')
    expect(item.find('.message-text').html()).toContain('class="highlight"')
    expect(item.find('.message-text').text()).toContain('记得喝水')
  })

  it('messageType 筛选 → 参数透传（5=文件消息）', async () => {
    const { wrapper, request } = mountSearch(() => ({
      code: 0,
      data: { list: [], totalCount: 0 }
    }))
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('报告')
    wrapper.findComponent(ElSelectStub).vm.$emit('update:modelValue', 5)
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-button').trigger('click')
    await flush()
    expect(request.__calls__[0].params.messageType).toBe(5)
  })

  it('dateRange → startTime/endTime 透传；无结果 → 「未找到相关消息」', async () => {
    const { wrapper, request } = mountSearch(() => ({
      code: 0,
      data: { list: [], totalCount: 0 }
    }))
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('词')
    wrapper
      .findComponent(ElDatePickerStub)
      .vm.$emit('update:modelValue', [1000, 2000])
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-button').trigger('click')
    await flush()

    expect(request.__calls__[0].params.startTime).toBe(1000)
    expect(request.__calls__[0].params.endTime).toBe(2000)
    expect(wrapper.text()).toContain('未找到相关消息')
    expect(wrapper.find('.el-pagination').exists()).toBe(false)
  })

  it('total>0 → 分页出现且 total 正确', async () => {
    const { wrapper } = mountSearch(() => ({
      code: 0,
      data: { list: [], totalCount: 55 }
    }))
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('词')
    await wrapper.find('.el-button').trigger('click')
    await flush()
    expect(wrapper.find('.el-pagination').attributes('data-total')).toBe('55')
  })

  it('点击结果 → emit jumpToMessage(messageId) 并关窗', async () => {
    const { wrapper } = mountSearch(() => ({
      code: 0,
      data: {
        totalCount: 1,
        list: [
          {
            messageId: 42,
            sendUserId: 'U_1',
            sendUserNickName: '李四',
            messageContent: 'x',
            sendTime: Date.now()
          }
        ]
      }
    }))
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('x')
    await wrapper.find('.el-button').trigger('click')
    await flush()

    await wrapper.find('.result-item').trigger('click')
    expect(wrapper.emitted('jumpToMessage')).toEqual([[42]])
    expect(wrapper.find('.el-dialog').exists()).toBe(false)
  })

  it('关键词含正则元字符 "("：不得抛异常，且按字面高亮', async () => {
    const { wrapper } = mountSearch(() => ({
      code: 0,
      data: {
        totalCount: 1,
        list: [
          {
            messageId: 1,
            sendUserId: 'U_1',
            sendUserNickName: '甲',
            messageContent: '函数(参数)说明',
            sendTime: Date.now()
          }
        ]
      }
    }))
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('(')
    await wrapper.find('.el-button').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    // 修复前：new RegExp('(') 抛 SyntaxError → 渲染中断，highlight span 不会出现
    const html = wrapper.find('.message-text').html()
    expect(html).toContain('class="highlight"')
    expect(wrapper.find('.message-text').text()).toBe('函数(参数)说明')
  })

  it('messageContent 含 HTML：v-html 前转义，不产生真实节点', async () => {
    const { wrapper } = mountSearch(() => ({
      code: 0,
      data: {
        totalCount: 1,
        list: [
          {
            messageId: 2,
            sendUserId: 'U_1',
            sendUserNickName: '甲',
            messageContent: '<b>加粗</b> 内容',
            sendTime: Date.now()
          }
        ]
      }
    }))
    wrapper.vm.show('S1')
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('内容')
    await wrapper.find('.el-button').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    // 修复前：未转义的 <b> 直接经 v-html 变成真实 <b> 节点（注入面）
    expect(wrapper.find('.message-text').find('b').exists()).toBe(false)
    expect(wrapper.find('.message-text').text()).toBe('<b>加粗</b> 内容')
  })
})
