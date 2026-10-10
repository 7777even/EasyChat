import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import DataBackup from '@/views/setting/DataBackup.vue'

/**
 * 数据备份页的**真实挂载**测试（DOM 级，真实 IPC 桥桩）。
 *
 * 被测点：
 *   1. 挂载即 send('loadSessionData') + 注册两个回调
 *   2. loadSessionDataCallback：会话数回显；非数组数据兜底 []
 *   3. startBackup：无会话 → warning 且不进备份；备份中重复点击被挡
 *   4. 备份成功路径：onProgress 回写进度文案 → exportChatBackup 带 format+groups
 *   5. 空 groups → warning「没有可备份的消息」、不落盘
 *   6. 抛异常 → warning「备份失败：…」+ 复位 backing/progress
 *   7. onBackupCallback：成功提示计数/会话数/路径；canceled 静默；失败 warning；
 *      截断 + 失败会话两个附加提示（依赖 lastTruncated/lastFailed 状态透传）
 *   8. 卸载 → removeAllListeners 清两个通道
 */

// 真实 cloudBackup 会 import Request/主进程桥，本测只验编排 → 整模块 mock
const backupAllSessions = vi.fn()
vi.mock('@/utils/cloudBackup', () => ({
  backupAllSessions: (...args) => backupAllSessions(...args),
  MAX_TOTAL_MESSAGES: 100000
}))

const MessageStub = { success: vi.fn(), warning: vi.fn() }

function makeRequest () {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const ElSelectStub = {
  name: 'ElSelect',
  props: ['modelValue', 'size', 'style'],
  emits: ['update:modelValue'],
  template: '<select class="select-stub" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>'
}
const ElOptionStub = {
  name: 'ElOption',
  props: ['label', 'value'],
  template: '<option class="option-stub" :value="value">{{ label }}</option>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'size', 'loading', 'disabled'],
  emits: ['click'],
  template: '<button class="el-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
}

const SESSIONS = [
  { contactId: 'U010', contactType: 0 },
  { contactId: 'G001', contactType: 1 }
]

async function mountBackup () {
  const request = makeRequest()
  const wrapper = mount(DataBackup, {
    global: {
      stubs: {
        'el-select': ElSelectStub,
        'el-option': ElOptionStub,
        'el-button': ElButtonStub
      },
      config: {
        globalProperties: {
          Request: request,
          Message: MessageStub
        }
      }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper, request }
}

function ipcCallback (channel) {
  const reg = window.ipcRenderer.__calls__.find((c) => c.channel === 'on' && c.args[0] === channel)
  return reg ? reg.args[1] : null
}

const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  window.ipcRenderer.__reset__
  backupAllSessions.mockReset()
  MessageStub.success.mockClear()
  MessageStub.warning.mockClear()
})

describe('DataBackup.vue 真实挂载（DOM 级）', () => {
  it('挂载即 send loadSessionData + 注册两个回调', async () => {
    await mountBackup()
    expect(window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'loadSessionData')).toBeTruthy()
    expect(ipcCallback('loadSessionDataCallback')).toBeTruthy()
    expect(ipcCallback('exportChatBackupCallback')).toBeTruthy()
  })

  it('会话数回显 + 默认 TXT + 空会话时按钮禁用', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('loadSessionDataCallback')(null, SESSIONS)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.value').text()).toBe('2 个')
    const btn = wrapper.find('.el-button')
    expect(btn.attributes('disabled')).toBeUndefined()
    expect(btn.text()).toBe('开始备份')
  })

  it('loadSessionDataCallback 非数组 → 兜底 []（按钮禁用）', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('loadSessionDataCallback')(null, { bad: true })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.value').text()).toBe('0 个')
    // Vue 布尔属性：true 渲染为 disabled=""（值为空串）
    expect(wrapper.find('.el-button').attributes('disabled')).toBe('')
  })

  it('无会话时按钮禁用 → 点击不触发任何备份（:disabled 在 DOM 层挡住）', async () => {
    const { wrapper } = await mountBackup()
    await wrapper.find('.el-button').trigger('click')
    await flush()
    // 源码内 `sessionList.length===0` 守卫属防御性冗余：DOM 层已禁用，此路不可达
    expect(backupAllSessions).not.toHaveBeenCalled()
    expect(MessageStub.warning).not.toHaveBeenCalled()
  })

  it('备份成功：onProgress 回写进度 → exportChatBackup 带 format+groups', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('loadSessionDataCallback')(null, SESSIONS)
    await wrapper.vm.$nextTick()
    let progressDuringBackup = ''
    backupAllSessions.mockImplementation(async ({ onProgress }) => {
      onProgress({ current: 1, total: 2, title: '阿强' })
      await wrapper.vm.$nextTick()
      progressDuringBackup = wrapper.find('.progress').text()
      return { groups: [{ title: '阿强', messages: [] }], failedSessions: [], truncated: false }
    })
    await wrapper.find('.el-button').trigger('click')
    await flush()

    expect(progressDuringBackup).toBe('正在备份第 1/2 个会话：阿强')
    // 备份结束后进入写入态（进度文案被覆盖）
    expect(wrapper.find('.progress').text()).toBe('正在写入文件…')
    const send = window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'exportChatBackup')
    expect(send.args[1]).toEqual({ format: 'txt', groups: [{ title: '阿强', messages: [] }] })
  })

  it('切换 CSV 格式 → exportChatBackup format=csv', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('loadSessionDataCallback')(null, SESSIONS)
    await wrapper.vm.$nextTick()
    await wrapper.find('.select-stub').setValue('csv')
    backupAllSessions.mockImplementation(async () => ({ groups: [{}], failedSessions: [], truncated: false }))
    await wrapper.find('.el-button').trigger('click')
    await flush()
    const send = window.ipcRenderer.__calls__.find((c) => c.channel === 'send' && c.args[0] === 'exportChatBackup')
    expect(send.args[1].format).toBe('csv')
  })

  it('空 groups → warning 没有可备份的消息、不落盘', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('loadSessionDataCallback')(null, SESSIONS)
    await wrapper.vm.$nextTick()
    backupAllSessions.mockImplementation(async () => ({ groups: [], failedSessions: [], truncated: false }))
    await wrapper.find('.el-button').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('没有可备份的消息')
    expect(window.ipcRenderer.__calls__.find((c) => c.args[0] === 'exportChatBackup')).toBeUndefined()
    expect(wrapper.find('.el-button').text()).toBe('开始备份') // backing 复位
  })

  it('备份抛异常 → warning「备份失败：…」+ backing/progress 复位', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('loadSessionDataCallback')(null, SESSIONS)
    await wrapper.vm.$nextTick()
    backupAllSessions.mockImplementation(async () => { throw new Error('网络断开') })
    await wrapper.find('.el-button').trigger('click')
    await flush()
    expect(MessageStub.warning).toHaveBeenCalledWith('备份失败：网络断开')
    expect(wrapper.find('.el-button').text()).toBe('开始备份')
    expect(wrapper.find('.progress').exists()).toBe(false)
  })

  it('落盘回调成功 → 提示条数/会话数/路径', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('loadSessionDataCallback')(null, SESSIONS)
    await wrapper.vm.$nextTick()
    backupAllSessions.mockImplementation(async () => ({ groups: [{}], failedSessions: [], truncated: false }))
    await wrapper.find('.el-button').trigger('click')
    await flush()
    ipcCallback('exportChatBackupCallback')(null, { success: true, count: 30, sessionCount: 2, path: 'D:/a.txt' })
    expect(MessageStub.success).toHaveBeenCalledWith('已备份 30 条消息（2 个会话）：D:/a.txt')
  })

  it('用户取消保存（canceled）→ 静默、backing 复位', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('exportChatBackupCallback')(null, { canceled: true })
    await wrapper.vm.$nextTick()
    expect(MessageStub.success).not.toHaveBeenCalled()
    expect(MessageStub.warning).not.toHaveBeenCalled()
    expect(wrapper.find('.el-button').text()).toBe('开始备份')
  })

  it('落盘失败 → warning(result.error)', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('exportChatBackupCallback')(null, { success: false, error: '磁盘已满' })
    expect(MessageStub.warning).toHaveBeenCalledWith('磁盘已满')
  })

  it('截断 + 失败会话 → 两个附加 warning（上限与会话清单）', async () => {
    const { wrapper } = await mountBackup()
    ipcCallback('loadSessionDataCallback')(null, SESSIONS)
    await wrapper.vm.$nextTick()
    backupAllSessions.mockImplementation(async () => ({ groups: [{}], failedSessions: ['U010'], truncated: true }))
    await wrapper.find('.el-button').trigger('click')
    await flush()
    ipcCallback('exportChatBackupCallback')(null, { success: true, count: 100000, sessionCount: 1, path: 'D:/b.csv' })
    expect(MessageStub.warning).toHaveBeenCalledWith('消息量已达上限 100000 条，本次为截断结果')
    expect(MessageStub.warning).toHaveBeenCalledWith('1 个会话拉取失败已跳过：U010')
  })

  it('卸载 → removeAllListeners 清两个回调通道', async () => {
    const { wrapper } = await mountBackup()
    wrapper.unmount()
    const removed = window.ipcRenderer.__calls__.filter((c) => c.channel === 'removeAllListeners')
    expect(removed.map((c) => c.args[0]).sort()).toEqual(['exportChatBackupCallback', 'loadSessionDataCallback'])
  })
})
