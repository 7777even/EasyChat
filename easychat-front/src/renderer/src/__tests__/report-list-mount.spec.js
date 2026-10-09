import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ReportList from '@/views/admin/ReportList.vue'

/**
 * 管理端举报管理的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 筛选区选项（类型 3 / 状态 3）与日期范围 → 起止毫秒
 *   2. 列表插槽：类型/理由/状态三色/操作（仅待处理可处置）
 *   3. showDetail：详情 + 审计轨迹两个请求及渲染兜底
 *   4. openDeal / submitDeal：表单重置、告警条件、处置提交
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
  'el-form-item': { name: 'el-form-item', props: ['label'], template: '<div><slot /></div>' },
  'el-row': { name: 'el-row', template: '<div><slot /></div>' },
  'el-col': { name: 'el-col', props: ['span', 'style'], template: '<div><slot /></div>' },
  'el-select': {
    name: 'el-select',
    props: ['modelValue', 'clearable', 'placeholder', 'style'],
    emits: ['update:modelValue'],
    template: '<div class="el-select"><slot /></div>'
  },
  'el-option': {
    name: 'el-option',
    props: ['label', 'value'],
    template: '<div class="el-option" :data-label="label" :data-value="value"></div>'
  },
  'el-date-picker': {
    name: 'el-date-picker',
    props: ['modelValue', 'type', 'rangeSeparator', 'startPlaceholder', 'endPlaceholder', 'valueFormat', 'style'],
    emits: ['update:modelValue'],
    template: '<div class="el-date-picker"></div>'
  },
  'el-button': {
    name: 'el-button',
    props: ['type'],
    emits: ['click'],
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`
  },
  'el-input': {
    name: 'el-input',
    props: ['modelValue', 'type', 'rows', 'maxlength', 'showWordLimit', 'placeholder'],
    emits: ['update:modelValue'],
    template: `<input class="el-input" :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value)" />`
  },
  'el-dialog': {
    name: 'el-dialog',
    props: ['title', 'modelValue', 'width'],
    emits: ['update:modelValue'],
    template: `<div class="el-dialog" v-if="modelValue"><div class="dialog-title">{{ title }}</div><slot /><slot name="footer" /></div>`
  },
  'el-descriptions': { name: 'el-descriptions', props: ['column', 'border'], template: '<div class="el-descriptions"><slot /></div>' },
  'el-descriptions-item': { name: 'el-descriptions-item', props: ['label'], template: '<div class="el-descriptions-item"><div class="di-label">{{ label }}</div><div class="di-value"><slot /></div></div>' },
  'el-timeline': { name: 'el-timeline', template: '<div class="el-timeline"><slot /></div>' },
  'el-timeline-item': { name: 'el-timeline-item', props: ['timestamp', 'placement'], template: '<div class="el-timeline-item"><slot /></div>' },
  'el-tag': { name: 'el-tag', props: ['size', 'type'], template: '<span class="el-tag" :data-type="type"><slot /></span>' },
  'el-empty': { name: 'el-empty', props: ['description', 'imageSize'], template: '<div class="el-empty">{{ description }}</div>' },
  'el-radio-group': { name: 'el-radio-group', props: ['modelValue'], emits: ['update:modelValue'], template: '<div class="el-radio-group"><slot /></div>' },
  'el-radio': { name: 'el-radio', props: ['label'], template: '<div class="el-radio"><slot /></div>' },
  'el-alert': { name: 'el-alert', props: ['type', 'closable', 'title'], template: '<div class="el-alert" :data-type="type">{{ title }}</div>' }
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
  const wrapper = mount(ReportList, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: {
            loadReport: '/admin/loadReport',
            getReportDetail: '/admin/getReportDetail',
            loadReportAudit: '/admin/loadReportAudit',
            dealReport: '/admin/dealReport'
          },
          Confirm: (opts) => { confirmCalls.push(opts) },
          Message: { success: (m) => messageCalls.push(m) }
        }
      }
    }
  })
  return wrapper
}

function listData(list = []) {
  return { code: 0, data: { pageNo: 1, pageSize: 15, totalCount: list.length, list } }
}

function report(over = {}) {
  return {
    id: 1,
    reportType: 1,
    contentExcerpt: '摘要',
    reportUserName: '举报人',
    reason: 0,
    status: 0,
    createTime: new Date(2026, 9, 1, 9, 5).getTime(),
    ...over
  }
}

// 默认：列表 + 详情 + 审计都有数据
function defaultHandler(opts) {
  if (opts.url === '/admin/loadReport') return listData([report()])
  if (opts.url === '/admin/getReportDetail') {
    return {
      code: 0,
      data: {
        id: 1,
        reportType: 3,
        status: 0,
        reportUserName: '举报人甲',
        reportUserId: 'U_r',
        publisherNickName: null,
        publisherId: 'U_p',
        reason: 2,
        description: '',
        content: '被举报内容原文'
      }
    }
  }
  if (opts.url === '/admin/loadReportAudit') {
    return {
      code: 0,
      data: {
        list: [
          { createTime: new Date(2026, 9, 2, 10, 0).getTime(), action: 1, handleAction: 1, adminId: 'U_admin', handleNote: '已删' },
          { createTime: new Date(2026, 9, 3, 10, 0).getTime(), action: 2, handleAction: 2, adminId: 'U_admin2' }
        ]
      }
    }
  }
  if (opts.url === '/admin/dealReport') return { code: 0, data: null }
  return { code: 0, data: null }
}

describe('ReportList.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    confirmCalls = []
    messageCalls = []
  })

  it('筛选区：类型 3 项 / 状态 3 项', () => {
    const wrapper = mountPage(() => listData())
    const selects = wrapper.findAll('.el-select')
    expect(selects[0].findAll('.el-option').map((o) => o.attributes('data-label'))).toEqual([
      '朋友圈动态',
      '评论',
      '聊天消息'
    ])
    expect(selects[1].findAll('.el-option').map((o) => o.attributes('data-label'))).toEqual([
      '待处理',
      '已处理',
      '已驳回'
    ])
  })

  it('Table 7 列与 5 个 scopedSlots', () => {
    const wrapper = mountPage(() => listData())
    const columns = wrapper.findComponent({ name: 'Table' }).props('columns')
    expect(columns).toHaveLength(7)
    expect(columns.filter((c) => c.scopedSlots).map((c) => c.scopedSlots)).toEqual([
      'slotReportType',
      'slotReason',
      'slotStatus',
      'slotCreateTime',
      'slotOperation'
    ])
  })

  it('类型/理由插槽文本与越界兜底', async () => {
    const wrapper = mountPage(() => listData([
      report({ id: 1, reportType: 1, reason: 0 }),
      report({ id: 2, reportType: 2, reason: 2 }),
      report({ id: 3, reportType: 3, reason: 4 }),
      report({ id: 4, reportType: 9, reason: 9 })
    ]))
    await new Promise((r) => setTimeout(r, 0))
    const types = wrapper.find('.col-slot[data-col="slotReportType"]').findAll('.slot-cell')
    expect(types.map((c) => c.text())).toEqual(['朋友圈动态', '评论', '聊天消息', '-'])
    const reasons = wrapper.find('.col-slot[data-col="slotReason"]').findAll('.slot-cell')
    expect(reasons.map((c) => c.text())).toEqual(['色情', '诈骗', '其他', '-'])
  })

  it('状态插槽三态颜色', async () => {
    const wrapper = mountPage(() => listData([
      report({ id: 1, status: 0 }),
      report({ id: 2, status: 1 }),
      report({ id: 3, status: 2 })
    ]))
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotStatus"]').findAll('.slot-cell')
    expect(cells.map((c) => c.find('span').text())).toEqual(['待处理', '已处理', '已驳回'])
    // jsdom 会把 hex 内联色转 rgb()
    expect(cells[0].find('span').attributes('style')).toContain('rgb(230, 162, 60)')
    expect(cells[1].find('span').attributes('style')).toContain('rgb(103, 194, 58)')
    expect(cells[2].find('span').attributes('style')).toContain('rgb(144, 147, 153)')
  })

  it('操作插槽：仅待处理可处置，其余显示已处置', async () => {
    const wrapper = mountPage(() => listData([
      report({ id: 1, status: 0 }),
      report({ id: 2, status: 1 })
    ]))
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotOperation"]').findAll('.slot-cell')
    expect(cells[0].findAll('a').map((a) => a.text())).toEqual(['查看', '处理'])
    expect(cells[1].findAll('a').map((a) => a.text())).toEqual(['查看'])
    expect(cells[1].text()).toContain('已处置')
  })

  it('举报时间插槽格式化与兜底', async () => {
    const wrapper = mountPage(() => listData([
      report({ id: 1, createTime: new Date(2026, 9, 1, 9, 5).getTime() }),
      report({ id: 2, createTime: null })
    ]))
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotCreateTime"]').findAll('.slot-cell')
    expect(cells.map((c) => c.text())).toEqual(['2026-10-01 09:05', '-'])
  })

  it('日期范围 → 当天起止毫秒', async () => {
    const wrapper = mountPage(() => listData())
    wrapper.vm.dateRange = ['2026-10-01', '2026-10-03']
    await wrapper.findAll('.el-button').find((b) => b.text() === '查询').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const params = request.__calls__[1].params
    expect(params.startTime).toBe(new Date(2026, 9, 1, 0, 0, 0).getTime())
    expect(params.endTime).toBe(new Date(2026, 9, 3, 23, 59, 59).getTime())
  })

  it('重置 → 清空筛选 + 重新拉取', async () => {
    const wrapper = mountPage(() => listData())
    wrapper.vm.searchForm.reportType = 2
    wrapper.vm.searchForm.status = 1
    wrapper.vm.dateRange = ['2026-10-01', '2026-10-02']
    await wrapper.findAll('.el-button').find((b) => b.text() === '重置').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.searchForm).toMatchObject({ reportType: null, status: null, startTime: null, endTime: null })
    expect(wrapper.vm.dateRange).toEqual([])
    expect(request.__calls__[1].params).toMatchObject({ reportType: null, status: null })
  })

  it('showDetail → 详情 + 审计两请求，弹窗渲染兜底文案', async () => {
    const wrapper = mountPage(defaultHandler)
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.col-slot[data-col="slotOperation"] .slot-cell a').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const detail = request.__calls__.find((c) => c.url === '/admin/getReportDetail')
    const audit = request.__calls__.find((c) => c.url === '/admin/loadReportAudit')
    expect(detail.params).toEqual({ id: 1, reportType: 1 })
    expect(audit.params).toMatchObject({ reportId: 1, reportType: 1, pageNo: 1, pageSize: 50 })
    // 弹窗内容
    const dialog = wrapper.find('.el-dialog')
    expect(dialog.find('.dialog-title').text()).toBe('举报详情')
    // 发布者兜底：publisherNickName 为 null → 用 publisherId
    const items = dialog.findAll('.el-descriptions-item')
    expect(items[0].find('.di-value').text()).toBe('聊天消息')
    expect(items[2].find('.di-value').text()).toBe('举报人甲')
    expect(items[3].find('.di-value').text()).toBe('U_p')
    expect(items[4].find('.di-value').text()).toBe('诈骗')
    // 举报说明空 → 「无」
    expect(items[5].find('.di-value').text()).toBe('无')
    // 被举报内容有值 → 显示原文（有内容时不走兜底）
    expect(items[6].find('.di-value').text()).toBe('被举报内容原文')
  })

  it('审计时间线渲染与空态兜底', async () => {
    const wrapper = mountPage(defaultHandler)
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.col-slot[data-col="slotOperation"] .slot-cell a').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const items = wrapper.findAll('.el-timeline-item')
    expect(items).toHaveLength(2)
    // 动作文本 + 驳回标签
    expect(items[0].find('.el-tag').text()).toBe('已处理')
    expect(items[1].find('.el-tag').text()).toBe('已驳回')
    expect(items[0].text()).toContain('动作：删除被举报内容')
    expect(items[0].text()).toContain('备注：已删')
    // 无备注的行不渲染备注
    expect(items[1].text()).not.toContain('备注：')
  })

  it('审计为空 → el-empty 兜底', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/admin/loadReport') return listData([report()])
      if (opts.url === '/admin/getReportDetail') return { code: 0, data: { id: 1, reportType: 1, reason: 1 } }
      if (opts.url === '/admin/loadReportAudit') return { code: 0, data: { list: [] } }
      return { code: 0, data: null }
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.col-slot[data-col="slotOperation"] .slot-cell a').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.el-empty').text()).toBe('暂无处置记录')
  })

  it('openDeal → 弹窗打开且表单重置为默认值', async () => {
    const wrapper = mountPage(defaultHandler)
    await new Promise((r) => setTimeout(r, 0))
    // 先改脏表单
    wrapper.vm.dealForm.status = 2
    wrapper.vm.dealForm.handleNote = '旧备注'
    const dealLink = wrapper.findAll('.col-slot[data-col="slotOperation"] .slot-cell a').find((a) => a.text() === '处理')
    await dealLink.trigger('click')
    expect(wrapper.vm.dealVisible).toBe(true)
    expect(wrapper.vm.dealForm).toMatchObject({ id: 1, reportType: 1, status: 1, handleAction: 0, handleNote: '' })
  })

  it('处置弹窗：消息型 + 删除内容时显示告警', async () => {
    const wrapper = mountPage(defaultHandler)
    await new Promise((r) => setTimeout(r, 0))
    const dealLink = wrapper.findAll('.col-slot[data-col="slotOperation"] .slot-cell a').find((a) => a.text() === '处理')
    await dealLink.trigger('click')
    // reportType=1（动态）+ 默认 handleAction=0 → 不告警
    expect(wrapper.find('.el-alert').exists()).toBe(false)
    // 切到删除内容 → 仍非消息型，不告警
    wrapper.vm.dealForm.handleAction = 1
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.el-alert').exists()).toBe(false)
    // 切到消息型 → 告警出现
    wrapper.vm.dealForm.reportType = 3
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.el-alert').text()).toContain('将逻辑删除该消息')
  })

  it('submitDeal → Confirm → 处置请求带全部字段 + 成功提示 + 重载', async () => {
    const wrapper = mountPage(defaultHandler)
    await new Promise((r) => setTimeout(r, 0))
    const dealLink = wrapper.findAll('.col-slot[data-col="slotOperation"] .slot-cell a').find((a) => a.text() === '处理')
    await dealLink.trigger('click')
    wrapper.vm.dealForm.status = 2
    wrapper.vm.dealForm.handleAction = 2
    wrapper.vm.dealForm.handleNote = '封禁处理'
    await wrapper.findAll('.el-button').find((b) => b.text() === '确认处置').trigger('click')
    expect(confirmCalls[0].message).toContain('确认要处置该举报')
    const before = request.__calls__.filter((c) => c.url === '/admin/loadReport').length
    await confirmCalls[0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    const deal = request.__calls__.find((c) => c.url === '/admin/dealReport')
    expect(deal.params).toEqual({
      id: 1,
      reportType: 1,
      status: 2,
      handleAction: 2,
      handleNote: '封禁处理'
    })
    expect(messageCalls).toContain('处置成功')
    expect(wrapper.vm.dealVisible).toBe(false)
    expect(request.__calls__.filter((c) => c.url === '/admin/loadReport').length).toBeGreaterThan(before)
  })
})
