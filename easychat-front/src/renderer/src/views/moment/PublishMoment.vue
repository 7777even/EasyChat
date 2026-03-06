<template>
  <Dialog
    :show="dialogConfig.show"
    :title="dialogConfig.title"
    :buttons="dialogConfig.buttons"
    width="600px"
    :showCancel="false"
    @close="closeDialog"
  >
    <div class="publish-moment">
      <el-input
        type="textarea"
        v-model="formData.content"
        :autosize="{ minRows: 4, maxRows: 8 }"
        maxlength="500"
        show-word-limit
        placeholder="这一刻的想法..."
      />

      <!-- 图片/视频预览 -->
      <div v-if="mediaList.length > 0" class="media-preview">
        <div v-for="(media, index) in mediaList" :key="index" class="media-item">
          <img v-if="media.type === 'image'" :src="media.preview" alt="preview" />
          <video v-else :src="media.preview" />
          <div class="media-mask">
            <i class="iconfont icon-close" @click="removeMedia(index)"></i>
          </div>
          <div v-if="media.type === 'video'" class="video-tag">
            <i class="iconfont icon-video"></i>
          </div>
        </div>
        <div v-if="mediaList.length < 9" class="add-media" @click="selectMedia">
          <i class="iconfont icon-add"></i>
        </div>
      </div>
      <div v-else class="media-actions">
        <div class="action-item" @click="selectMedia">
          <i class="iconfont icon-image"></i>
          <span>照片/视频</span>
        </div>
      </div>

      <!-- 位置 -->
      <div class="location-input">
        <el-input
          v-model="formData.location"
          placeholder="所在位置"
          size="small"
        >
          <template #prefix>
            <i class="iconfont icon-location"></i>
          </template>
        </el-input>
      </div>

      <!-- 可见范围 -->
      <div class="visibility-select">
        <span class="label">谁可以看</span>
        <el-select v-model="formData.visibility" size="small">
          <el-option
            v-for="item in visibilityOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>

      <!-- 上传进度显示 -->
      <div v-if="uploadingFiles.length > 0" class="uploading-panel">
        <div class="uploading-title">
          <i class="iconfont icon-loading"></i>
          正在上传媒体文件...
        </div>
        <div v-for="(item, index) in uploadingFiles" :key="index" class="upload-item">
          <div class="file-info">
            <span class="file-name">{{ item.fileName }}</span>
            <span class="file-type">{{ item.type }}</span>
          </div>
          <el-progress 
            :percentage="item.progress" 
            :status="item.status === 'success' ? 'success' : item.status === 'error' ? 'exception' : ''"
          />
          <div class="progress-text">
            {{ item.uploadedChunks }}/{{ item.totalChunks }} 分片
          </div>
        </div>
      </div>
    </div>

    <!-- 隐藏的文件选择器 -->
    <input
      ref="mediaInputRef"
      type="file"
      accept="image/*,video/*"
      multiple
      style="display: none"
      @change="handleMediaSelect"
    />
  </Dialog>
</template>

<script setup>
import { ref, reactive, getCurrentInstance, nextTick } from 'vue'
import Dialog from '@/components/Dialog.vue'
import MomentChunkUploadApi from '@/utils/MomentChunkUploadApi'

const { proxy } = getCurrentInstance()

const emit = defineEmits(['refresh'])

const dialogConfig = ref({
  show: false,
  title: '发表朋友圈',
  buttons: [
    {
      type: 'primary',
      text: '发表',
      click: () => {
        publishMoment()
      }
    }
  ]
})

const formData = reactive({
  content: '',
  location: '',
  visibility: 0
})

const visibilityOptions = [
  { value: 0, label: '公开' },
  { value: 1, label: '好友可见' },
  { value: 2, label: '仅自己可见' }
]

const mediaList = ref([])
const mediaInputRef = ref(null)

// 上传进度状态
const uploadingFiles = ref([])
const isUploading = ref(false)

const show = () => {
  dialogConfig.value.show = true
  formData.content = ''
  formData.location = ''
  formData.visibility = 0
  mediaList.value = []
  uploadingFiles.value = []
  isUploading.value = false
}

const closeDialog = () => {
  if (isUploading.value) {
    proxy.Confirm({
      message: '文件正在上传中，确定要关闭吗？',
      okfun: () => {
        dialogConfig.value.show = false
        isUploading.value = false
        uploadingFiles.value = []
      }
    })
    return
  }
  dialogConfig.value.show = false
}

const selectMedia = () => {
  mediaInputRef.value.click()
}

const handleMediaSelect = (e) => {
  const files = Array.from(e.target.files)
  if (files.length + mediaList.value.length > 9) {
    proxy.Message.warning('最多只能选择9张图片/视频')
    return
  }

  files.forEach((file) => {
    const isImage = file.type.startsWith('image/')
    const isVideo = file.type.startsWith('video/')

    if (!isImage && !isVideo) {
      proxy.Message.warning('只支持图片和视频格式')
      return
    }

    if (isVideo && file.size > 100 * 1024 * 1024) {
      proxy.Message.warning('视频大小不能超过100MB')
      return
    }

    const reader = new FileReader()
    reader.onload = (event) => {
      mediaList.value.push({
        file: file,
        preview: event.target.result,
        type: isImage ? 'image' : 'video'
      })
    }
    reader.readAsDataURL(file)
  })

  e.target.value = ''
}

const removeMedia = (index) => {
  mediaList.value.splice(index, 1)
}

const publishMoment = async () => {
  if (!formData.content.trim() && mediaList.value.length === 0) {
    proxy.Message.warning('请输入内容或选择图片/视频')
    return
  }

  if (isUploading.value) {
    proxy.Message.warning('文件正在上传中，请稍候...')
    return
  }

  console.log('开始发布朋友圈, 媒体数量:', mediaList.value.length)

  // 先发布朋友圈获取momentId
  const result = await proxy.Request({
    url: proxy.Api.publishMoment,
    params: {
      content: formData.content,
      visibility: formData.visibility,
      location: formData.location
    }
  })

  if (!result) return

  const momentId = result.data.id
  console.log('朋友圈发布成功, momentId:', momentId)

  // 上传媒体文件（使用分片上传）
  if (mediaList.value.length > 0) {
    await uploadMediaFiles(momentId)
  } else {
    proxy.Message.success('发表成功')
    closeDialog()
    emit('refresh')
  }
}

// 使用分片上传媒体文件
const uploadMediaFiles = async (momentId) => {
  isUploading.value = true
  
  // 初始化上传进度列表
  uploadingFiles.value = mediaList.value.map((media, index) => ({
    fileName: media.file.name,
    type: media.type === 'image' ? '图片' : '视频',
    progress: 0,
    uploadedChunks: 0,
    totalChunks: 0,
    status: 'uploading',
    index
  }))

  try {
    // 并发上传所有文件
    const uploadPromises = mediaList.value.map(async (media, index) => {
      const mediaType = media.type === 'image' ? 0 : 1
      
      console.log(`开始上传第 ${index + 1} 个文件:`, media.file.name, '大小:', (media.file.size / 1024 / 1024).toFixed(2), 'MB')
      
      try {
        await MomentChunkUploadApi.uploadMedia(media.file, momentId, mediaType, {
          onProgress: (progress, uploadedCount, totalChunks) => {
            if (uploadingFiles.value[index]) {
              uploadingFiles.value[index].progress = progress
              uploadingFiles.value[index].uploadedChunks = uploadedCount
              uploadingFiles.value[index].totalChunks = totalChunks
            }
          },
          
          onChunkSuccess: (uploadedCount, totalChunks) => {
            console.log(`文件 ${media.file.name} 分片上传: ${uploadedCount}/${totalChunks}`)
          },
          
          onComplete: (fileId) => {
            console.log(`文件 ${media.file.name} 上传完成, fileId:`, fileId)
            if (uploadingFiles.value[index]) {
              uploadingFiles.value[index].status = 'success'
            }
          },
          
          onError: (error) => {
            console.error(`文件 ${media.file.name} 上传失败:`, error)
            if (uploadingFiles.value[index]) {
              uploadingFiles.value[index].status = 'error'
            }
            throw error
          }
        })
      } catch (error) {
        console.error(`文件 ${media.file.name} 上传异常:`, error)
        throw error
      }
    })
    
    // 等待所有文件上传完成
    await Promise.all(uploadPromises)
    
    console.log('所有媒体文件上传完成')
    proxy.Message.success('发表成功')
    
    // 延迟关闭，让用户看到上传完成状态
    setTimeout(() => {
      isUploading.value = false
      uploadingFiles.value = []
      closeDialog()
      emit('refresh')
    }, 1000)
    
  } catch (error) {
    console.error('媒体文件上传失败:', error)
    proxy.Message.error('部分文件上传失败，请重试')
    isUploading.value = false
  }
}

defineExpose({
  show
})
</script>

<style lang="scss" scoped>
.publish-moment {
  padding: 10px 0;
}

.media-preview {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 15px;

  .media-item {
    position: relative;
    width: 100%;
    padding-bottom: 100%;
    border-radius: 4px;
    overflow: hidden;
    background: #f5f5f5;

    img,
    video {
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .media-mask {
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      background: rgba(0, 0, 0, 0.3);
      opacity: 0;
      transition: opacity 0.2s;
      display: flex;
      align-items: center;
      justify-content: center;

      .icon-close {
        color: #fff;
        font-size: 24px;
        cursor: pointer;
      }
    }

    &:hover .media-mask {
      opacity: 1;
    }

    .video-tag {
      position: absolute;
      bottom: 4px;
      right: 4px;
      background: rgba(0, 0, 0, 0.6);
      color: #fff;
      padding: 2px 6px;
      border-radius: 2px;
      font-size: 12px;

      .iconfont {
        font-size: 12px;
      }
    }
  }

  .add-media {
    width: 100%;
    padding-bottom: 100%;
    position: relative;
    border: 2px dashed #ddd;
    border-radius: 4px;
    cursor: pointer;
    transition: all 0.2s;

    .iconfont {
      position: absolute;
      top: 50%;
      left: 50%;
      transform: translate(-50%, -50%);
      font-size: 32px;
      color: #999;
    }

    &:hover {
      border-color: #07c160;

      .iconfont {
        color: #07c160;
      }
    }
  }
}

.media-actions {
  margin-top: 15px;
  display: flex;
  gap: 15px;

  .action-item {
    flex: 1;
    padding: 20px;
    border: 1px solid #e8e8e8;
    border-radius: 4px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    transition: all 0.2s;

    .iconfont {
      font-size: 32px;
      color: #07c160;
    }

    span {
      font-size: 13px;
      color: #666;
    }

    &:hover {
      border-color: #07c160;
      background: #f0fdf4;
    }
  }
}

.location-input {
  margin-top: 15px;
}

.visibility-select {
  margin-top: 15px;
  display: flex;
  align-items: center;
  gap: 10px;

  .label {
    font-size: 14px;
    color: #666;
  }
}

.uploading-panel {
  margin-top: 20px;
  padding: 15px;
  background: #f8f9fa;
  border-radius: 8px;
  border: 1px solid #e8e8e8;

  .uploading-title {
    font-size: 14px;
    font-weight: 500;
    color: #333;
    margin-bottom: 15px;
    display: flex;
    align-items: center;
    gap: 8px;

    .iconfont {
      font-size: 16px;
      color: #07c160;
      animation: rotate 1s linear infinite;
    }
  }

  .upload-item {
    margin-bottom: 15px;

    &:last-child {
      margin-bottom: 0;
    }

    .file-info {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;

      .file-name {
        font-size: 13px;
        color: #333;
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        margin-right: 10px;
      }

      .file-type {
        font-size: 12px;
        color: #999;
        padding: 2px 8px;
        background: white;
        border-radius: 4px;
      }
    }

    .progress-text {
      font-size: 12px;
      color: #999;
      margin-top: 5px;
      text-align: right;
    }
  }
}

@keyframes rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
