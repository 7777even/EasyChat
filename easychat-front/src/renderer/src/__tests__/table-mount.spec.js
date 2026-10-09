import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Table from '@/components/Table.vue'

/**
 * 通用表格组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. initFetch + fetch → 挂载即拉数据
 *   2. 高度计算 → innerHeight - 固定 134 - extHeight
 *   3. 行点击 / 多选 → emit
 *   4. 分页切换 → fetch 重新拉取、pageSize 变时 pageNo 回 1
 *   5. showPagination=false → 不渲染分页
 *   6. options.showIndex → 序号列；selectType=checkbox → 多选列
 *   7. setCurrentRow / clearSelection → 转发给 el-table ref
 */

const elTableStub = {
  name: 'el-table',
  props: ['data', 'height', 'stripe', 'border'],
  emits: ['rowClick', 'selectionChange'],
  data() {
    return { cleared: false, currentRow: null }
  },
  methods: {
    clearSelection() { this.cleared = true },
    setCurrentRow(row) { this.currentRow = row }
  },
  template: `<div class="el-table">
    <div v-for="(r, i) in data" :key="i" class="row" @click="$emit('rowClick', r)">{{ r.id }}</div>
    <slot />
  </div>`
}

const elTableColumnStub = {
  name: 'el-table-column',
  props: ['prop', 'label', 'align', 'width', 'fixed', 'type', 'selectable'],
  template: `<div class="el-table-column" :data-type="type" :data-label="label"><slot /></div>`
}

const elPaginationStub = {
  name: 'el-pagination',
  props: ['total', 'pageSizes', 'pageSize', 'currentPage', 'layout', 'background'],
  emits: ['sizeChange', 'currentChange'],
  template: `<div class="el-pagination">
    <button class="size-btn" @click="$emit('sizeChange', 50)">50</button>
    <button class="page-btn" @click="$emit('currentChange', 3)">3</button>
  </div>`
}

const stubs = {
  'el-table': elTableStub,
  'el-table-column': elTableColumnStub,
  'el-pagination': elPaginationStub
}

function dataSource() {
  return { list: [{ id: 1 }, { id: 2 }], totalCount: 100, pageSize: 15, pageNo: 2 }
}

describe('Table.vue 真实挂载（DOM 级）', () => {
  it('initFetch + fetch → 挂载即拉数据', () => {
    const fetch = vi.fn()
    mount(Table, {
      props: { dataSource: dataSource(), columns: [], fetch },
      global: { stubs }
    })
    expect(fetch).toHaveBeenCalled()
  })

  it('高度 = innerHeight - 134 - extHeight（默认70）', () => {
    const fetch = vi.fn()
    const wrapper = mount(Table, {
      props: { dataSource: dataSource(), columns: [], fetch },
      global: { stubs }
    })
    // jsdom innerHeight=768
    const height = wrapper.findComponent({ name: 'el-table' }).props('height')
    expect(height).toBe(768 - 134 - 70)
  })

  it('options.tableHeight 优先', () => {
    const wrapper = mount(Table, {
      props: {
        dataSource: dataSource(),
        columns: [],
        fetch: () => {},
        options: { tableHeight: 500 }
      },
      global: { stubs }
    })
    expect(wrapper.findComponent({ name: 'el-table' }).props('height')).toBe(500)
  })

  it('行点击 → emit rowClick', async () => {
    const wrapper = mount(Table, {
      props: { dataSource: dataSource(), columns: [], fetch: () => {} },
      global: { stubs }
    })
    await wrapper.find('.row').trigger('click')
    const emitted = wrapper.emitted('rowClick')
    expect(emitted).toBeTruthy()
    expect(emitted[0][0].id).toBe(1)
  })

  it('切每页大小 → fetch + pageNo 回 1', async () => {
    const ds = dataSource()
    const fetch = vi.fn()
    const wrapper = mount(Table, {
      props: { dataSource: ds, columns: [], fetch },
      global: { stubs }
    })
    await wrapper.findComponent({ name: 'el-pagination' }).find('.size-btn').trigger('click')
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(ds.pageSize).toBe(50)
    expect(ds.pageNo).toBe(1)
  })

  it('切页码 → fetch', async () => {
    const ds = dataSource()
    const fetch = vi.fn()
    const wrapper = mount(Table, {
      props: { dataSource: ds, columns: [], fetch },
      global: { stubs }
    })
    await wrapper.findComponent({ name: 'el-pagination' }).find('.page-btn').trigger('click')
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(ds.pageNo).toBe(3)
  })

  it('showPagination=false → 不渲染分页', () => {
    const wrapper = mount(Table, {
      props: { dataSource: dataSource(), columns: [], fetch: () => {}, showPagination: false },
      global: { stubs }
    })
    expect(wrapper.find('.pagination').exists()).toBe(false)
  })

  it('options.showIndex → 序号列', () => {
    const wrapper = mount(Table, {
      props: { dataSource: dataSource(), columns: [], fetch: () => {}, options: { showIndex: true } },
      global: { stubs }
    })
    const indexCol = wrapper.find('.el-table-column[data-label="序号"]')
    expect(indexCol.exists()).toBe(true)
  })

  it('setCurrentRow / clearSelection → 转发 el-table ref', async () => {
    const wrapper = mount(Table, {
      props: { dataSource: dataSource(), columns: [], fetch: () => {} },
      global: { stubs }
    })
    const table = wrapper.findComponent({ name: 'el-table' })
    wrapper.vm.setCurrentRow('id', 2)
    expect(table.vm.currentRow).toEqual({ id: 2 })
    wrapper.vm.clearSelection()
    expect(table.vm.cleared).toBe(true)
  })
})
