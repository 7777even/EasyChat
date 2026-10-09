import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UserList from '@/views/admin/UserList.vue'

/**
 * 管理端用户列表的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 昵称(UID) 与性别图标、状态/在线两态
 *   2. 操作列：管理员本人行只显示「管理员」不可操作（防自操作）；
 *       「强制下线」仅在线用户可见
 *   3. changeAccountStatus：状态取反 + Confirm 文案 + 请求 + 重载
 *   4. forceOffLine：Confirm + 请求 + 重载
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
  'el-dropdown': { name: 'el-dropdown', props: ['placement', 'trigger'], template: '<div class="el-dropdown"><slot name="dropdown" /></div>' },
  'el-dropdown-item': {
    name: 'el-dropdown-item',
    emits: ['click'],
    template: `<div class="el-dropdown-item" @click="$emit('click')"><slot /></div>`
  },
  AvatarBase: { name: 'AvatarBase', props: ['width', 'userId', 'partType'], template: '<div class="avatar-base" :data-userid="userId" />' }
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

function mountPage(handler, list = []) {
  request = makeRequest(handler)
  const wrapper = mount(UserList, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: {
            loadAdminAccount: '/admin/loadAdminAccount',
            updateUserStatus: '/admin/updateUserStatus',
            forceOffLine: '/admin/forceOffLine',
            getUserInfo: '/user/getUserInfo'
          },
          Confirm: (opts) => { confirmCalls.push(opts) },
          Message: { success: (m) => messageCalls.push(m) }
        }
      }
    }
  })
  return wrapper
}

function listData(list) {
  return { code: 0, data: { pageNo: 1, pageSize: 15, totalCount: list.length, list } }
}

function user(over = {}) {
  return {
    userId: 'U_1',
    nickName: '张三',
    email: 'a@b.com',
    sex: 1,
    status: 1,
    onlineType: 0,
    areaName: ['北京市'],
    createTime: 1700000000000,
    ...over
  }
}

describe('UserList.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    confirmCalls = []
    messageCalls = []
  })

  it('筛选区两个输入 + 查询', () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      return listData([])
    })
    expect(wrapper.findAll('.el-input')).toHaveLength(2)
    expect(wrapper.findAll('.el-button').map((b) => b.text())).toEqual(['查询'])
  })

  it('Table 8 列与 5 个 scopedSlots', () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      return listData([])
    })
    const columns = wrapper.findComponent({ name: 'Table' }).props('columns')
    expect(columns).toHaveLength(8)
    expect(columns.filter((c) => c.scopedSlots).map((c) => c.scopedSlots)).toEqual([
      'slotAvatar',
      'slotNickName',
      'slotStatus',
      'slotOnline',
      'slotOperation'
    ])
  })

  it('挂载即拉列表与登录信息', () => {
    mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      return listData([])
    })
    const urls = request.__calls__.map((c) => c.url)
    expect(urls).toContain('/admin/loadAdminAccount')
    expect(urls).toContain('/user/getUserInfo')
  })

  it('昵称插槽：昵称(UID) + 性别图标', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      return listData([
        user({ id: 1, sex: 0 }),
        user({ id: 2, sex: 1 }),
        user({ id: 3, sex: 2 })
      ])
    })
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotNickName"]').findAll('.slot-cell')
    // 模板跨行渲染，文本为「昵称 (UID)」形态
    expect(cells[0].text().replace(/\s+/g, '')).toBe('张三(U_1)')
    expect(cells[0].find('.icon-woman').exists()).toBe(true)
    expect(cells[1].find('.icon-man').exists()).toBe(true)
    expect(cells[2].find('.icon-woman').exists()).toBe(false)
    expect(cells[2].find('.icon-man').exists()).toBe(false)
  })

  it('状态 / 在线插槽两态', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      return listData([
        user({ id: 1, status: 0, onlineType: 1 }),
        user({ id: 2, status: 1, onlineType: 0 })
      ])
    })
    await new Promise((r) => setTimeout(r, 0))
    const status = wrapper.find('.col-slot[data-col="slotStatus"]').findAll('.slot-cell')
    expect(status[0].find('span').text()).toBe('禁用')
    expect(status[0].find('span').attributes('style')).toContain('red')
    expect(status[1].find('span').text()).toBe('启用')
    expect(status[1].find('span').attributes('style')).toContain('green')
    const online = wrapper.find('.col-slot[data-col="slotOnline"]').findAll('.slot-cell')
    expect(online[0].find('span').text()).toBe('在线')
    expect(online[1].find('span').text()).toBe('离线')
  })

  it('操作列：管理员本人行只显示「管理员」', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      return listData([user({ userId: 'U_admin' })])
    })
    await new Promise((r) => setTimeout(r, 0))
    const cell = wrapper.find('.col-slot[data-col="slotOperation"] .slot-cell')
    expect(cell.find('.el-dropdown').exists()).toBe(false)
    expect(cell.text()).toBe('管理员')
  })

  it('操作列：他人行有下拉；强制下线仅在线用户可见', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      return listData([
        user({ id: 1, userId: 'U_a', onlineType: 1 }),
        user({ id: 2, userId: 'U_b', onlineType: 0 })
      ])
    })
    await new Promise((r) => setTimeout(r, 0))
    const cells = wrapper.find('.col-slot[data-col="slotOperation"]').findAll('.slot-cell')
    expect(cells[0].findAll('.el-dropdown-item').map((i) => i.text())).toEqual(['禁用', '强制下线'])
    expect(cells[1].findAll('.el-dropdown-item').map((i) => i.text())).toEqual(['禁用'])
  })

  it('changeAccountStatus：启用中的用户 → 确认禁用', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      if (opts.url === '/admin/updateUserStatus') return { code: 0, data: null }
      return listData([user({ id: 1, userId: 'U_a', status: 1 })])
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.col-slot[data-col="slotOperation"] .el-dropdown-item').trigger('click')
    expect(confirmCalls[0].message).toContain('禁用')
    expect(confirmCalls[0].message).toContain('张三')
    const before = request.__calls__.length
    await confirmCalls[0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    const upd = request.__calls__.find((c) => c.url === '/admin/updateUserStatus')
    expect(upd.params).toEqual({ userId: 'U_a', status: 0 })
    expect(messageCalls).toContain('操作成功')
    expect(request.__calls__.length).toBeGreaterThan(before)
  })

  it('changeAccountStatus：禁用中的用户 → 确认启用', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      if (opts.url === '/admin/updateUserStatus') return { code: 0, data: null }
      return listData([user({ id: 2, userId: 'U_b', status: 0 })])
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('.col-slot[data-col="slotOperation"] .el-dropdown-item').trigger('click')
    expect(confirmCalls[0].message).toContain('启用')
    await confirmCalls[0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.find((c) => c.url === '/admin/updateUserStatus').params).toEqual({
      userId: 'U_b',
      status: 1
    })
  })

  it('forceOffLine → Confirm + 请求 + 重载', async () => {
    const wrapper = mountPage((opts) => {
      if (opts.url === '/user/getUserInfo') return { code: 0, data: { userId: 'U_admin' } }
      if (opts.url === '/admin/forceOffLine') return { code: 0, data: null }
      return listData([user({ id: 1, userId: 'U_a', onlineType: 1, nickName: '李四' })])
    })
    await new Promise((r) => setTimeout(r, 0))
    const offlineBtn = wrapper.findAll('.col-slot[data-col="slotOperation"] .el-dropdown-item').find((i) => i.text() === '强制下线')
    await offlineBtn.trigger('click')
    expect(confirmCalls[0].message).toContain('李四')
    expect(confirmCalls[0].message).toContain('强制下线')
    const before = request.__calls__.length
    await confirmCalls[0].okfun()
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.find((c) => c.url === '/admin/forceOffLine').params).toEqual({ userId: 'U_a' })
    expect(messageCalls).toContain('操作成功')
    expect(request.__calls__.length).toBeGreaterThan(before)
  })
})
