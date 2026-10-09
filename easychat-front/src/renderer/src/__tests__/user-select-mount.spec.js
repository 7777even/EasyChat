import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UserSelect from '@/views/chat/UserSelect.vue'

/**
 * 群员选择（穿梭框）弹窗的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. show()：按 opType 切标题（移除群员/添加群员）+ 注入候选列表
 *   2. 未选联系人点确定 → warning 不发请求
 *   3. 选中后确定 → addOrRemoveGroupUser 参数（selectContacts 逗号拼接、groupId、opType）+ 关窗 + emit
 *   4. Request 返回空 → 弹窗不关、不 emit（失败不吞）
 *   5. transfer 的 filter-method：大小写不敏感的联系人名过滤
 */

const MessageStub = { warning: vi.fn(), success: vi.fn(), error: vi.fn() }

function makeRequest (handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

const DialogStub = {
  name: 'Dialog',
  props: ['show', 'title', 'buttons', 'width', 'showCancel'],
  emits: ['close'],
  template: `
    <div v-if="show" class="dialog-stub">
      <div class="dialog-title">{{ title }}</div>
      <slot />
      <div class="dialog-footer">
        <button
          v-for="btn in buttons"
          :key="btn.text"
          class="footer-btn"
          @click="btn.click()"
        >{{ btn.text }}</button>
      </div>
    </div>`
}
const ElTransferStub = {
  name: 'ElTransfer',
  props: ['modelValue', 'titles', 'format', 'data', 'props', 'filterable', 'filterMethod'],
  emits: ['update:modelValue'],
  template: `
    <div class="transfer-stub">
      <div class="option-item" v-for="opt in data" :key="opt[props.key]">
        <slot :option="opt" />
      </div>
    </div>`
}
const AvatarBaseStub = {
  name: 'AvatarBase',
  props: ['userId', 'width', 'borderRadius', 'showDetail'],
  template: '<div class="avatar-base-stub" :data-userid="userId" />'
}

const CONTACTS = [
  { contactId: 'U001', contactName: '张三', disabled: true },
  { contactId: 'U002', contactName: '李四' },
  { contactId: 'U003', contactName: '王五' }
]

function mountSelect (handler) {
  const request = makeRequest(handler)
  const wrapper = mount(UserSelect, {
    global: {
      stubs: { Dialog: DialogStub, 'el-transfer': ElTransferStub, AvatarBase: AvatarBaseStub },
      config: {
        globalProperties: {
          Request: request,
          Api: { addOrRemoveGroupUser: '/group/addOrRemoveUser' },
          Message: MessageStub
        }
      }
    }
  })
  return { wrapper, request }
}

async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

beforeEach(() => {
  MessageStub.warning.mockClear()
})

describe('UserSelect.vue 真实挂载（DOM 级）', () => {
  it('show(opType=1)：标题「添加群员」+ 候选列表渲染', async () => {
    const { wrapper } = mountSelect()
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    wrapper.vm.show({ contactList: CONTACTS, groupId: 'G1', opType: 1 })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.dialog-title').text()).toBe('添加群员')
    expect(wrapper.findAll('.option-item')).toHaveLength(3)
    expect(wrapper.findAll('.nick-name').map((n) => n.text())).toEqual(['张三', '李四', '王五'])
    expect(wrapper.find('.avatar-base-stub').attributes('data-userid')).toBe('U001')
  })

  it('show(opType=0)：标题「移除群员」', async () => {
    const { wrapper } = mountSelect()
    wrapper.vm.show({ contactList: CONTACTS, groupId: 'G1', opType: 0 })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.dialog-title').text()).toBe('移除群员')
  })

  it('重复 show：重置已选列表（上次选中不带到本轮）', async () => {
    const { wrapper, request } = mountSelect()
    wrapper.vm.show({ contactList: CONTACTS, groupId: 'G1', opType: 1 })
    await wrapper.vm.$nextTick()
    wrapper.findComponent(ElTransferStub).vm.$emit('update:modelValue', ['U002'])
    await wrapper.vm.$nextTick()

    // 再次 show（重新打开场景）→ 已选应清空，点确定走「未选」分支
    wrapper.vm.show({ contactList: CONTACTS, groupId: 'G1', opType: 1 })
    await wrapper.vm.$nextTick()
    await wrapper.find('.footer-btn').trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请选择联系人')
    expect(request).not.toHaveBeenCalled()
  })

  it('未选联系人点确定 → warning 不发请求', async () => {
    const { wrapper, request } = mountSelect()
    wrapper.vm.show({ contactList: CONTACTS, groupId: 'G1', opType: 1 })
    await wrapper.vm.$nextTick()
    await wrapper.find('.footer-btn').trigger('click')
    expect(MessageStub.warning).toHaveBeenCalledWith('请选择联系人')
    expect(request).not.toHaveBeenCalled()
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
  })

  it('选中后确定 → 参数拼接正确 + 关窗 + emit callback', async () => {
    const { wrapper, request } = mountSelect()
    wrapper.vm.show({ contactList: CONTACTS, groupId: 'G1', opType: 0 })
    await wrapper.vm.$nextTick()
    // 直接构造选中态（穿梭交互属 Element 内部，非本组件逻辑）
    wrapper.findComponent(ElTransferStub).vm.$emit('update:modelValue', ['U002', 'U003'])
    await wrapper.vm.$nextTick()

    await wrapper.find('.footer-btn').trigger('click')
    await flush()

    expect(request.__calls__).toHaveLength(1)
    expect(request.__calls__[0].url).toBe('/group/addOrRemoveUser')
    expect(request.__calls__[0].params).toEqual({
      groupId: 'G1',
      opType: 0,
      selectContacts: 'U002,U003'
    })
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
    expect(wrapper.emitted('callback')).toHaveLength(1)
  })

  it('Request 返回空 → 弹窗不关、不 emit（失败不静默）', async () => {
    const { wrapper, request } = mountSelect(() => undefined)
    wrapper.vm.show({ contactList: CONTACTS, groupId: 'G1', opType: 1 })
    await wrapper.vm.$nextTick()
    wrapper.findComponent(ElTransferStub).vm.$emit('update:modelValue', ['U002'])
    await wrapper.vm.$nextTick()

    await wrapper.find('.footer-btn').trigger('click')
    await flush()

    expect(request).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.dialog-stub').exists()).toBe(true)
    expect(wrapper.emitted('callback')).toBeUndefined()
  })

  it('filter-method：大小写不敏感匹配联系人名', async () => {
    const { wrapper } = mountSelect()
    wrapper.vm.show({ contactList: CONTACTS, groupId: 'G1', opType: 1 })
    await wrapper.vm.$nextTick()
    const filter = wrapper.findComponent(ElTransferStub).props('filterMethod')
    expect(filter('张', CONTACTS[0])).toBe(true)
    expect(filter('Z', { contactName: 'zhangsan' })).toBe(true)
    expect(filter('李', CONTACTS[2])).toBe(false)
    expect(filter('', CONTACTS[1])).toBe(true)
  })
})
