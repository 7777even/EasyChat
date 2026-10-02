<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="620px"
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <el-transfer
      v-model="innerValue"
      :titles="['全部好友', '已选']"
      :data="allFriends"
      :props="{ key: 'id', label: 'name' }"
      filterable
      :filter-method="search"
      :loading="loading"
    >
      <template #default="{ option }">
        <div class="picker-item">
          <AvatarBase :userId="option.id" :width="28" :borderRadius="4" :showDetail="false" />
          <span class="picker-name">{{ option.name }}</span>
        </div>
      </template>
    </el-transfer>
    <div v-if="!loading && allFriends.length === 0" class="picker-empty">你还没有好友，无法选择</div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 通用联系人选择器（纯选择，无提交副作用）
 *
 * 与 `views/chat/UserSelect.vue` 的区别：后者是**群成员专用**选择器，
 * 硬编码「添加/移除群员」标题且 submit 时直接调 `addOrRemoveGroupUser`（有副作用），
 * 因此不能被隐私设置/发布页复用。本组件只负责「拉好友 + 双向选择 + 抛结果」。
 *
 * 隐私设置（朋友圈白/黑名单）与发布朋友圈（单条白/黑名单）共用。
 *
 * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
 */
import { ref, computed, watch, getCurrentInstance } from 'vue'
import AvatarBase from '@/components/AvatarBase.vue'

const { proxy } = getCurrentInstance()

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 弹窗标题
  title: { type: String, default: '选择联系人' },
  // 已选中的 userId 数组
  selected: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const allFriends = ref([])
const loading = ref(false)
// 弹窗内的临时选择，取消时不污染外部值
const innerValue = ref([])

const search = (query, item) => {
  return (item.name || '').toLowerCase().includes((query || '').toLowerCase())
}

const loadFriends = async () => {
  loading.value = true
  try {
    let result = await proxy.Request({
      url: proxy.Api.loadContact,
      showLoading: false,
      params: { contactType: 'USER' }
    })
    if (!result) {
      allFriends.value = []
      return
    }
    allFriends.value = (result.data || []).map((item) => ({
      id: item.contactId,
      // 备注优先：后端 contactName 已按 COALESCE(NULLIF(remark,''), nick_name) 取值
      name: item.contactName || item.contactId
    }))
  } finally {
    loading.value = false
  }
}

// 打开时载入好友并带上已选
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    innerValue.value = [...(props.selected || [])]
    loadFriends()
  },
  { immediate: true }
)

const onClosed = () => {
  innerValue.value = []
}

const confirm = () => {
  emit('confirm', [...innerValue.value])
  visible.value = false
}
</script>

<style lang="scss" scoped>
.el-transfer {
  display: block;
  :deep(.el-transfer-panel) {
    width: 250px;
  }
  :deep(.el-transfer-panel__item) {
    display: flex;
    align-items: center;
  }
}

.picker-item {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
}

.picker-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker-empty {
  margin-top: 10px;
  color: var(--ec-text-secondary, #909399);
  font-size: 12px;
  text-align: center;
}
</style>
