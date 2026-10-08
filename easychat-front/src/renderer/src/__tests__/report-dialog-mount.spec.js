import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ReportDialog from '@/components/ReportDialog.vue'

/**
 * 举报对话框组件的**真实挂载**测试（DOM 级）。
 *
 * 为什么选它：
 *   1. 独立对话框组件，有明确交互（选择原因/输入描述/提交）
 *   2. 举报是内容治理的前端入口，提交参数错误会导致后端误判
 *   3. 与已测组件不同族（对话框 vs 媒体消息/选择器）
 *
 * 测试模式参照 emoji-picker-mount.spec.js：
 *   mount + globalProperties（Request/Api/Message）+ Element Plus 组件 stub
 */

const MessageStub = { error: vi.fn(), warning: vi.fn(), success: vi.fn() }

/** 可控的 Request 桩：按 url 返回预设结果，并记录调用 */
function makeRequestStub (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountReport (props = {}, handler) {
  const request = makeRequestStub(handler)
  const wrapper = mount(ReportDialog, {
    props: {
      modelValue: true,
      type: 'message',
      messageId: '1001',
      ...props
    },
    global: {
      stubs: {
        'el-dialog': {
          template: '<div v-if="modelValue" class="el-dialog"><div class="el-dialog__title">{{ title }}</div><slot /><slot name="footer" /></div>',
          props: ['modelValue', 'title', 'width', 'closeOnClickModal']
        },
        'el-radio-group': {
          template: '<div class="el-radio-group"><slot /></div>',
          props: ['modelValue']
        },
        'el-radio': {
          template: '<label class="el-radio"><slot /></label>',
          props: ['label']
        },
        'el-input': {
          template: '<input class="el-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
          props: ['modelValue', 'type', 'rows', 'maxlength', 'showWordLimit', 'placeholder']
        },
        'el-button': {
          template: '<button :class="[\'el-button\', type ? \'el-button--\' + type : \'\']"><slot /></button>',
          props: ['type', 'loading']
        }
      },
      config: {
        globalProperties: {
          Request: request,
          Api: {
            reportChat: '/chat/reportChat',
            reportMoment: '/chat/reportMoment'
          },
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper, request }
}

/** 等待异步链完成（submit → Request → 关闭） */
async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

describe('ReportDialog.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    MessageStub.success.mockClear()
  })

  it('挂载后渲染对话框标题（默认 message → 举报聊天消息）', () => {
    const { wrapper } = mountReport()
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('举报聊天消息')
  })

  it('type 为 moment 时标题为「举报朋友圈动态」', () => {
    const { wrapper } = mountReport({ type: 'moment' })
    expect(wrapper.text()).toContain('举报朋友圈动态')
  })

  it('type 为 comment 时标题为「举报评论」', () => {
    const { wrapper } = mountReport({ type: 'comment' })
    expect(wrapper.text()).toContain('举报评论')
  })

  it('渲染 5 个举报原因选项', () => {
    const { wrapper } = mountReport()
    expect(wrapper.findAll('.el-radio').length).toBe(5)
  })

  it('渲染描述输入框', () => {
    const { wrapper } = mountReport()
    expect(wrapper.find('.el-input').exists()).toBe(true)
  })

  it('提交举报：调 Request(reportChat) 带 reason 和 messageId，成功后关闭弹窗', async () => {
    const { wrapper, request } = mountReport(() => ({ code: 0, data: true }))
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    await flush()
    const report = request.__calls__.find((c) => c.url === '/chat/reportChat')
    expect(report).toBeTruthy()
    expect(report.params.reason).toBe(0)
    expect(report.params.messageId).toBe('1001')
    expect(report.params.description).toBe('')
    expect(MessageStub.success).toHaveBeenCalledWith('举报已提交，感谢您的反馈')
    // 成功后关闭弹窗（emit update:modelValue false）
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue').at(-1)[0]).toBe(false)
  })

  it('输入描述后提交，Request 带 description', async () => {
    const { wrapper, request } = mountReport(() => ({ code: 0, data: true }))
    await wrapper.find('.el-input').setValue('测试描述')
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    await flush()
    const report = request.__calls__.find((c) => c.url === '/chat/reportChat')
    expect(report.params.description).toBe('测试描述')
  })

  it('type 为 moment 时提交调 Request(reportMoment) 带 momentId', async () => {
    const { wrapper, request } = mountReport(
      { type: 'moment', momentId: '2001' },
      () => ({ code: 0, data: true })
    )
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    await flush()
    const report = request.__calls__.find((c) => c.url === '/chat/reportMoment')
    expect(report).toBeTruthy()
    expect(report.params.momentId).toBe('2001')
    expect(report.params.commentId).toBeNull()
  })
})
