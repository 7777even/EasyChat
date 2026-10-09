import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import CallLogList from '@/views/admin/CallLogList.vue'

/**
 * 管理端通话记录列表的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 三个筛选下拉的选项集合（类型 2 / 媒体 2 / 状态 5）
 *   2. loadDataList：日期范围 → 起止毫秒（当天 00:00:00 / 23:59:59）
 *   3. resetSearch：清空全部筛选项 + 日期 + 重新拉取
 *   4. 各列插槽的纯格式化：类型/媒体/对方或群名/状态五色/时长/时间
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
  'el-form-item': { name: 'el-form-item', props: ['label'], template: '<div class="el-form-item"><slot /></div>' },
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
  }
}

let request

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
  const wrapper = mount(CallLogList, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { loadCallLog: '/admin/loadCallLog' }
        }
      }
    }
  })
  return wrapper
}

// 一行覆盖各种分支的通话记录
function row(over = {}) {
  return {
    id: 1,
    callType: 1,
    mediaType: 1,
    callerNickName: '主叫',
    peerNickName: '对方',
    groupNickName: null,
    status: 1,
    durationMs: 3500,
    participantCount: 2,
    createTime: new Date(2026, 9, 1, 9, 5).getTime(),
    ...over
  }
}

function setList(wrapper, rows) {
  // 直接触发一次带 list 的响应：覆写 request 后手动调用拉取
  return wrapper
}

describe('CallLogList.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('三个筛选下拉的选项集合', () => {
    const wrapper = mountPage()
    const selects = wrapper.findAll('.el-select')
    expect(selects).toHaveLength(3)
    const labels = (i) => selects[i].findAll('.el-option').map((o) => o.attributes('data-label'))
    expect(labels(0)).toEqual(['单聊', '群呼'])
    expect(labels(1)).toEqual(['音频', '音视频'])
    expect(labels(2)).toEqual(['已接', '未接', '拒接', '取消', '忙线'])
  })

  it('Table 收到 9 列与关键 scopedSlots 名', () => {
    const wrapper = mountPage()
    const columns = wrapper.findComponent({ name: 'Table' }).props('columns')
    expect(columns).toHaveLength(9)
    const slots = columns.filter((c) => c.scopedSlots).map((c) => c.scopedSlots)
    expect(slots).toEqual([
      'slotCallType',
      'slotMediaType',
      'slotCaller',
      'slotPeerOrGroup',
      'slotStatus',
      'slotDuration',
      'slotCreateTime'
    ])
  })

  it('挂载即拉取（Table initFetch）且参数含全部筛选项', () => {
    const wrapper = mountPage()
    expect(request.__calls__).toHaveLength(1)
    expect(request.__calls__[0].url).toBe('/admin/loadCallLog')
    expect(request.__calls__[0].params).toMatchObject({
      callType: null,
      mediaType: null,
      status: null,
      startTime: null,
      endTime: null
    })
  })

  it('日期范围 → 当天起止毫秒（00:00:00 / 23:59:59）', async () => {
    const wrapper = mountPage()
    wrapper.vm.dateRange = ['2026-10-01', '2026-10-01']
    await wrapper.findAll('.el-button').find((b) => b.text() === '查询').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const params = request.__calls__[1].params
    expect(params.startTime).toBe(new Date(2026, 9, 1, 0, 0, 0).getTime())
    expect(params.endTime).toBe(new Date(2026, 9, 1, 23, 59, 59).getTime())
  })

  it('跨日范围 → 起止各自边界', async () => {
    const wrapper = mountPage()
    wrapper.vm.dateRange = ['2026-10-01', '2026-10-03']
    await wrapper.findAll('.el-button').find((b) => b.text() === '查询').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const params = request.__calls__[1].params
    expect(params.startTime).toBe(new Date(2026, 9, 1, 0, 0, 0).getTime())
    expect(params.endTime).toBe(new Date(2026, 9, 3, 23, 59, 59).getTime())
  })

  it('日期为空 → startTime/endTime 为 null', async () => {
    const wrapper = mountPage()
    wrapper.vm.dateRange = []
    await wrapper.findAll('.el-button').find((b) => b.text() === '查询').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__[1].params.startTime).toBeNull()
    expect(request.__calls__[1].params.endTime).toBeNull()
  })

  it('resetSearch → 清空全部筛选 + 日期 + 重新拉取', async () => {
    const wrapper = mountPage()
    wrapper.vm.searchForm.callType = 2
    wrapper.vm.searchForm.status = 3
    wrapper.vm.dateRange = ['2026-10-01', '2026-10-02']
    await wrapper.findAll('.el-button').find((b) => b.text() === '重置').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.vm.searchForm).toMatchObject({ callType: null, mediaType: null, status: null, startTime: null, endTime: null })
    expect(wrapper.vm.dateRange).toEqual([])
    // 重新拉了一次（参数全空）
    expect(request.__calls__).toHaveLength(2)
    expect(request.__calls__[1].params).toMatchObject({ callType: null, status: null, startTime: null, endTime: null })
  })

  it('slotCallType / slotMediaType 文本与兜底', async () => {
    const wrapper = mountPage([
      row({ callType: 1, mediaType: 1 }),
      row({ id: 2, callType: 2, mediaType: 2 }),
      row({ id: 3, callType: 9, mediaType: 9 })
    ])
    await new Promise((r) => setTimeout(r, 0))
    const callType = wrapper.find('.col-slot[data-col="slotCallType"]').findAll('.slot-cell')
    expect(callType.map((c) => c.text())).toEqual(['单聊', '群呼', '-'])
    const mediaType = wrapper.find('.col-slot[data-col="slotMediaType"]').findAll('.slot-cell')
    expect(mediaType.map((c) => c.text())).toEqual(['音频', '音视频', '-'])
  })

  it('slotPeerOrGroup：群呼取群名、单聊取对方、缺失占位', async () => {
    const wrapper = mountPage([
      row({ callType: 1, peerNickName: '张三', groupNickName: '群名不应用' }),
      row({ id: 2, callType: 2, peerNickName: '张三', groupNickName: '开发群' }),
      row({ id: 3, callType: 1, peerNickName: null }),
      row({ id: 4, callType: 2, groupNickName: null })
    ])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotPeerOrGroup"]').findAll('.slot-cell')
    expect(cells.map((c) => c.text())).toEqual(['张三', '开发群', '—', '—'])
  })

  it('slotStatus 五态颜色与兜底', async () => {
    const wrapper = mountPage([
      row({ id: 1, status: 1 }),
      row({ id: 2, status: 2 }),
      row({ id: 3, status: 3 }),
      row({ id: 4, status: 4 }),
      row({ id: 5, status: 5 }),
      row({ id: 6, status: 9 })
    ])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotStatus"]').findAll('.slot-cell')
    expect(cells.map((c) => c.find('span').text())).toEqual(['已接', '未接', '拒接', '取消', '忙线', '-'])
    // 注意：jsdom 会把内联 style 的 hex 颜色转成 rgb() 形式
    expect(cells[0].find('span').attributes('style')).toContain('rgb(103, 194, 58)')
    expect(cells[2].find('span').attributes('style')).toContain('rgb(245, 108, 108)')
  })

  it('slotDuration 格式化：占位 / mm:ss / h:mm:ss', async () => {
    const wrapper = mountPage([
      row({ id: 1, durationMs: null }),
      row({ id: 2, durationMs: 'abc' }),
      row({ id: 3, durationMs: -5 }),
      row({ id: 4, durationMs: 3500 }),
      row({ id: 5, durationMs: 3723000 })
    ])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotDuration"]').findAll('.slot-cell')
    expect(cells.map((c) => c.text())).toEqual(['—', '—', '—', '00:03', '1:02:03'])
  })

  it('slotCreateTime 格式化与兜底', async () => {
    const wrapper = mountPage([
      row({ id: 1, createTime: new Date(2026, 9, 1, 9, 5).getTime() }),
      row({ id: 2, createTime: null }),
      row({ id: 3, createTime: 'not-a-number' })
    ])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotCreateTime"]').findAll('.slot-cell')
    expect(cells.map((c) => c.text())).toEqual(['2026-10-01 09:05', '-', '-'])
  })

  it('slotCaller：昵称为空显示占位', async () => {
    const wrapper = mountPage([row({ callerNickName: null })])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotCaller"]').findAll('.slot-cell')
    expect(cells[0].text()).toBe('—')
  })
})
