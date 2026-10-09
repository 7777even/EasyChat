import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MessageSend from '@/views/chat/MessageSend.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { useSysSettingStore } from '@/stores/SysSettingStore'
import { ipcCalls } from './setup'

/**
 * 消息发送面板的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 空白点击发送 → 「不能发送空白信息」且不发请求
 *   2. 文本发送：sendMessage 参数（sessionId/contactId/clientId）、emit sendMessage4Local、
 *      ipc addLocalMessage + registerPendingAck、输入框清空
 *   3. shift+enter 换行不发送；enter 发送
 *   4. 引用回复渲染 + extraData 带 quote；发送后清空引用
 *   5. 草稿：切会话恢复 draft + 保存草稿（ipc saveSessionDraft）
 *   6. 单聊无 @ 按钮 / 群聊有；拉成员后可选人、群主可见「@所有人」
 *   7. 位置发送 messageType=25；空地址 warning
 *   8. 拖入 11 个文件 → 超限 Confirm；空文件 → 空文件 Confirm
 */

const MessageStub = { warning: vi.fn(), success: vi.fn(), error: vi.fn() }
const ConfirmStub = vi.fn()

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: { messageId: 101 } }
  })
  fn.__calls__ = calls
  return fn
}

vi.mock('@/views/contact/SearchAdd.vue', () => ({
  default: { name: 'SearchAdd', template: '<div class="search-add-stub" />' }
}))
vi.mock('@/views/chat/EmojiPicker.vue', () => ({
  default: { name: 'EmojiPicker', template: '<div class="emoji-picker-stub" />' }
}))

const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'type', 'rows', 'maxlength', 'placeholder', 'clearable', 'showWordLimit', 'spellcheck', 'inputStyle', 'resize', 'size'],
  emits: ['update:modelValue', 'keydown', 'input', 'paste', 'keyup.enter'],
  template: `
    <textarea
      v-if="type === 'textarea'"
      class="el-input-native"
      :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value); $emit('input', $event)"
      @keydown="$emit('keydown', $event)"
      @paste="$emit('paste', $event)"
    ></textarea>
    <input
      v-else
      class="el-input-native"
      :value="modelValue"
      :placeholder="placeholder"
      @input="$emit('update:modelValue', $event.target.value)"
      @keydown="$emit('keydown', $event)"
    />`
}
const ElPopoverStub = {
  name: 'ElPopover',
  props: ['visible', 'trigger', 'placement', 'teleported', 'popperStyle', 'hideAfter'],
  emits: ['show', 'hide'],
  template: `<div class="el-popover"><div class="popover-reference"><slot name="reference" /></div><div v-if="visible" class="popover-popper"><slot /></div></div>`
}
const ElTabsStub = { name: 'ElTabs', props: ['modelValue'], template: '<div class="el-tabs"><slot /></div>' }
const ElTabPaneStub = { name: 'ElTabPane', props: ['label', 'name'], template: '<div class="el-tab-pane"><slot /></div>' }
const ElUploadStub = {
  name: 'ElUpload',
  props: ['showFileList', 'multiple', 'limit', 'httpRequest', 'onExceed', 'name'],
  template: '<div class="el-upload"><slot /></div>',
  methods: { clearFiles () {} }
}
const ElDialogStub = {
  name: 'ElDialog',
  props: ['modelValue', 'title', 'width', 'closeOnClickModal', 'appendToBody', 'showClose'],
  emits: ['update:modelValue', 'close'],
  template: '<div v-if="modelValue" class="el-dialog"><div class="dialog-title">{{ title }}</div><slot /><slot name="footer" /></div>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'disabled'],
  emits: ['click'],
  template: '<button class="el-button" :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>'
}
const ElProgressStub = { name: 'ElProgress', props: ['percentage', 'status'], template: '<div class="el-progress" />' }

function mountSend (session, handler) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useUserInfoStore().setInfo({ userId: 'U001', nickName: '我' })
  useSysSettingStore().setSetting({ 0: 10, 1: 100, 2: 100, 3: 20, 4: 100, 5: 500 })
  const request = makeRequest(handler)
  const wrapper = mount(MessageSend, {
    props: { currentChatSession: session },
    global: {
      stubs: {
        'el-input': ElInputStub,
        'el-popover': ElPopoverStub,
        'el-tabs': ElTabsStub,
        'el-tab-pane': ElTabPaneStub,
        'el-upload': ElUploadStub,
        'el-dialog': ElDialogStub,
        'el-button': ElButtonStub,
        'el-progress': ElProgressStub
      },
      plugins: [pinia],
      config: {
        globalProperties: {
          Request: request,
          Api: {
            sendMessage: '/message/send',
            saveSessionDraft: '/session/saveDraft',
            getGroupInfo4Chat: '/group/getGroupInfo4Chat',
            uploadFile: '/file/upload'
          },
          Message: MessageStub,
          Confirm: ConfirmStub
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

function enterEvent (shiftKey) {
  const ev = new KeyboardEvent('keydown', { key: 'Enter', shiftKey })
  Object.defineProperty(ev, 'keyCode', { value: 13 })
  return ev
}

const SESSION = { contactId: 'U_1', sessionId: 'S_1', contactType: 0 }
const GROUP_SESSION = { contactId: 'G_1', sessionId: 'S_2', contactType: 1 }

beforeEach(() => {
  MessageStub.warning.mockClear()
  MessageStub.success.mockClear()
  ConfirmStub.mockClear()
  ipcCalls.length = 0
  window.api = { sendTypingStatus: vi.fn() }
})

describe('MessageSend.vue 真实挂载（DOM 级）', () => {
  it('空白点击发送 → 「不能发送空白信息」且不发请求', async () => {
    const { wrapper, request } = mountSend(SESSION)
    await wrapper.find('.send-btn').trigger('click')
    expect(request).not.toHaveBeenCalled()
    expect(wrapper.find('.popover-popper').text()).toContain('不能发送空白信息')
  })

  it('文本发送：参数齐全 + emit + ipc 落库/ACK 注册 + 清空输入', async () => {
    const { wrapper, request } = mountSend(SESSION, () => ({
      code: 0,
      data: { messageId: 555, sendTime: 1700000000000 }
    }))
    await wrapper.find('.el-input-native').setValue('你好')
    await wrapper.find('.send-btn').trigger('click')
    await flush()

    const call = request.__calls__[0]
    expect(call.url).toBe('/message/send')
    expect(call.params.messageContent).toBe('你好')
    expect(call.params.contactId).toBe('U_1')
    expect(call.params.messageType).toBe(2)
    expect(call.params.clientId).toBeTruthy()
    expect(call.params.extraData).toBeNull()
    expect(call.params.atUserIds).toBeNull()

    expect(wrapper.emitted('sendMessage4Local')).toHaveLength(1)
    const localMsg = wrapper.emitted('sendMessage4Local')[0][0]
    expect(localMsg.messageId).toBe(555)
    // sessionId/sendUserId 落在本地消息对象上（不进 HTTP params）
    expect(localMsg.sessionId).toBe('S_1')
    expect(localMsg.sendUserId).toBe('U001')
    expect(ipcCalls.find((c) => c.args[0] === 'addLocalMessage')).toBeTruthy()
    const ack = ipcCalls.find((c) => c.args[0] === 'registerPendingAck')
    expect(ack).toBeTruthy()
    expect(ack.args[1].clientId).toBe(call.params.clientId)
    expect(wrapper.find('.el-input-native').element.value).toBe('')
  })

  it('enter 发送；shift+enter 不发送（换行）', async () => {
    const { wrapper, request } = mountSend(SESSION)
    await wrapper.find('.el-input-native').setValue('在吗')

    await wrapper.findComponent(ElInputStub).vm.$emit('keydown', enterEvent(true))
    await flush()
    expect(request).not.toHaveBeenCalled()

    await wrapper.findComponent(ElInputStub).vm.$emit('keydown', enterEvent(false))
    await flush()
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('引用回复：渲染引用条 + extraData 带 quote；发送后引用清空', async () => {
    const { wrapper, request } = mountSend(SESSION, () => ({
      code: 0,
      data: { messageId: 6 }
    }))
    wrapper.vm.setQuote({ messageId: 9, quoteNickName: '李四', quoteContent: '原文' })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.quote-panel').exists()).toBe(true)
    expect(wrapper.find('.quote-name').text()).toBe('李四')
    expect(wrapper.find('.quote-content').text()).toBe('原文')

    await wrapper.find('.el-input-native').setValue('回复内容')
    await wrapper.find('.send-btn').trigger('click')
    await flush()

    const extra = JSON.parse(request.__calls__[0].params.extraData)
    expect(extra).toEqual({ quoteId: 9, quoteContent: '原文', quoteNickName: '李四' })
    expect(wrapper.find('.quote-panel').exists()).toBe(false)
  })

  it('切会话：恢复新会话 draft；旧会话草稿经 ipc saveSessionDraft 保存', async () => {
    const { wrapper } = mountSend({ ...SESSION, draft: '上次没打完' })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.el-input-native').element.value).toBe('上次没打完')

    // 输入后切换会话 → 保存旧草稿
    await wrapper.find('.el-input-native').setValue('草稿A')
    await wrapper.setProps({ currentChatSession: { contactId: 'U_2', sessionId: 'S_9', contactType: 0 } })
    await flush()
    const saved = ipcCalls.find((c) => c.args[0] === 'saveSessionDraft')
    expect(saved).toBeTruthy()
    expect(saved.args[1]).toEqual({ contactId: 'U_1', draft: '草稿A' })
    // 新会话无草稿 → 输入框清空
    expect(wrapper.find('.el-input-native').element.value).toBe('')
  })

  it('单聊无 @ 按钮；群聊有 @ 按钮', async () => {
    const { wrapper: w1 } = mountSend(SESSION)
    expect(w1.find('.at-icon').exists()).toBe(false)
    const { wrapper: w2 } = mountSend(GROUP_SESSION)
    expect(w2.find('.at-icon').exists()).toBe(true)
  })

  it('群聊 @ 面板：拉成员 → 可选人插入 @Ux；群主可见「@所有人」并标记', async () => {
    const { wrapper, request } = mountSend(GROUP_SESSION, (opts) => {
      if (opts.url === '/group/getGroupInfo4Chat') {
        return {
          code: 0,
          data: {
            userContactList: [
              { userId: 'U001', contactName: '我', role: 0 },
              { userId: 'U002', contactName: '乙', role: 2 }
            ]
          }
        }
      }
      return { code: 0, data: { messageId: 1 } }
    })
    const textarea = () => wrapper.find('.input-area .el-input-native')

    await wrapper.find('.at-icon').trigger('click')
    await flush()

    expect(request.__calls__[0].url).toBe('/group/getGroupInfo4Chat')
    expect(request.__calls__[0].params).toEqual({ groupId: 'G_1' })
    // 群主 → @所有人 可见；成员乙在列表
    const items = wrapper.findAll('.at-item')
    expect(items[0].text()).toContain('@所有人')
    expect(wrapper.text()).toContain('乙')

    // 选人 → 正文插入 @U002（面板含「@所有人」项，按文本定位成员项）
    const memberItems = wrapper.findAll('.at-item').filter((n) => !n.classes().includes('at-item-all'))
    await memberItems.find((n) => n.text().includes('乙')).trigger('click')
    expect(textarea().element.value).toBe('@U002 ')

    // 点 @所有人 → 正文含 @所有人 + atAllEnabled（发送时进 extraData）
    await wrapper.find('.at-icon').trigger('click')
    await flush()
    await wrapper.find('.at-item-all').trigger('click')
    expect(textarea().element.value).toContain('@所有人')

    await wrapper.find('.send-btn').trigger('click')
    await flush()
    const sendCall = request.__calls__.find((c) => c.url === '/message/send')
    const extra = JSON.parse(sendCall.params.extraData)
    expect(extra.atAll).toBe(true)
    expect(sendCall.params.atUserIds).toBe('U002')
  })

  it('普通成员手工键入 @所有人 → extraData 不带 atAll（权限降级）', async () => {
    const { wrapper, request } = mountSend(GROUP_SESSION, (opts) => {
      if (opts.url === '/group/getGroupInfo4Chat') {
        return {
          code: 0,
          data: {
            userContactList: [
              { userId: 'U001', contactName: '我', role: 2 },
              { userId: 'U002', contactName: '乙', role: 2 }
            ]
          }
        }
      }
      return { code: 0, data: { messageId: 2 } }
    })
    // 拉取成员（含本人 role=2）后再看 @所有人 选项
    await wrapper.find('.at-icon').trigger('click')
    await flush()
    expect(wrapper.find('.at-item-all').exists()).toBe(false)

    // textarea 用 .input-area 限定，避开面板搜索框
    await wrapper.find('.input-area .el-input-native').setValue('@所有人 收到请回复')
    await wrapper.find('.send-btn').trigger('click')
    await flush()
    const sendCall = request.__calls__.find((c) => c.url === '/message/send')
    expect(sendCall.params.extraData).toBeNull()
  })

  it('位置分享：空地址 warning 不发；有地址 → messageType=25 + extraData.location', async () => {
    const { wrapper, request } = mountSend(SESSION, () => ({
      code: 0,
      data: { messageId: 77 }
    }))
    await wrapper.find('.icon-top').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.el-dialog').exists()).toBe(true)

    // 空字符串地址按钮 disabled（守卫在 UI 层）；纯空白地址绕过 disabled → handler 内 trim 兜底
    const sendBtn = () =>
      wrapper.findAll('.el-dialog .el-button').find((b) => b.text().includes('发送'))
    expect(sendBtn().attributes('disabled')).toBe('')

    await wrapper.find('.el-dialog .el-input-native').setValue('   ')
    await wrapper.vm.$nextTick()
    expect(sendBtn().attributes('disabled')).toBeUndefined()
    await sendBtn().trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请输入地点名称')
    expect(request).not.toHaveBeenCalled()

    await wrapper.find('.el-dialog .el-input-native').setValue('天安门')
    await wrapper.vm.$nextTick()
    await sendBtn().trigger('click')
    await flush()

    const loc = request.__calls__.find((c) => c.url === '/message/send')
    expect(loc.params.messageType).toBe(25)
    expect(loc.params.messageContent).toBe('天安门')
    expect(JSON.parse(loc.params.extraData)).toEqual({
      location: '天安门',
      latitude: null,
      longitude: null
    })
  })

  it('拖入 11 个文件 → 超限 Confirm；不发请求', async () => {
    const { wrapper, request } = mountSend(SESSION)
    const files = Array.from({ length: 11 }, (_, i) => new File(['x'], `f${i}.txt`, { type: 'text/plain' }))
    await wrapper.find('.input-area').trigger('drop', { dataTransfer: { files } })
    await flush()
    expect(ConfirmStub).toHaveBeenCalledWith({
      message: '一次最多可以上传10个文件',
      showCancelBtn: false
    })
    expect(request).not.toHaveBeenCalled()
  })

  it('拖入空文件 → 空文件 Confirm，不发 sendMessage', async () => {
    const { wrapper, request } = mountSend(SESSION)
    const file = new File([''], 'empty.pdf', { type: 'application/pdf' })
    await wrapper.find('.input-area').trigger('drop', { dataTransfer: { files: [file] } })
    await flush()
    expect(ConfirmStub).toHaveBeenCalledWith({
      message: '"empty.pdf"是一个空文件无法发送，请重新选择',
      showCancelBtn: false
    })
    expect(request).not.toHaveBeenCalled()
  })

  it('搜索入口 → emit showSearch', async () => {
    const { wrapper } = mountSend(SESSION)
    await wrapper.find('.icon-search').trigger('click')
    expect(wrapper.emitted('showSearch')).toHaveLength(1)
  })

  it('输入触发 sendTypingStatus（立即 + 3 秒停止）', async () => {
    vi.useFakeTimers()
    try {
      const { wrapper } = mountSend(SESSION)
      await wrapper.find('.el-input-native').setValue('在')
      expect(window.api.sendTypingStatus).toHaveBeenCalledWith('U_1', 'S_1', true)
      expect(window.api.sendTypingStatus).toHaveBeenCalledTimes(1)
      vi.advanceTimersByTime(3000)
      expect(window.api.sendTypingStatus).toHaveBeenLastCalledWith('U_1', 'S_1', false)
      expect(window.api.sendTypingStatus).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
})
