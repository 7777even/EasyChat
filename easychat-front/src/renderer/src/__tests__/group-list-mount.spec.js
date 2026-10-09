import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import GroupList from '@/views/admin/GroupList.vue'

/**
 * 管理端群组列表的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 三个筛选项（群组ID/群名称/群主UID）+ 查询按钮
 *   2. 各列插槽：群名带ID、群主带UID、加入方式、状态两态、解散入口条件
 *   3. dissolutionGroup：Confirm 文案含群名 + 确认后解散请求 + 重载
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
  'el-form-item': { name: 'el-form-item', props: ['label', 'labelWidth'], template: '<div><slot /></div>' },
  'el-row': { name: 'el-row', template: '<div><slot /></div>' },
  'el-col': { name: 'el-col', props: ['span', 'style'], template: '<div><slot /></div>' },
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
    template: `<button class="el-button" @click="$emit('click')"><slot /></button>`
  },
  AvatarBase: { name: 'AvatarBase', props: ['width', 'userId', 'partType'], template: '<div class="avatar-base" :data-groupid="userId" />' }
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
  const wrapper = mount(GroupList, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: { loadGroup: '/admin/loadGroup', adminDissolutionGroup: '/admin/adminDissolutionGroup' },
          Confirm: (opts) => { confirmCalls.push(opts) },
          Message: { success: (m) => messageCalls.push(m) }
        }
      }
    }
  })
  return wrapper
}

function group(over = {}) {
  return {
    groupId: 'G1',
    groupName: '开发群',
    groupOwnerId: 'U_owner',
    groupOwnerNickName: '群主甲',
    memberCount: 3,
    createTime: 1700000000000,
    joinType: 0,
    status: 1,
    ...over
  }
}

describe('GroupList.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    confirmCalls = []
    messageCalls = []
  })

  it('三个筛选项 + 查询按钮渲染', () => {
    const wrapper = mountPage()
    expect(wrapper.findAll('.el-input')).toHaveLength(3)
    expect(wrapper.findAll('.el-button').map((b) => b.text())).toEqual(['查询'])
  })

  it('Table 8 列与 5 个 scopedSlots', () => {
    const wrapper = mountPage()
    const columns = wrapper.findComponent({ name: 'Table' }).props('columns')
    expect(columns).toHaveLength(8)
    expect(columns.filter((c) => c.scopedSlots).map((c) => c.scopedSlots)).toEqual([
      'slotAvatar',
      'slotGroupName',
      'slotGroupOwnerNickName',
      'slotJoinType',
      'slotStatus',
      'slotOperation'
    ])
  })

  it('initFetch → 默认参数（分页空）', () => {
    mountPage()
    expect(request.__calls__[0].url).toBe('/admin/loadGroup')
    expect(request.__calls__[0].params).toMatchObject({ pageNo: undefined, pageSize: undefined })
  })

  it('查询 → 参数带三个搜索字段', async () => {
    const wrapper = mountPage()
    await wrapper.findAll('.el-input')[0].setValue('G1')
    await wrapper.findAll('.el-input')[1].setValue('开发')
    await wrapper.findAll('.el-input')[2].setValue('U_owner')
    await wrapper.find('.el-button').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__[1].params).toMatchObject({
      groupId: 'G1',
      groupNameFuzzy: '开发',
      groupOwnerId: 'U_owner'
    })
  })

  it('slotGroupName / slotGroupOwnerNickName 附带 ID', async () => {
    const wrapper = mountPage([group()])
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.col-slot[data-col="slotGroupName"] .slot-cell').text()).toBe('开发群(G1)')
    expect(wrapper.find('.col-slot[data-col="slotGroupOwnerNickName"] .slot-cell').text()).toBe('群主甲(U_owner)')
  })

  it('slotJoinType 两态文本', async () => {
    const wrapper = mountPage([group({ joinType: 0 }), group({ id: 2, joinType: 1 }), group({ id: 3, joinType: 9 })])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotJoinType"]').findAll('.slot-cell')
    expect(cells.map((c) => c.text())).toEqual(['直接加入', '管理员同意后加入', '管理员同意后加入'])
  })

  it('slotStatus 两态（0 已解散红 / 1 正常绿）', async () => {
    const wrapper = mountPage([group({ status: 0 }), group({ id: 2, status: 1 })])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotStatus"]').findAll('.slot-cell')
    expect(cells[0].find('span').text()).toBe('已解散')
    expect(cells[0].find('span').attributes('style')).toContain('red')
    expect(cells[1].find('span').text()).toBe('正常')
    expect(cells[1].find('span').attributes('style')).toContain('green')
  })

  it('slotOperation：仅正常群有解散入口', async () => {
    const wrapper = mountPage([group({ status: 0 }), group({ id: 2, status: 1 })])
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotOperation"]').findAll('.slot-cell')
    expect(cells[0].find('a').exists()).toBe(false)
    expect(cells[1].find('a').text()).toBe('解散')
  })

  it('slotAvatar：AvatarBase 传 groupId', async () => {
    const wrapper = mountPage([group()])
    await new Promise((r) => setTimeout(r, 0))
    const avatar = wrapper.find('.col-slot[data-col="slotAvatar"] .avatar-base')
    expect(avatar.attributes('data-groupid')).toBe('G1')
  })

  it('dissolutionGroup → Confirm 含群名 + 解散后重载', async () => {
    const wrapper = mountPage([group()])
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.col-slot[data-col="slotOperation"] .slot-cell a').trigger('click')
    expect(confirmCalls).toHaveLength(1)
    expect(confirmCalls[0].message).toContain('开发群')
    const before = request.__calls__.length
    await confirmCalls[0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    const del = request.__calls__.find((c) => c.url === '/admin/adminDissolutionGroup')
    expect(del).toBeTruthy()
    expect(del.params.groupId).toBe('G1')
    expect(messageCalls).toContain('解散成功')
    expect(request.__calls__.length).toBeGreaterThan(before)
  })
})
