<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="440px"
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <div class="report-dialog-body">
      <div class="report-tip">请选择举报原因：</div>
      <el-radio-group v-model="reason">
        <el-radio :label="0">色情低俗</el-radio>
        <el-radio :label="1">暴力血腥</el-radio>
        <el-radio :label="2">诈骗信息</el-radio>
        <el-radio :label="3">侵权 / 抄袭</el-radio>
        <el-radio :label="4">其他违规</el-radio>
      </el-radio-group>
      <el-input
        v-model="description"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="补充说明（选填，最多 200 字）"
        class="report-desc"
      />
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交举报</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { getCurrentInstance } from 'vue'

const { proxy } = getCurrentInstance()

const props = defineProps({
  // 是否显示（v-model）
  modelValue: { type: Boolean, default: false },
  // moment 朋友圈动态 | comment 评论 | message 聊天消息
  type: { type: String, default: 'message' },
  momentId: { type: [Number, String], default: null },
  commentId: { type: [Number, String], default: null },
  messageId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const reason = ref(0)
const description = ref('')
const submitting = ref(false)

const dialogTitle = computed(() => {
  if (props.type === 'moment') return '举报朋友圈动态'
  if (props.type === 'comment') return '举报评论'
  return '举报聊天消息'
})

// 关闭后重置，避免下次打开残留上次输入
const reset = () => {
  reason.value = 0
  description.value = ''
  submitting.value = false
}

const onClosed = () => reset()

const submit = async () => {
  if (submitting.value) return
  submitting.value = true
  try {
    const base = { reason: reason.value, description: description.value }
    let result
    if (props.type === 'message') {
      result = await proxy.Request({
        url: proxy.Api.reportChat,
        params: { ...base, messageId: props.messageId },
        showLoading: true
      })
    } else {
      result = await proxy.Request({
        url: proxy.Api.reportMoment,
        params: {
          ...base,
          momentId: props.momentId || null,
          commentId: props.type === 'comment' ? props.commentId : null
        },
        showLoading: true
      })
    }
    if (!result) return
    proxy.Message.success('举报已提交，感谢您的反馈')
    visible.value = false
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.report-dialog-body {
  .report-tip {
    margin-bottom: 10px;
    color: #606266;
    font-size: 13px;
  }
  .el-radio-group {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .report-desc {
    margin-top: 14px;
  }
}
</style>
