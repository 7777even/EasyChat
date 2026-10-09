import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import BeautyAccount from '@/views/admin/BeautyAccount.vue'
import { ipcCalls } from './setup'

/**
 * 靓号管理列表页的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 查询表单（靓号/邮箱模糊输入 + 查询/新增按钮）渲染
 *   2. loadDataList：分页字段 + 模糊搜索字段一起提交；结果合并进 tableData
 *   3. 状态列插槽：status=0 红字「未使用」/ 非 0 绿字「已使用」
 *   4. 操作列插槽：status=0 才有「修改」、始终有「删除」
 *   5. editAccount(row) → BeautyAccountEdit.showEdit(row)
 *   6. delAccount → Confirm 文案含邮箱；确认后删除 + 成功提示 + 重载
 */

// Table 的 scopedSlots 由本页模板定义，stub 需逐行把 slot props 传下去，
// 否则 `<template #slotStatus="{ row }">` 拿不到 row（destructure undefined 报错）
const TableStub = {
  name: 'Table',
  props: ['columns', 'fetch', 'dataSource', 'options'],
  emits: ['rowClick', 'rowSelected'],
  data() {
    return { fetchCalled: 0 }
  },
  mounted() {
    // Table 的 initFetch 行为：挂载即拉一次
    if (this.fetch) {
      this.fetchCalled++
      this.fetch()
    }
  },
  template: `<div class="table-stub">
    <template v-for="(col, ci) in columns" :key="ci">
      <div v-if="col.scopedSlots" class="col-slot" :data-col="col.scopedSlots">
        <div
          v-for="(row, ri) in (dataSource.list || [])"
          :key="ri"
          class="slot-cell"
          :data-ri="ri"
        ><slot :name="col.scopedSlots" :index="ri" :row="row" /></div>
      </div>
    </template>
  </div>`
}

const stubs = {
  Table: TableStub,
  'el-card': { name: 'el-card', template: '<div class="el-card"><slot /></div>' },
  'el-form': { name: 'el-form', props: ['model', 'labelWidth', 'labelPosition'], template: '<form class="el-form"><slot /></form>' },
  'el-form-item': { name: 'el-form-item', props: ['label', 'labelWidth', 'style'], template: '<div class="el-form-item"><slot /></div>' },
  'el-row': { name: 'el-row', template: '<div class="el-row"><slot /></div>' },
  'el-col': { name: 'el-col', props: ['span', 'style'], template: '<div class="el-col"><slot /></div>' },
  'el-input': {
    name: 'el-input',
    props: ['modelValue', 'clearable', 'placeholder'],
    emits: ['update:modelValue'],
    template: `<input class="el-input" :value="modelValue" :placeholder="placeholder"
      @input="$emit('update:modelValue', $event.target.value)" />`
  },
  'el-button': {
    name: 'el-button',
    props: ['type'],
    emits: ['click'],
    template: `<button class="el-button" :data-type="type" @click="$emit('click')"><slot /></button>`
  },
  'el-dropdown': { name: 'el-dropdown', props: ['placement', 'trigger'], template: '<div class="el-dropdown"><slot name="dropdown" /></div>' },
  'el-dropdown-item': { name: 'el-dropdown-item', emits: ['click'], template: '<div class="el-dropdown-item" @click="$emit(\'click\')"><slot /></div>' },
  Avatar: { name: 'Avatar', props: ['width', 'userId', 'partType'], template: '<div class="avatar" />' },
  BeautyAccountEdit: {
    name: 'BeautyAccountEdit',
    emits: ['reload'],
    data() { return { showEditArgs: [] } },
    methods: {
      showEdit(...args) { this.showEditArgs.push(args) }
    },
    template: '<div class="beauty-edit" />'
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

function mountPage(handler) {
  request = makeRequest(handler)
  const wrapper = mount(BeautyAccount, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { loadBeautyAccount: '/admin/loadBeautyAccountList', delBeautAccount: '/admin/delBeautAccount' },
          Confirm: (opts) => { confirmCalls.push(opts) },
          Message: { success: (m) => messageCalls.push(m) }
        }
      },
      plugins: [createPinia()]
    }
  })
  return wrapper
}

// 表格数据
function tableDataResponse() {
  return {
    code: 0,
    data: {
      pageNo: 1,
      pageSize: 15,
      totalCount: 2,
      list: [
        { id: 1, userId: '10001', email: 'a@b.com', nickName: '甲', sex: 0, status: 0, onlineType: 0 },
        { id: 2, userId: '10002', email: 'c@d.com', nickName: '乙', sex: 1, status: 1, onlineType: 1 }
      ]
    }
  }
}

describe('BeautyAccount.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    confirmCalls = []
    messageCalls = []
    ipcCalls.length = 0
  })

  it('查询表单 + Table 渲染', () => {
    const wrapper = mountPage(() => tableDataResponse())
    expect(wrapper.findAll('.el-input')).toHaveLength(2)
    const btns = wrapper.findAll('.el-button').map((b) => b.text())
    expect(btns).toContain('查询')
    expect(btns).toContain('新增靓号')
    expect(wrapper.findComponent({ name: 'Table' }).exists()).toBe(true)
  })

  it('Table 收到 columns（含 scopedSlots 列）/fetch/dataSource', () => {
    const wrapper = mountPage(() => tableDataResponse())
    const table = wrapper.findComponent({ name: 'Table' })
    const columns = table.props('columns')
    expect(columns.map((c) => c.label)).toEqual(['邮箱', '靓号', '状态', '操作'])
    expect(columns.find((c) => c.label === '状态').scopedSlots).toBe('slotStatus')
    expect(columns.find((c) => c.label === '操作').scopedSlots).toBe('slotOperation')
    expect(typeof table.props('fetch')).toBe('function')
    // Table initFetch：挂载即拉一次
    expect(request.__calls__.some((c) => c.url === '/admin/loadBeautyAccountList')).toBe(true)
  })

  it('loadDataList → 请求参数含分页与模糊搜索字段', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/admin/loadBeautyAccountList') return tableDataResponse()
      return { code: 0, data: null }
    })
    // 第一次由 initFetch 触发（此时 searchForm 为空）
    let calls = request.__calls__.filter((c) => c.url === '/admin/loadBeautyAccountList')
    expect(calls[0].params).toMatchObject({ pageNo: undefined, pageSize: undefined })
    // 填搜索条件后点查询
    await wrapper.findAll('.el-input')[0].setValue('100')
    await wrapper.findAll('.el-button').find((b) => b.text() === '查询').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    calls = request.__calls__.filter((c) => c.url === '/admin/loadBeautyAccountList')
    expect(calls[1].params.userIdFuzzy).toBe('100')
  })

  it('结果合并进 tableData（list/totalCount）', async () => {
    const wrapper = mountPage(() => tableDataResponse())
    await new Promise((r) => setTimeout(r, 0))
    // 通过 Table 收到的 dataSource 观察合并结果
    const ds = wrapper.findComponent({ name: 'Table' }).props('dataSource')
    expect(ds.list).toHaveLength(2)
    expect(ds.totalCount).toBe(2)
  })

  it('状态列插槽：未使用红字 / 已使用绿字', async () => {
    const wrapper = mountPage(() => tableDataResponse())
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotStatus"]').findAll('.slot-cell')
    expect(cells[0].text()).toBe('未使用')
    expect(cells[0].find('span').attributes('style')).toContain('red')
    expect(cells[1].text()).toBe('已使用')
    expect(cells[1].find('span').attributes('style')).toContain('green')
  })

  it('操作列插槽：status=0 有「修改」、始终有「删除」', async () => {
    const wrapper = mountPage(() => tableDataResponse())
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotOperation"]').findAll('.slot-cell')
    const first = cells[0].findAll('.el-dropdown-item').map((i) => i.text())
    expect(first).toEqual(['修改', '删除'])
    // 已使用的行只给删除
    const second = cells[1].findAll('.el-dropdown-item').map((i) => i.text())
    expect(second).toEqual(['删除'])
  })

  it('editAccount(row) → BeautyAccountEdit.showEdit(row)', async () => {
    const wrapper = mountPage(() => tableDataResponse())
    await new Promise((r) => setTimeout(r, 0))
    const edit = wrapper.findComponent({ name: 'BeautyAccountEdit' })
    // 新增（无参）
    await wrapper.findAll('.el-button').find((b) => b.text() === '新增靓号').trigger('click')
    expect(edit.vm.showEditArgs[0]).toEqual([undefined])
    // 行内修改（带 row）
    const cells = wrapper.find('.col-slot[data-col="slotOperation"]').findAll('.slot-cell')
    await cells[0].findAll('.el-dropdown-item')[0].trigger('click')
    expect(edit.vm.showEditArgs[1][0].id).toBe(1)
  })

  it('delAccount → Confirm 含邮箱 + 确认后删除并重载', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/admin/loadBeautyAccountList') return tableDataResponse()
      if (opts.url === '/admin/delBeautAccount') return { code: 0, data: null }
      return { code: 0, data: null }
    })
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotOperation"]').findAll('.slot-cell')
    const delBtn = cells[0].findAll('.el-dropdown-item').find((i) => i.text() === '删除')
    await delBtn.trigger('click')
    // Confirm 文案含邮箱
    expect(confirmCalls).toHaveLength(1)
    expect(confirmCalls[0].message).toContain('a@b.com')
    // 走确认回调
    const before = request.__calls__.filter((c) => c.url === '/admin/loadBeautyAccountList').length
    await confirmCalls[0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    const del = request.__calls__.find((c) => c.url === '/admin/delBeautAccount')
    expect(del).toBeTruthy()
    expect(del.params.id).toBe(1)
    expect(messageCalls).toContain('删除成功')
    // 重新拉取
    const after = request.__calls__.filter((c) => c.url === '/admin/loadBeautyAccountList').length
    expect(after).toBeGreaterThan(before)
  })
})
