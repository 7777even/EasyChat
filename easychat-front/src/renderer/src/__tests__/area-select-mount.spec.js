import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AreaSelect from '@/components/AreaSelect.vue'

/**
 * 省市区选择器组件的**真实挂载**测试（DOM 级）。
 *
 * 被测点（change 路径）：
 *   1. 挂载后渲染 el-cascader
 *   2. change 且有选中节点 → emit { areaName: pathLabels, areaCode: pathValues }
 *   3. change 无选中节点（清空）→ emit 空 areaData（不抛异常）
 *
 * stub el-cascader 并提供可控 getCheckedNodes，以精确驱动两条 change 路径。
 */

const cascaderStub = {
  name: 'el-cascader',
  template: `<div class="el-cascader" @click="$emit('change')"></div>`,
  props: ['options', 'modelValue', 'clearable'],
  emits: ['change'],
  data() {
    return { checkedNodes: [] }
  },
  methods: {
    getCheckedNodes() {
      return this.checkedNodes
    }
  }
}

function mountAreaSelect() {
  const wrapper = mount(AreaSelect, {
    props: { modelValue: { areaCode: null, areaName: null } },
    global: { stubs: { 'el-cascader': cascaderStub } }
  })
  return wrapper
}

describe('AreaSelect.vue 真实挂载（DOM 级）', () => {
  it('挂载后渲染 el-cascader', () => {
    const wrapper = mountAreaSelect()
    expect(wrapper.find('.el-cascader').exists()).toBe(true)
  })

  it('change 有选中节点 → emit areaName + areaCode', async () => {
    const wrapper = mountAreaSelect()
    const stub = wrapper.findComponent({ name: 'el-cascader' })
    // 模拟级联选中路径
    stub.vm.checkedNodes = [{
      pathValues: ['110000', '110100', '110105'],
      pathLabels: ['北京市', '北京市', '朝阳区']
    }]
    await stub.trigger('click')
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const payload = emitted[0][0]
    expect(payload.areaCode).toEqual(['110000', '110100', '110105'])
    expect(payload.areaName).toEqual(['北京市', '北京市', '朝阳区'])
  })

  it('change 无选中节点 → emit 空 areaData 不抛异常', async () => {
    const wrapper = mountAreaSelect()
    const stub = wrapper.findComponent({ name: 'el-cascader' })
    stub.vm.checkedNodes = []
    await stub.trigger('click')
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const payload = emitted[0][0]
    expect(payload.areaCode).toEqual([])
    expect(payload.areaName).toEqual([])
  })

  it('change 选中节点 pathValues 为空数组 → 仍正常 emit', async () => {
    const wrapper = mountAreaSelect()
    const stub = wrapper.findComponent({ name: 'el-cascader' })
    stub.vm.checkedNodes = [{ pathValues: [], pathLabels: [] }]
    await stub.trigger('click')
    const payload = wrapper.emitted('update:modelValue')[0][0]
    expect(payload.areaCode).toEqual([])
    expect(payload.areaName).toEqual([])
  })
})
