<template>
  <el-dialog
    v-model="dialogVisible"
    title="群文件"
    width="640px"
    :close-on-click-modal="false"
    @close="resetState"
  >
    <div class="group-file-panel">
      <div class="op-bar">
        <el-button type="primary" size="small" :disabled="uploading" @click="triggerUpload">
          <span class="iconfont icon-upload"></span> 上传文件
        </el-button>
        <input
          ref="fileInput"
          type="file"
          style="display: none"
          @change="onFileChange"
        />
        <span class="count-tip">共 {{ totalCount }} 个文件</span>
      </div>

      <div v-if="uploading" class="upload-progress">
        <el-progress :percentage="uploadProgress" :stroke-width="12"></el-progress>
        <span class="upload-tip">正在上传：{{ uploadingName }}</span>
      </div>

      <div v-if="loading" class="empty-tip">加载中...</div>
      <div v-else-if="fileList.length === 0" class="empty-tip">还没有群文件，点击「上传文件」添加</div>

      <div v-else class="file-list">
        <div v-for="file in fileList" :key="file.id" class="file-item">
          <div class="file-thumb">
            <ShowLocalImage
              v-if="file.fileType === 0"
              :fileId="file.filePath"
              partType="group"
              :fileType="0"
              :width="44"
            ></ShowLocalImage>
            <span
              v-else
              class="iconfont"
              :class="file.fileType === 1 ? 'icon-video' : 'icon-file'"
            ></span>
          </div>
          <div class="file-meta">
            <div class="file-name" :title="file.fileName">{{ file.fileName }}</div>
            <div class="file-sub">
              {{ formatSize(file.fileSize) }} · {{ file.uploadUserNickName || file.uploadUserId }}
            </div>
          </div>
          <div class="file-actions">
            <a
              class="action-link"
              :href="downloadUrl(file)"
              :download="file.fileName"
            >下载</a>
            <span class="action-link danger" @click="delFile(file)">删除</span>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, getCurrentInstance } from 'vue'
import Request from '@/utils/Request'
import Api from '@/utils/Api'
import groupFileChunkUploadApi from '@/utils/GroupFileChunkUploadApi'
import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'
import ShowLocalImage from '@/components/ShowLocalImage.vue'

const { proxy } = getCurrentInstance()
const globalInfoStore = useGlobalInfoStore()

const dialogVisible = ref(false)
const loading = ref(false)
const fileList = ref([])
const totalCount = ref(0)
const groupId = ref('')
const fileInput = ref(null)

const uploading = ref(false)
const uploadProgress = ref(0)
const uploadingName = ref('')

const show = (gid) => {
  groupId.value = gid
  dialogVisible.value = true
  loadList()
}

const resetState = () => {
  uploading.value = false
  uploadProgress.value = 0
  uploadingName.value = ''
}

const loadList = async () => {
  if (!groupId.value) return
  loading.value = true
  try {
    const result = await Request({
      url: Api.groupFileList,
      params: { groupId: groupId.value, pageNo: 1, pageSize: 100 },
      showLoading: false
    })
    if (result && result.code === 0) {
      fileList.value = result.data.list || []
      totalCount.value = result.data.totalCount || 0
    }
  } finally {
    loading.value = false
  }
}

const formatSize = (size) => {
  if (!size) return '0 B'
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'
  return (size / 1024 / 1024).toFixed(1) + ' MB'
}

const fileTypeOf = (fileName) => {
  const ext = (fileName.split('.').pop() || '').toLowerCase()
  if (['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp'].includes(ext)) return 0
  if (['mp4', 'avi', 'rmvb', 'mkv', 'mov'].includes(ext)) return 1
  return 2
}

const downloadUrl = (file) => {
  const serverPort = globalInfoStore.getInfo('localServerPort')
  return `http://localhost:${serverPort}/file?fileId=${encodeURIComponent(file.filePath)}&partType=group&showCover=false`
}

const triggerUpload = () => {
  fileInput.value.click()
}

const onFileChange = async (e) => {
  const file = e.target.files && e.target.files[0]
  // 重置 input，保证同一文件可重复选择
  e.target.value = ''
  if (!file) return

  const fileType = fileTypeOf(file.name)
  uploading.value = true
  uploadProgress.value = 0
  uploadingName.value = file.name
  try {
    await groupFileChunkUploadApi.uploadFile(file, groupId.value, fileType, {
      onProgress: (p) => {
        uploadProgress.value = p
      }
    })
    proxy.Message.success('上传成功')
    await loadList()
  } catch (err) {
    // 错误已由 Request 拦截器提示
  } finally {
    uploading.value = false
  }
}

const delFile = async (file) => {
  try {
    const result = await Request({
      url: Api.groupFileDelete,
      params: { groupId: groupId.value, fileId: file.id },
      showLoading: true
    })
    if (result && result.code === 0) {
      proxy.Message.success('已删除')
      await loadList()
    }
  } catch (err) {
    // 无权限等错误已由拦截器提示
  }
}

defineExpose({ show })
</script>

<style lang="scss" scoped>
.group-file-panel {
  .op-bar {
    display: flex;
    align-items: center;
    margin-bottom: 12px;

    .count-tip {
      margin-left: 12px;
      color: #999;
      font-size: 13px;
    }
  }

  .upload-progress {
    margin-bottom: 12px;

    .upload-tip {
      display: block;
      margin-top: 4px;
      color: #666;
      font-size: 13px;
    }
  }

  .empty-tip {
    text-align: center;
    color: #999;
    padding: 40px 0;
    font-size: 14px;
  }

  .file-list {
    max-height: 420px;
    overflow-y: auto;

    .file-item {
      display: flex;
      align-items: center;
      padding: 10px 8px;
      border-bottom: 1px solid #f0f0f0;

      &:hover {
        background: #f7f7f7;
      }

      .file-thumb {
        width: 44px;
        height: 44px;
        display: flex;
        align-items: center;
        justify-content: center;
        margin-right: 12px;
        background: #f0f0f0;
        border-radius: 4px;
        overflow: hidden;

        .iconfont {
          font-size: 24px;
          color: #999;
        }
      }

      .file-meta {
        flex: 1;
        min-width: 0;

        .file-name {
          font-size: 14px;
          color: #333;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }

        .file-sub {
          font-size: 12px;
          color: #999;
          margin-top: 4px;
        }
      }

      .file-actions {
        display: flex;
        gap: 14px;
        margin-left: 12px;

        .action-link {
          color: #07c160;
          cursor: pointer;
          font-size: 13px;

          &.danger {
            color: #f56c6c;
          }

          &:hover {
            opacity: 0.8;
          }
        }
      }
    }
  }
}
</style>
