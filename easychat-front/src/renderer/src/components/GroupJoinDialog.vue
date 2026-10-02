<template>
  <el-dialog
    v-model="visible"
    title="加入群聊"
    width="440px"
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <el-tabs v-model="activeTab" class="join-tabs">
      <el-tab-pane label="群二维码" name="qrcode">
        <div class="join-tip">把群主分享的二维码 token 粘贴到下方即可加入。</div>
        <el-input
          v-model="qrToken"
          placeholder="请输入群二维码 token"
          maxlength="64"
          clearable
          @keyup.enter="submit"
        />
      </el-tab-pane>
      <el-tab-pane label="邀请链接" name="invite">
        <div class="join-tip">把群主分享的邀请 token 粘贴到下方即可加入。</div>
        <el-input
          v-model="inviteToken"
          placeholder="请输入邀请链接 token"
          maxlength="64"
          clearable
          @keyup.enter="submit"
        />
      </el-tab-pane>
    </el-tabs>

    <div class="join-note">
      <span class="iconfont icon-tips"></span>
      <span>若该群设置了「需管理员同意后加入」，提交后会进入待审批，需群主或管理员通过。</span>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">加入</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { getCurrentInstance } from 'vue'

/**
 * 加入群聊对话框（群二维码 / 邀请链接）
 *
 * 对应 openspec/specs/group-join-approval：
 *   POST /api/group/qrCode/join  → Result<Integer> joinType
 *   POST /api/group/invite/join  → Result<Integer> joinType
 *   joinType 0 = 已直接加入（本地会话立即可用）
 *   joinType 1 = 已提交入群申请，等待群主或管理员同意
 *
 * 失败（code=1001，如 token 过期/已是成员）由 Request 统一错误提示接管，
 * 本组件**不关闭对话框**也不误报「已加入」，便于用户就地改正 token 重试。
 */
const { proxy } = getCurrentInstance()

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'joined'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const activeTab = ref('qrcode')
const qrToken = ref('')
const inviteToken = ref('')
const submitting = ref(false)

const currentToken = () => (activeTab.value === 'qrcode' ? qrToken.value : inviteToken.value).trim()

const reset = () => {
  activeTab.value = 'qrcode'
  qrToken.value = ''
  inviteToken.value = ''
  submitting.value = false
}

const onClosed = () => reset()

const submit = async () => {
  if (submitting.value) return
  const token = currentToken()
  if (!token) {
    proxy.Message.warning('请输入 token')
    return
  }
  submitting.value = true
  try {
    const isQrcode = activeTab.value === 'qrcode'
    const result = await proxy.Request({
      url: isQrcode ? proxy.Api.joinGroupQrCode : proxy.Api.joinGroupInvite,
      params: isQrcode ? { qrCodeToken: token } : { inviteToken: token },
      showLoading: true
    })
    // result 为 falsy 说明 Request 已统一弹错误提示（token 过期/已是成员等）
    if (!result) return

    if (result.data == 0) {
      proxy.Message.success('已加入该群聊')
      visible.value = false
      // joinType=0 已真正入群，通知外部刷新群列表
      emit('joined', { approved: true })
    } else {
      proxy.Message.success('已提交入群申请，等待群主或管理员同意')
      visible.value = false
      emit('joined', { approved: false })
    }
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.join-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 12px;
  }
}

.join-tip {
  margin-bottom: 10px;
  color: var(--ec-text-secondary, #909399);
  font-size: 13px;
}

.join-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 14px;
  padding: 8px 10px;
  border-radius: 4px;
  background: var(--ec-surface-raised, #f5f7fa);
  color: var(--ec-text-secondary, #909399);
  font-size: 12px;
  line-height: 18px;

  .iconfont {
    flex-shrink: 0;
    margin-top: 2px;
  }
}
</style>
