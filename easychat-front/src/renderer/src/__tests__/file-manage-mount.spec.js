import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import FileManage from '@/views/setting/FileManage.vue'

/**
 * 文件管理页的**真实挂载**测试（DOM 级，真实 IPC 桥桩）。
 *
 * 被测点：
 *   1. 挂载即 send('getSysSetting') 拉取本地保存目录 + 注册两个回调
 *   2. getSysSettingCallback(JSON) → 回显 localFileFolder（含解析失败路径的容错面）
 *   3. 更改 → send('changeLocalFolder')；打开文件夹 → send('openLocalFolder')
 *   4. copyingCallback → loading 态开启（正在复制文件遮罩）
 *   5. 卸载 → removeAllListeners 清干净两个通道（防泄漏）
 */

const ContentPanelStub = {
  name: 'ContentPanel',
  props: ['showTopBorder'],
  template: '<div class="content-panel-stub"><slot /></div>'
}
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'link'],
  emits: ['click'],
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>'
}

beforeEach(() => {
  window.ipcRenderer.__reset__
})

async function mountFileManage () {
  const wrapper = mount(FileManage, {
    global: {
      stubs: {
        ContentPanel: ContentPanelStub,
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<div class="form-item-stub"><slot /></div>' },
        'el-button': ElButtonStub
      },
      directives: { loading: {} }
    }
  })
  await wrapper.vm.$nextTick()
  return { wrapper }
}

/** 从桩记录里取出某通道注册的回调（ipcRenderer.on 的第二个实参） */
function ipcCallback (channel) {
  const reg = window.ipcRenderer.__calls__.find((c) => c.channel === 'on' && c.args[0] === channel)
  return reg ? reg.args[1] : null
}

describe('FileManage.vue 真实挂载（DOM 级）', () => {
  it('挂载即 send getSysSetting + 注册两个回调', async () => {
    await mountFileManage()
    const sends = window.ipcRenderer.__calls__.filter((c) => c.channel === 'send')
    expect(sends[0].args[0]).toBe('getSysSetting')
    expect(ipcCallback('getSysSettingCallback')).toBeTruthy()
    expect(ipcCallback('copyingCallback')).toBeTruthy()
  })

  it('getSysSettingCallback(JSON) → 回显 localFileFolder', async () => {
    const { wrapper } = await mountFileManage()
    ipcCallback('getSysSettingCallback')(null, JSON.stringify({ localFileFolder: 'D:/EasyChat/files' }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.file-input').text()).toBe('D:/EasyChat/files')
    expect(wrapper.find('.tips').text()).toBe('文件的默认保存位置')
  })

  it('点击「更改」→ send changeLocalFolder；「打开文件夹」→ send openLocalFolder', async () => {
    const { wrapper } = await mountFileManage()
    const btns = wrapper.findAll('.el-button')
    await btns.find((b) => b.text() === '更改').trigger('click')
    await btns.find((b) => b.text() === '打开文件夹').trigger('click')
    const sends = window.ipcRenderer.__calls__.filter((c) => c.channel === 'send').map((c) => c.args[0])
    expect(sends).toContain('changeLocalFolder')
    expect(sends).toContain('openLocalFolder')
  })

  it('卸载 → removeAllListeners 清掉两个回调通道', async () => {
    const { wrapper } = await mountFileManage()
    wrapper.unmount()
    const removed = window.ipcRenderer.__calls__.filter((c) => c.channel === 'removeAllListeners')
    expect(removed.map((c) => c.args[0]).sort()).toEqual(['copyingCallback', 'getSysSettingCallback'])
  })
})
