import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import SensitiveWord from '@/views/admin/SensitiveWord.vue'

/**
 * 管理端敏感词管理的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 筛选区与工具栏（新增/导入/导出）
 *   2. 列表插槽：级别标签三色与文本、状态两色、时间兜底
 *   3. openSave 两态（新增默认值 / 编辑回填）
 *   4. submitSave：空词条守卫 + 新增与编辑两条成功文案
 *   5. removeWord：Confirm 文案 + 删除后重载
 *   6. onFileChange：csv 识别（决定是否附带 level/status）
 *   7. submitImport：未选文件守卫 + FormData 内容 + 计数提示 + 复位
 *   8. exportWords：blob 请求 + 触发下载
 */

const TableStub = {
  name: 'Table',
  props: ['columns', 'fetch', 'dataSource', 'options'],
  emits: ['rowClick', 'rowSelected'],
  mounted() {
    if (this.fetch) this.fetch()
  },
  template: `<div class="table-stub">
    <template v-for="(col, ci) in columns" :key="ci">
      <div v-if="col.scopedSlots" class="col-slot" :data-col="col.scopedSlots">
        <div v-for="(row, ri) in (dataSource.list || [])" :key="ri" class="slot-cell" :data-ri="ri">
          <slot :name="col.scopedSlots" :index="ri" :row="row" />
        </div>
      </div>
    </template>
  </div>`
}

const stubs = {
  Table: TableStub,
  'el-card': { name: 'el-card', template: '<div class="el-card"><slot /></div>' },
  'el-form': { name: 'el-form', props: ['model', 'labelWidth', 'labelPosition'], template: '<form><slot /></form>' },
  'el-form-item': { name: 'el-form-item', props: ['label', 'required'], template: '<div><slot /></div>' },
  'el-row': { name: 'el-row', template: '<div><slot /></div>' },
  'el-col': { name: 'el-col', props: ['span', 'style'], template: '<div><slot /></div>' },
  'el-select': {
    name: 'el-select',
    props: ['modelValue', 'clearable', 'placeholder', 'style', 'disabled'],
    emits: ['update:modelValue'],
    template: '<div class="el-select"><slot /></div>'
  },
  'el-option': {
    name: 'el-option',
    props: ['label', 'value'],
    template: '<div class="el-option" :data-label="label" :data-value="value"></div>'
  },
  'el-input': {
    name: 'el-input',
    props: ['modelValue', 'maxlength', 'showWordLimit', 'clearable', 'placeholder'],
    emits: ['update:modelValue'],
    template: `<input class="el-input" :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value)" />`
  },
  'el-button': {
    name: 'el-button',
    props: ['type'],
    emits: ['click'],
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`
  },
  'el-dialog': {
    name: 'el-dialog',
    props: ['title', 'modelValue', 'width'],
    template: `<div class="el-dialog" v-if="modelValue"><div class="dialog-title">{{ title }}</div><slot /><slot name="footer" /></div>`
  },
  'el-tag': { name: 'el-tag', props: ['size', 'type'], template: '<span class="el-tag" :data-type="type"><slot /></span>' },
  'el-radio-group': { name: 'el-radio-group', props: ['modelValue', 'disabled'], template: '<div class="el-radio-group"><slot /></div>' },
  'el-radio': { name: 'el-radio', props: ['label'], template: '<div class="el-radio"><slot /></div>' }
}

let request
let confirmCalls
let messageCalls

function makeRequest(handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountPage(handler) {
  request = makeRequest(handler)
  const wrapper = mount(SensitiveWord, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: {
            loadSensitiveWord: '/admin/loadSensitiveWord',
            saveSensitiveWord: '/admin/saveSensitiveWord',
            deleteSensitiveWord: '/admin/deleteSensitiveWord',
            importSensitiveWord: '/admin/importSensitiveWord',
            exportSensitiveWord: '/admin/exportSensitiveWord'
          },
          Confirm: (opts) => { confirmCalls.push(opts) },
          Message: {
            success: (m) => messageCalls.push(['success', m]),
            warning: (m) => messageCalls.push(['warning', m])
          }
        }
      }
    }
  })
  return wrapper
}

function listData(list = []) {
  return { code: 0, data: { pageNo: 1, pageSize: 15, totalCount: list.length, list } }
}

function word(over = {}) {
  return {
    id: 1,
    word: '敏感词A',
    level: 3,
    status: 1,
    createTime: new Date(2026, 9, 1, 9, 5).getTime(),
    ...over
  }
}

describe('SensitiveWord.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    confirmCalls = []
    messageCalls = []
  })

  it('筛选区与工具栏', () => {
    const wrapper = mountPage(() => listData())
    expect(wrapper.findAll('.el-input')).toHaveLength(1)
    const selects = wrapper.findAll('.el-select')
    expect(selects[0].findAll('.el-option').map((o) => o.attributes('data-label'))).toEqual([
      '提醒',
      '替换',
      '禁止发送'
    ])
    expect(selects[1].findAll('.el-option').map((o) => o.attributes('data-label'))).toEqual(['启用', '停用'])
    expect(wrapper.find('.toolbar').findAll('.el-button').map((b) => b.text())).toEqual([
      '新增词条',
      '批量导入',
      '导出 CSV'
    ])
  })

  it('Table 5 列与 4 个 scopedSlots', () => {
    const wrapper = mountPage(() => listData())
    const columns = wrapper.findComponent({ name: 'Table' }).props('columns')
    expect(columns).toHaveLength(5)
    expect(columns.filter((c) => c.scopedSlots).map((c) => c.scopedSlots)).toEqual([
      'slotLevel',
      'slotStatus',
      'slotCreateTime',
      'slotOperation'
    ])
  })

  it('级别插槽：标签类型映射与越界兜底', async () => {
    const wrapper = mountPage(() => listData([
      word({ id: 1, level: 1 }),
      word({ id: 2, level: 2 }),
      word({ id: 3, level: 3 }),
      word({ id: 4, level: 9 })
    ]))
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotLevel"]').findAll('.slot-cell')
    expect(cells.map((c) => c.find('.el-tag').text())).toEqual(['提醒', '替换', '禁止发送', '-'])
    expect(cells.map((c) => c.find('.el-tag').attributes('data-type'))).toEqual([
      'info',
      'warning',
      'danger',
      'info'
    ])
  })

  it('状态插槽两态颜色', async () => {
    const wrapper = mountPage(() => listData([word({ status: 1 }), word({ id: 2, status: 0 })]))
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotStatus"]').findAll('.slot-cell')
    expect(cells[0].find('span').text()).toBe('启用')
    expect(cells[0].find('span').attributes('style')).toContain('rgb(103, 194, 58)')
    expect(cells[1].find('span').text()).toBe('停用')
    expect(cells[1].find('span').attributes('style')).toContain('rgb(144, 147, 153)')
  })

  it('创建时间插槽格式化与兜底', async () => {
    const wrapper = mountPage(() => listData([
      word({ id: 1, createTime: new Date(2026, 9, 1, 9, 5).getTime() }),
      word({ id: 2, createTime: null })
    ]))
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotCreateTime"]').findAll('.slot-cell')
    expect(cells.map((c) => c.text())).toEqual(['2026-10-01 09:05', '-'])
  })

  it('查询 → 参数带三个筛选项', async () => {
    const wrapper = mountPage(() => listData())
    await wrapper.findAll('.el-input')[0].setValue('测试')
    await wrapper.findAll('.el-select')[0].findAll('.el-option')[1].trigger('click')
    await wrapper.find('.toolbar') // 占位：确保已挂载
    await wrapper.findAll('.el-button').find((b) => b.text() === '查询').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__[1].params).toMatchObject({ keyword: '测试' })
  })

  it('重置 → 清空筛选 + 重新拉取', async () => {
    const wrapper = mountPage(() => listData())
    wrapper.vm.searchForm.keyword = 'x'
    wrapper.vm.searchForm.level = 2
    wrapper.vm.searchForm.status = 0
    await wrapper.findAll('.el-button').find((b) => b.text() === '重置').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.searchForm).toMatchObject({ keyword: '', level: null, status: null })
    expect(request.__calls__[1].params).toMatchObject({ keyword: '', level: null, status: null })
  })

  it('openSave() 无参 → 新增默认值', async () => {
    const wrapper = mountPage(() => listData())
    await wrapper.findAll('.el-button').find((b) => b.text() === '新增词条').trigger('click')
    expect(wrapper.vm.saveVisible).toBe(true)
    expect(wrapper.vm.saveForm).toMatchObject({ id: null, word: '', level: 3, status: 1 })
    // 无 id → 标题为新增
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.dialog-title').text()).toBe('新增词条')
  })

  it('openSave(row) → 编辑回填', async () => {
    const wrapper = mountPage(() => listData([word({ id: 7, word: '旧词', level: 2, status: 0 })]))
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.findAll('.col-slot[data-col="slotOperation"] .slot-cell a').find((a) => a.text() === '编辑').trigger('click')
    expect(wrapper.vm.saveForm).toMatchObject({ id: 7, word: '旧词', level: 2, status: 0 })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.dialog-title').text()).toBe('编辑词条')
  })

  it('submitSave 空词条 → warning 且不发请求', async () => {
    const wrapper = mountPage(() => listData())
    await wrapper.findAll('.el-button').find((b) => b.text() === '新增词条').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    wrapper.vm.saveForm.word = '   '
    await wrapper.find('.el-dialog').findAll('.el-button').find((b) => b.text() === '保存').trigger('click')
    expect(messageCalls).toEqual([['warning', '请输入词条内容']])
    expect(request.__calls__.filter((c) => c.url === '/admin/saveSensitiveWord')).toHaveLength(0)
    // 不关闭弹窗
    expect(wrapper.vm.saveVisible).toBe(true)
  })

  it('submitSave 新增 → 保存请求 + 新增文案 + 关闭 + 重载', async () => {
    const wrapper = mountPage(() => listData())
    await wrapper.findAll('.el-button').find((b) => b.text() === '新增词条').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    wrapper.vm.saveForm.word = '  新词  '
    await wrapper.find('.el-dialog').findAll('.el-button').find((b) => b.text() === '保存').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const save = request.__calls__.find((c) => c.url === '/admin/saveSensitiveWord')
    expect(save.params).toMatchObject({ id: null, word: '新词', level: 3, status: 1 })
    expect(messageCalls).toEqual([['success', '词条已新增，过滤已即时生效']])
    expect(wrapper.vm.saveVisible).toBe(false)
    // 重载（init 1 次 + 保存后 1 次）
    expect(request.__calls__.filter((c) => c.url === '/admin/loadSensitiveWord')).toHaveLength(2)
  })

  it('submitSave 编辑 → 更新文案', async () => {
    const wrapper = mountPage(() => listData([word({ id: 7 })]))
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.findAll('.col-slot[data-col="slotOperation"] .slot-cell a').find((a) => a.text() === '编辑').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    wrapper.vm.saveForm.word = '改名'
    await wrapper.find('.el-dialog').findAll('.el-button').find((b) => b.text() === '保存').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(messageCalls).toEqual([['success', '词条已更新，过滤已即时生效']])
    expect(request.__calls__.find((c) => c.url === '/admin/saveSensitiveWord').params.id).toBe(7)
  })

  it('removeWord → Confirm 含词条 + 删除后重载', async () => {
    const wrapper = mountPage(() => listData([word({ id: 3, word: '坏词' })]))
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.findAll('.col-slot[data-col="slotOperation"] .slot-cell a').find((a) => a.text() === '删除').trigger('click')
    expect(confirmCalls[0].message).toContain('坏词')
    await confirmCalls[0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.find((c) => c.url === '/admin/deleteSensitiveWord').params).toEqual({ id: 3 })
    expect(messageCalls).toEqual([['success', '已删除，过滤已即时生效']])
    expect(request.__calls__.filter((c) => c.url === '/admin/loadSensitiveWord')).toHaveLength(2)
  })

  it('onFileChange：csv 文件 → isCsv=true（决定是否附带级别/状态）', async () => {
    const wrapper = mountPage(() => listData())
    await wrapper.findAll('.el-button').find((b) => b.text() === '批量导入').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const input = wrapper.find('input[type="file"]')
    const csv = new File(['word,level,status\n坏词,3,1'], 'words.csv', { type: 'text/csv' })
    Object.defineProperty(input.element, 'files', { value: [csv], configurable: true })
    await input.trigger('change')
    expect(wrapper.vm.importForm.isCsv).toBe(true)
  })

  it('onFileChange：txt 文件 → isCsv=false', async () => {
    const wrapper = mountPage(() => listData())
    await wrapper.findAll('.el-button').find((b) => b.text() === '批量导入').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const input = wrapper.find('input[type="file"]')
    const txt = new File(['坏词\n坏词2'], 'words.txt', { type: 'text/plain' })
    Object.defineProperty(input.element, 'files', { value: [txt], configurable: true })
    await input.trigger('change')
    expect(wrapper.vm.importForm.isCsv).toBe(false)
  })

  it('submitImport 未选文件 → warning', async () => {
    const wrapper = mountPage(() => listData())
    await wrapper.findAll('.el-button').find((b) => b.text() === '批量导入').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.el-dialog').findAll('.el-button').find((b) => b.text() === '开始导入').trigger('click')
    expect(messageCalls).toEqual([['warning', '请选择要导入的文件']])
    expect(request.__calls__.filter((c) => c.url === '/admin/importSensitiveWord')).toHaveLength(0)
  })

  it('submitImport txt → FormData 带 file/level/status + 计数提示 + 复位', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/admin/importSensitiveWord') {
        return { code: 0, data: { success: 8, skipped: 1, failed: 0 } }
      }
      return listData()
    })
    await wrapper.findAll('.el-button').find((b) => b.text() === '批量导入').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const input = wrapper.find('input[type="file"]')
    const txt = new File(['坏词'], 'words.txt', { type: 'text/plain' })
    Object.defineProperty(input.element, 'files', { value: [txt], configurable: true })
    await input.trigger('change')
    wrapper.vm.importForm.level = 2
    wrapper.vm.importForm.status = 0
    await wrapper.find('.el-dialog').findAll('.el-button').find((b) => b.text() === '开始导入').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const imp = request.__calls__.find((c) => c.url === '/admin/importSensitiveWord')
    expect(imp.params).toBeInstanceOf(FormData)
    expect(imp.params.get('file')).toBe(txt)
    expect(imp.params.get('level')).toBe('2')
    expect(imp.params.get('status')).toBe('0')
    expect(messageCalls).toEqual([['success', '导入完成：新增 8，跳过 1，失败 0']])
    // 复位：弹窗关闭、文件清空、重新拉取
    expect(wrapper.vm.importVisible).toBe(false)
    expect(wrapper.vm.importFile).toBeNull()
    expect(request.__calls__.filter((c) => c.url === '/admin/loadSensitiveWord')).toHaveLength(2)
  })

  it('submitImport csv → FormData 不带 level/status', async () => {
    const wrapper = mountPage(() => ({ code: 0, data: { success: 1, skipped: 0, failed: 0 } }))
    await wrapper.findAll('.el-button').find((b) => b.text() === '批量导入').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const input = wrapper.find('input[type="file"]')
    const csv = new File(['word,level,status\n坏词,3,1'], 'words.csv', { type: 'text/csv' })
    Object.defineProperty(input.element, 'files', { value: [csv], configurable: true })
    await input.trigger('change')
    await wrapper.find('.el-dialog').findAll('.el-button').find((b) => b.text() === '开始导入').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const imp = request.__calls__.find((c) => c.url === '/admin/importSensitiveWord')
    expect(imp.params.get('file')).toBe(csv)
    expect(imp.params.get('level')).toBeNull()
    expect(imp.params.get('status')).toBeNull()
  })

  it('exportWords → blob 请求 + 触发下载', async () => {
    const wrapper = mountPage(() => ({}))
    const clickSpy = vi.fn()
    const origCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const el = origCreate(tag)
      if (tag === 'a') el.click = clickSpy
      return el
    })
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake')
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    try {
      await wrapper.findAll('.el-button').find((b) => b.text() === '导出 CSV').trigger('click')
      await new Promise((r) => setTimeout(r, 0))
      const exp = request.__calls__.find((c) => c.url === '/admin/exportSensitiveWord')
      expect(exp).toBeTruthy()
      expect(exp.method).toBe('GET')
      expect(exp.responseType).toBe('blob')
      expect(createUrl).toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalled()
      expect(revokeUrl).toHaveBeenCalledWith('blob:fake')
    } finally {
      createUrl.mockRestore()
      revokeUrl.mockRestore()
      document.createElement.mockRestore?.()
    }
  })
})
