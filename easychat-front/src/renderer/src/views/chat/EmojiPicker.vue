<template>
  <div class="emoji-picker">
    <div class="emoji-header">
      <span class="emoji-title">表情包</span>
      <el-button size="small" type="primary" @click="showUploadDialog = true">上传</el-button>
    </div>
    <div class="emoji-list">
      <div
        v-for="emoji in emojiList"
        :key="emoji.id"
        class="emoji-item"
        @click="selectEmoji(emoji)"
      >
        <img :src="emoji.filePath" :alt="emoji.fileName" />
        <div class="emoji-delete" @click.stop="deleteEmoji(emoji)">×</div>
      </div>
      <div v-if="emojiList.length === 0" class="empty">暂无表情包</div>
    </div>

    <!-- 上传对话框 -->
    <el-dialog v-model="showUploadDialog" title="上传表情包" width="400px">
      <el-upload
        drag
        :auto-upload="false"
        :on-change="handleFileChange"
        :limit="1"
        accept="image/*"
      >
        <el-icon class="el-icon--upload"><upload-filled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处或<em>点击上传</em></div>
      </el-upload>
      <template #footer>
        <el-button @click="showUploadDialog = false">取消</el-button>
        <el-button type="primary" @click="uploadEmoji">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getCurrentInstance } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
const { proxy } = getCurrentInstance()

const emit = defineEmits(['select'])

const emojiList = ref([])
const showUploadDialog = ref(false)
const selectedFile = ref(null)

const loadEmojiList = async () => {
  const result = await proxy.Request({
    url: proxy.Api.emojiList,
    showLoading: false
  })
  if (result) {
    emojiList.value = result.data || []
  }
}

const selectEmoji = (emoji) => {
  emit('select', emoji)
}

const handleFileChange = (file) => {
  selectedFile.value = file.raw
}

const uploadEmoji = async () => {
  if (!selectedFile.value) {
    proxy.Message.warning('请选择文件')
    return
  }
  const formData = new FormData()
  formData.append('file', selectedFile.value)
  const result = await proxy.Request({
    url: proxy.Api.emojiUpload,
    params: formData,
    showLoading: false
  })
  if (result) {
    proxy.Message.success('上传成功')
    showUploadDialog.value = false
    loadEmojiList()
  }
}

const deleteEmoji = async (emoji) => {
  try {
    await proxy.Confirm({
      message: '确定删除该表情包吗？',
      okfun: async () => {
        const result = await proxy.Request({
          url: proxy.Api.emojiDelete,
          params: { emojiId: emoji.id },
          showLoading: false
        })
        if (result) {
          proxy.Message.success('删除成功')
          loadEmojiList()
        }
      }
    })
  } catch (e) {
    // 用户取消
  }
}

onMounted(() => {
  loadEmojiList()
})
</script>

<style lang="scss" scoped>
.emoji-picker {
  padding: 10px;

  .emoji-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 10px;

    .emoji-title {
      font-size: 14px;
      font-weight: 500;
    }
  }

  .emoji-list {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    max-height: 300px;
    overflow-y: auto;

    .emoji-item {
      position: relative;
      width: 80px;
      height: 80px;
      border: 1px solid #ddd;
      border-radius: 4px;
      cursor: pointer;
      overflow: hidden;

      &:hover {
        border-color: #07c160;
      }

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }

      .emoji-delete {
        position: absolute;
        top: 2px;
        right: 2px;
        width: 16px;
        height: 16px;
        background: rgba(0, 0, 0, 0.5);
        color: #fff;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 12px;
        cursor: pointer;

        &:hover {
          background: rgba(0, 0, 0, 0.8);
        }
      }
    }

    .empty {
      text-align: center;
      color: #999;
      padding: 20px;
      width: 100%;
    }
  }
}
</style>
