<template>
  <div class="backup-page">
    <div class="backup-card">
      <div class="card-title">聊天数据备份</div>
      <div class="desc">
        以<strong>服务端</strong>为数据源，把当前账号<strong>全部会话</strong>的聊天记录导出为一个文件。
        只导出单个会话请在会话上右键选择「导出云端全量」。
      </div>

      <div class="row">
        <span class="label">可备份会话</span>
        <span class="value">{{ sessionList.length }} 个</span>
      </div>

      <div class="row">
        <span class="label">备份格式</span>
        <el-select v-model="format" size="small" style="width: 160px">
          <el-option label="TXT 文本" value="txt" />
          <el-option label="CSV 表格" value="csv" />
        </el-select>
      </div>

      <div class="actions">
        <el-button
          type="primary"
          :loading="backing"
          :disabled="backing || sessionList.length === 0"
          @click="startBackup"
        >
          {{ backing ? '备份中…' : '开始备份' }}
        </el-button>
      </div>

      <div v-if="progress" class="progress">{{ progress }}</div>

      <div class="tip">
        备份会逐会话向服务端分页请求全量历史，会话较多时耗时较长，请保持网络与后端在线。
        单个会话拉取失败会自动跳过，结束后单独提示，不影响其余会话。
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, getCurrentInstance } from 'vue'
import { backupAllSessions, MAX_TOTAL_MESSAGES } from '@/utils/cloudBackup'

const { proxy } = getCurrentInstance()

const format = ref('txt')
const backing = ref(false)
const progress = ref('')
const sessionList = ref([])

// 本次备份的失败会话与是否截断，落盘回调里一并提示
let lastFailed = []
let lastTruncated = false

const onLoadSessionData = (e, data) => {
  sessionList.value = Array.isArray(data) ? data : []
}

const startBackup = async () => {
  if (backing.value) {
    return
  }
  if (sessionList.value.length === 0) {
    proxy.Message.warning('暂无可备份的会话')
    return
  }
  backing.value = true
  progress.value = '准备中…'
  try {
    const res = await backupAllSessions({
      sessions: sessionList.value,
      onProgress: ({ current, total, title }) => {
        progress.value = `正在备份第 ${current}/${total} 个会话：${title}`
      }
    })
    lastFailed = res.failedSessions || []
    lastTruncated = res.truncated
    if (!res.groups || res.groups.length === 0) {
      backing.value = false
      progress.value = ''
      proxy.Message.warning('没有可备份的消息')
      return
    }
    progress.value = '正在写入文件…'
    window.ipcRenderer.send('exportChatBackup', { format: format.value, groups: res.groups })
  } catch (e) {
    backing.value = false
    progress.value = ''
    proxy.Message.warning('备份失败：' + ((e && e.message) || '请稍后重试'))
  }
}

const onBackupCallback = (e, result) => {
  backing.value = false
  progress.value = ''
  if (!result) {
    return
  }
  // 用户主动取消保存：静默返回，不打扰
  if (result.canceled) {
    return
  }
  if (!result.success) {
    proxy.Message.warning(result.error || '备份失败')
    return
  }
  proxy.Message.success(`已备份 ${result.count} 条消息（${result.sessionCount} 个会话）：${result.path}`)
  if (lastTruncated) {
    proxy.Message.warning(`消息量已达上限 ${MAX_TOTAL_MESSAGES} 条，本次为截断结果`)
  }
  if (lastFailed && lastFailed.length > 0) {
    proxy.Message.warning(`${lastFailed.length} 个会话拉取失败已跳过：${lastFailed.join('、')}`)
  }
}

onMounted(() => {
  if (window.ipcRenderer) {
    window.ipcRenderer.on('loadSessionDataCallback', onLoadSessionData)
    window.ipcRenderer.on('exportChatBackupCallback', onBackupCallback)
    window.ipcRenderer.send('loadSessionData')
  }
})

onUnmounted(() => {
  if (window.ipcRenderer) {
    window.ipcRenderer.removeAllListeners('loadSessionDataCallback')
    window.ipcRenderer.removeAllListeners('exportChatBackupCallback')
  }
})
</script>

<style lang="scss" scoped>
.backup-page {
  height: 100%;
  padding: 20px;
  box-sizing: border-box;
  overflow: auto;
  background: var(--ec-chat-bg);
}

.backup-card {
  background: var(--ec-card-bg);
  color: var(--ec-card-text);
  border-radius: 8px;
  padding: 20px 24px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);

  .card-title {
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 10px;
  }

  .desc {
    font-size: 13px;
    line-height: 1.8;
    color: var(--ec-body-text);
    margin-bottom: 18px;
  }

  .row {
    display: flex;
    align-items: center;
    margin-bottom: 14px;

    .label {
      width: 90px;
      font-size: 13px;
      color: var(--ec-body-text);
    }

    .value {
      font-size: 13px;
    }
  }

  .actions {
    margin-top: 6px;
  }

  .progress {
    margin-top: 14px;
    font-size: 13px;
    color: #07c160;
  }

  .tip {
    margin-top: 18px;
    padding-top: 14px;
    border-top: 1px solid var(--ec-divider-soft);
    font-size: 12px;
    line-height: 1.8;
    color: var(--ec-body-text);
  }
}
</style>
