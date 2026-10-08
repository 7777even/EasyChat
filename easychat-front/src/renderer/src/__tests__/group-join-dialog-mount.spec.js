import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import GroupJoinDialog from '@/components/GroupJoinDialog.vue'

/**
 * 加入群聊对话框组件的**真实挂载**测试（DOM 级）。
 *
 * 为什么选它：
 *   1. 独立对话框组件，有明确交互（输入 token/提交）
 *   2. 入群是群组域核心操作，提交参数错误会导致入群失败
 *   3. joinType 0/1 分支（直接加入 vs 待审批）是业务契约
 *
 * 测试模式参照 report-dialog-mount.spec.js：
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

function mountJoinDialog (props = {}, handler) {
  const request = makeRequestStub(handler)
  const wrapper = mount(GroupJoinDialog, {
    props: {
      modelValue: true,
      ...props
    },
    global: {
      stubs: {
        'el-dialog': {
          template: '<div v-if="modelValue" class="el-dialog"><div class="el-dialog__title">{{ title }}</div><slot /><slot name="footer" /></div>',
          props: ['modelValue', 'title', 'width', 'closeOnClickModal']
        },
        'el-tabs': {
          template: '<div class="el-tabs"><slot /></div>',
          props: ['modelValue']
        },
        'el-tab-pane': {
          template: '<div class="el-tab-pane"><div class="el-tab-pane__label">{{ label }}</div><slot /></div>',
          props: ['label', 'name']
        },
        'el-input': {
          template: '<input class="el-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @keyup.enter="$emit(\'keyup.enter\', $event)" />',
          props: ['modelValue', 'placeholder', 'maxlength', 'clearable']
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
            joinGroupQrCode: '/group/qrCode/join',
            joinGroupInvite: '/group/invite/join'
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

describe('GroupJoinDialog.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    MessageStub.success.mockClear()
    MessageStub.warning.mockClear()
  })

  it('挂载后渲染对话框标题「加入群聊」', () => {
    const { wrapper } = mountJoinDialog()
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('加入群聊')
  })

  it('渲染两个 tab（群二维码/邀请链接）', () => {
    const { wrapper } = mountJoinDialog()
    expect(wrapper.text()).toContain('群二维码')
    expect(wrapper.text()).toContain('邀请链接')
  })

  it('渲染 token 输入框', () => {
    const { wrapper } = mountJoinDialog()
    expect(wrapper.find('.el-input').exists()).toBe(true)
  })

  it('提交空 token 时提示「请输入 token」', async () => {
    const { wrapper } = mountJoinDialog()
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请输入 token')
  })

  it('提交 qrcode token：调 Request(joinGroupQrCode)，joinType=0 时关闭并 emit joined', async () => {
    const { wrapper, request } = mountJoinDialog({}, () => ({ code: 0, data: 0 }))
    await wrapper.find('.el-input').setValue('qr_token_123')
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    await flush()
    const join = request.__calls__.find((c) => c.url === '/group/qrCode/join')
    expect(join).toBeTruthy()
    expect(join.params.qrCodeToken).toBe('qr_token_123')
    expect(MessageStub.success).toHaveBeenCalledWith('已加入该群聊')
    expect(wrapper.emitted('joined')).toBeTruthy()
    expect(wrapper.emitted('joined')[0][0]).toEqual({ approved: true })
  })

  it('joinType=1 时提示「已提交入群申请」并 emit joined(approved=false)', async () => {
    const { wrapper } = mountJoinDialog({}, () => ({ code: 0, data: 1 }))
    await wrapper.find('.el-input').setValue('qr_token_123')
    await wrapper.find('.el-dialog .el-button--primary').trigger('click')
    await flush()
    expect(MessageStub.success).toHaveBeenCalledWith('已提交入群申请，等待群主或管理员同意')
    expect(wrapper.emitted('joined')[0][0]).toEqual({ approved: false })
  })
})
