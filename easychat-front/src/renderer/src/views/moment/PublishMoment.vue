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
          <img v-if="media.type === 'image'" :src="media.preview" />
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

const show = () => {
  dialogConfig.value.show = true
  formData.content = ''
  formData.location = ''
  formData.visibility = 0
  mediaList.value = []
}

const closeDialog = () => {
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

  // TODO: 上传媒体文件
  const uploadedMedia = []
  for (const media of mediaList.value) {
    // 这里应该调用文件上传接口
    // const result = await uploadFile(media.file)
    // uploadedMedia.push(result)
  }

  const result = await proxy.Request({
    url: proxy.Api.publishMoment,
    params: {
      content: formData.content,
      visibility: formData.visibility,
      location: formData.location
      // mediaList: uploadedMedia
    }
  })

  if (!result) return

  proxy.Message.success('发表成功')
  closeDialog()
  emit('refresh')
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
</style>
