import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Update from '@/views/admin/Update.vue'

/**
 * 管理端版本管理列表的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 日期范围 → createTimeStart/End，且不把 createTimeRange 数组
 *      原样透传给后端（delete 守卫）
 *   2. 列插槽：更新内容序号、文件类型两态、状态三色、操作入口条件
 *   3. showEdit / updatePost 分别转发到两个子弹窗
 *   4. del → Confirm 文案 + 删除后重载
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
  'el-form-item': { name: 'el-form-item', props: ['label', 'labelWidth', 'style'], template: '<div><slot /></div>' },
  'el-row': { name: 'el-row', template: '<div><slot /></div>' },
  'el-col': { name: 'el-col', props: ['span', 'style'], template: '<div><slot /></div>' },
  'el-date-picker': {
    name: 'el-date-picker',
    props: ['modelValue', 'type', 'rangeSeparator', 'startPlaceholder', 'endPlaceholder', 'valueFormat'],
    emits: ['update:modelValue', 'change'],
    template: '<div class="el-date-picker"></div>'
  },
  'el-button': {
    name: 'el-button',
    props: ['type'],
    emits: ['click'],
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`
  },
  'el-dropdown': { name: 'el-dropdown', props: ['placement', 'trigger'], template: '<div class="el-dropdown"><slot name="dropdown" /></div>' },
  'el-dropdown-item': {
    name: 'el-dropdown-item',
    emits: ['click'],
    template: `<div class="el-dropdown-item" @click="$emit('click')"><slot /></div>`
  },
  UpdateEdit: {
    name: 'UpdateEdit',
    emits: ['reload'],
    data() { return { showEditArgs: [] } },
    methods: { showEdit(...args) { this.showEditArgs.push(args) } },
    template: '<div class="update-edit" />'
  },
  UpdatePost: {
    name: 'UpdatePost',
    emits: ['reload'],
    data() { return { showEditArgs: [] } },
    methods: { showEdit(...args) { this.showEditArgs.push(args) } },
    template: '<div class="update-post" />'
  }
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

function mountPage(list = []) {
  request = makeRequest(() => ({
    code: 0,
    data: { pageNo: 1, pageSize: 15, totalCount: list.length, list }
  }))
  const wrapper = mount(Update, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: {
            loadUpdateDataList: '/admin/loadUpdateDataList',
            delUpdate: '/admin/delUpdate'
          },
          Confirm: (opts) => { confirmCalls.push(opts) },
          Message: { success: (m) => messageCalls.push(m) }
        }
      }
    }
  })
  return wrapper
}

function update(over = {}) {
  return {
    id: 1,
    version: '1.2.0',
    updateDesc: '修复问题',
    updateDescArray: ['修复问题', '优化体验'],
    createTime: new Date(2026, 9, 1, 9, 5).getTime(),
    fileType: 0,
    outerLink: '',
    status: 0,
    ...over
  }
}

describe('Update.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    confirmCalls = []
    messageCalls = []
  })

  it('筛选区：日期选择器 + 查询/发布版本按钮', () => {
    const wrapper = mountPage()
    expect(wrapper.find('.el-date-picker').exists()).toBe(true)
    expect(wrapper.findAll('.el-button').map((b) => b.text())).toEqual(['查询', '发布版本'])
  })

  it('Table 6 列与 4 个 scopedSlots', () => {
    const wrapper = mountPage()
    const columns = wrapper.findComponent({ name: 'Table' }).props('columns')
    expect(columns).toHaveLength(6)
    expect(columns.filter((c) => c.scopedSlots).map((c) => c.scopedSlots)).toEqual([
      'slotUpdateDesc',
      'fileTypeSlot',
      'slotStatus',
      'slotOperation'
    ])
  })

  it('无日期筛选 → 参数不含起止字段', () => {
    mountPage()
    const params = request.__calls__[0].params
    expect('createTimeStart' in params).toBe(false)
    expect('createTimeEnd' in params).toBe(false)
    expect('createTimeRange' in params).toBe(false)
  })

  it('有日期筛选 → 起止字段 + 不透传 createTimeRange', async () => {
    const wrapper = mountPage()
    wrapper.vm.searchFormData.createTimeRange = ['2026-10-01', '2026-10-03']
    await wrapper.findAll('.el-button').find((b) => b.text() === '查询').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const params = request.__calls__[1].params
    expect(params.createTimeStart).toBe('2026-10-01')
    expect(params.createTimeEnd).toBe('2026-10-03')
    expect('createTimeRange' in params).toBe(false)
  })

  it('更新内容插槽：序号列表', async () => {
    const wrapper = mountPage([update()])
    await new Promise((r) => setTimeout(r, 0))
    const items = wrapper.find('.col-slot[data-col="slotUpdateDesc"]').findAll('.slot-cell div')
    expect(items.map((i) => i.text())).toEqual(['1、修复问题', '2、优化体验'])
  })

  it('文件类型插槽两态', async () => {
    const wrapper = mountPage([update({ fileType: 0 }), update({ id: 2, fileType: 1, outerLink: 'https://dl.example.com' })])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="fileTypeSlot"]').findAll('.slot-cell')
    expect(cells[0].text()).toBe('本地文件')
    expect(cells[1].text()).toBe('https://dl.example.com')
  })

  it('状态插槽三态三色', async () => {
    const wrapper = mountPage([
      update({ id: 1, status: 0 }),
      update({ id: 2, status: 1 }),
      update({ id: 3, status: 2 })
    ])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotStatus"]').findAll('.slot-cell')
    expect(cells.map((c) => c.find('div').text())).toEqual(['未发布', '灰度发布', '全网发布'])
    expect(cells[0].find('div').attributes('style')).toContain('rgb(245, 108, 108)')
    expect(cells[1].find('div').attributes('style')).toContain('rgb(247, 186, 42)')
    expect(cells[2].find('div').attributes('style')).toContain('rgb(82, 155, 46)')
  })

  it('操作插槽：仅未发布可修改/删除', async () => {
    const wrapper = mountPage([update({ status: 0 }), update({ id: 2, status: 2 })])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotOperation"]').findAll('.slot-cell')
    expect(cells[0].findAll('.el-dropdown-item').map((i) => i.text())).toEqual(['修改', '发布', '删除'])
    expect(cells[1].findAll('.el-dropdown-item').map((i) => i.text())).toEqual(['发布'])
  })

  it('showEdit / updatePost 分别转发到两个子弹窗', async () => {
    const wrapper = mountPage([update()])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotOperation"]').findAll('.slot-cell')
    await cells[0].findAll('.el-dropdown-item').find((i) => i.text() === '修改').trigger('click')
    await cells[0].findAll('.el-dropdown-item').find((i) => i.text() === '发布').trigger('click')
    const updateEdit = wrapper.findComponent({ name: 'UpdateEdit' })
    const updatePost = wrapper.findComponent({ name: 'UpdatePost' })
    expect(updateEdit.vm.showEditArgs[0][0].id).toBe(1)
    expect(updatePost.vm.showEditArgs[0][0].id).toBe(1)
  })

  it('发布版本按钮 → UpdateEdit.showEdit() 无参（新增）', async () => {
    const wrapper = mountPage()
    await wrapper.findAll('.el-button').find((b) => b.text() === '发布版本').trigger('click')
    expect(wrapper.findComponent({ name: 'UpdateEdit' }).vm.showEditArgs[0]).toEqual([undefined])
  })

  it('del → Confirm 含版本号 + 删除后重载', async () => {
    const wrapper = mountPage([update({ id: 9, version: '2.0.0' })])
    await new Promise((r) => setTimeout(r, 0))
    const delItem = wrapper.findAll('.col-slot[data-col="slotOperation"] .el-dropdown-item').find((i) => i.text() === '删除')
    await delItem.trigger('click')
    expect(confirmCalls[0].message).toContain('2.0.0')
    const before = request.__calls__.length
    await confirmCalls[0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.find((c) => c.url === '/admin/delUpdate').params).toEqual({ id: 9 })
    expect(messageCalls).toContain('删除成功')
    expect(request.__calls__.length).toBeGreaterThan(before)
  })
})
