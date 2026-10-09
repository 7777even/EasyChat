import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import GlobalSearch from '@/views/chat/GlobalSearch.vue'

/**
 * 全局搜索弹窗的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 初始态提示「输入关键词开始搜索」；空关键词搜索 → Message.warning 不发请求
 *   2. 搜索结果三段渲染（联系人/群组/聊天记录）+ 关键词高亮 + 空结果兜底
 *   3. messageType=5 → 摘要显示 [文件]fileName；formatTime MM-DD HH:mm
 *   4. 高亮须先转义 HTML 再替换（正则元字符/注入串不破坏 DOM）
 *   5. scope tab 切换 → 带 scope 重新搜索；点击结果 → emit 并关窗
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

const DialogStub = {
  props: ['show', 'title', 'width', 'showCancel'],
  template: '<div v-if="show" class="dialog-stub"><div class="dialog-title">{{ title }}</div><slot /></div>'
}
const ElInputStub = {
  props: ['modelValue', 'placeholder', 'size', 'clearable'],
  template:
    '<input class="el-input" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" @keyup.enter="$emit(\'keyup.enter\', $event)" />'
}
const ElButtonStub = {
  props: ['type', 'size', 'loading'],
  template: '<button class="el-button" :disabled="loading"><slot /></button>'
}
const ElScrollbarStub = { template: '<div class="el-scrollbar"><slot /></div>' }
const AvatarStub = {
  name: 'Avatar',
  props: ['userId', 'width', 'borderRadius'],
  template: '<div class="avatar-stub" :data-userid="userId" />'
}

function mountSearch (handler) {
  const request = makeRequest(handler)
  const wrapper = mount(GlobalSearch, {
    global: {
      stubs: {
        Dialog: DialogStub,
        Avatar: AvatarStub,
        'el-input': ElInputStub,
        'el-button': ElButtonStub,
        'el-scrollbar': ElScrollbarStub
      },
      config: {
        globalProperties: {
          Request: request,
          Api: { globalSearch: '/search/global' },
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
  MessageStub.success.mockClear()
})

describe('GlobalSearch.vue 真实挂载（DOM 级）', () => {
  it('初始态：弹窗未开；show() 后开窗并提示「输入关键词开始搜索」', async () => {
    const { wrapper } = mountSearch()
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.text()).toContain('输入关键词开始搜索')
    expect(wrapper.text()).toContain('全局搜索')
  })

  it('空关键词搜索 → warning 且不发请求', async () => {
    const { wrapper, request } = mountSearch()
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-button').trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请输入搜索关键词')
    expect(request).not.toHaveBeenCalled()
  })

  it('搜索结果三段渲染：联系人/群组/聊天记录（含分组标题与描述文案）', async () => {
    const { wrapper, request } = mountSearch(() => ({
      code: 0,
      data: {
        contactList: [
          { contactId: 'U_1', contactName: '张三', remark: '备注张', contactType: 0 }
        ],
        groupList: [{ contactId: 'G1', groupName: '开发群', contactType: 1 }],
        messageList: [
          {
            messageId: 9,
            contactId: 'U_1',
            contactType: 0,
            sendUserId: 'U_2',
            sendUserNickName: '李四',
            messageContent: '你好世界',
            sendTime: new Date(2026, 9, 1, 9, 5).getTime()
          }
        ]
      }
    }))
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('你好')
    await wrapper.find('.el-button').trigger('click')
    await flush()

    expect(request.__calls__[0].url).toBe('/search/global')
    expect(request.__calls__[0].params).toEqual({ keyword: '你好', scope: 'all' })

    const titles = wrapper.findAll('.group-title').map((n) => n.text())
    expect(titles).toEqual(['联系人', '群组', '聊天记录'])
    // 备注名优先于联系人昵称
    expect(wrapper.findAll('.result-item')[0].find('.result-name').text()).toBe('备注张')
    expect(wrapper.findAll('.result-item')[1].find('.result-desc').text()).toBe('群聊')
    // 消息条：昵称兜底 + 摘要 + 时间
    const msg = wrapper.findAll('.result-item')[2]
    expect(msg.find('.result-name').text()).toBe('李四')
    expect(msg.find('.result-desc').text()).toBe('你好世界')
    expect(msg.find('.result-time').text()).toBe('10-01 09:05')
    // 关键词高亮（jsdom 序列化后属性为双引号）
    expect(msg.find('.result-desc').html()).toContain('class="highlight"')
  })

  it('全部结果为空 → 「没有找到相关内容」', async () => {
    const { wrapper } = mountSearch(() => ({ code: 0, data: {} }))
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('不存在的词')
    await wrapper.find('.el-button').trigger('click')
    await flush()
    expect(wrapper.text()).toContain('没有找到相关内容')
    expect(wrapper.findAll('.result-group')).toHaveLength(0)
  })

  it('messageType=5 → 摘要显示 [文件]fileName（带高亮）', async () => {
    const { wrapper } = mountSearch(() => ({
      code: 0,
      data: {
        messageList: [
          {
            messageId: 1,
            contactId: 'U_1',
            sendUserId: 'U_2',
            messageType: 5,
            fileName: '需求文档.docx',
            sendTime: 0
          }
        ]
      }
    }))
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('需求')
    await wrapper.find('.el-button').trigger('click')
    await flush()
    const desc = wrapper.find('.result-desc')
    expect(desc.text()).toBe('[文件]需求文档.docx')
    // sendTime=0 → 时间列为空（formatTime 假值兜底）
    expect(wrapper.find('.result-time').text()).toBe('')
  })

  it('关键词含正则元字符/HTML 注入串：先转义再高亮，不产生脚本节点', async () => {
    const { wrapper } = mountSearch(() => ({
      code: 0,
      data: {
        contactList: [
          { contactId: 'U_1', contactName: '<img src=x> a+b', contactType: 0 }
        ]
      }
    }))
    // 注入串既作为搜索词，又作为被展示的名字
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('<img')
    await wrapper.find('.el-button').trigger('click')
    await flush()
    expect(wrapper.find('img').exists()).toBe(false)
    // 转义后的文本仍可见（'<' 已被转义为实体，不会重建节点）
    const name = wrapper.find('.result-name')
    expect(name.text()).toContain('<img src=x> a+b')
    expect(name.html()).toContain('&lt;img')

    // 正则元字符 'a+b' 不因 '+' 语义失配（a+b 按字面命中）
    await wrapper.find('.el-input').setValue('a+b')
    await wrapper.find('.el-button').trigger('click')
    await flush()
    expect(wrapper.find('.result-name').html()).toContain('class="highlight"')
  })

  it('切 scope → 带新 scope 重搜；all → message → active 类跟随', async () => {
    const { wrapper, request } = mountSearch(() => ({ code: 0, data: {} }))
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('词')
    await wrapper.find('.el-button').trigger('click')
    await flush()
    expect(request.__calls__[0].params.scope).toBe('all')

    const tabs = wrapper.findAll('.scope-tab')
    expect(tabs.map((t) => t.text())).toEqual(['全部', '聊天记录', '联系人', '群组'])
    expect(tabs[0].classes()).toContain('active')

    await tabs[1].trigger('click')
    await flush()
    expect(request.__calls__[1].params.scope).toBe('message')
    const after = wrapper.findAll('.scope-tab')
    expect(after[1].classes()).toContain('active')
    expect(after[0].classes()).not.toContain('active')
  })

  it('点击联系人结果 → emit openSession 带 contactId/contactType 并关窗', async () => {
    const { wrapper } = mountSearch(() => ({
      code: 0,
      data: { contactList: [{ contactId: 'U_1', contactName: '张三', contactType: 0 }] }
    }))
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('张')
    await wrapper.find('.el-button').trigger('click')
    await flush()

    await wrapper.find('.result-item').trigger('click')
    expect(wrapper.emitted('openSession')).toEqual([
      [{ contactId: 'U_1', contactType: 0 }]
    ])
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
  })

  it('点击聊天记录结果 → emit jumpMessage 带 messageId 并关窗', async () => {
    const { wrapper } = mountSearch(() => ({
      code: 0,
      data: {
        messageList: [
          {
            messageId: 77,
            contactId: 'G1',
            contactType: 1,
            sendUserId: 'U_2',
            messageContent: 'x',
            sendTime: 1
          }
        ]
      }
    }))
    wrapper.vm.show()
    await wrapper.vm.$nextTick()
    await wrapper.find('.el-input').setValue('x')
    await wrapper.find('.el-button').trigger('click')
    await flush()

    await wrapper.find('.result-item').trigger('click')
    expect(wrapper.emitted('jumpMessage')).toEqual([
      [{ contactId: 'G1', contactType: 1, messageId: 77 }]
    ])
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
  })

  it('show(initKeyword)：开窗即带该词搜索', async () => {
    const { wrapper, request } = mountSearch(() => ({ code: 0, data: {} }))
    wrapper.vm.show('预置词')
    await flush()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.__calls__[0].params.keyword).toBe('预置词')
  })
})
