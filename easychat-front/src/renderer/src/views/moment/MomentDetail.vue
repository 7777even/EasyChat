<template>
  <Dialog
    :show="dialogConfig.show"
    :title="dialogConfig.title"
    width="700px"
    :showCancel="false"
    @close="closeDialog"
  >
    <div v-if="moment" class="moment-detail">
      <!-- 用户信息 -->
      <div class="user-info">
        <Avatar :userId="moment.userId" :width="50" :borderRadius="4" />
        <div class="user-meta">
          <div class="name">{{ moment.nickName || moment.userId }}</div>
          <div class="time">{{ formatTime(moment.createTime) }}</div>
        </div>
      </div>

      <!-- 内容 -->
      <div class="moment-content">{{ moment.content }}</div>

      <!-- 媒体 -->
      <div v-if="moment.mediaList && moment.mediaList.length" class="moment-media">
        <div
          v-for="(media, index) in moment.mediaList"
          :key="media.id"
          class="media-item"
          @click="previewMedia(index)"
        >
          <img v-if="media.mediaType === 0" :src="getImageUrl(media.filePath)" />
          <video v-else :src="getImageUrl(media.filePath)" />
          <div v-if="media.mediaType === 1" class="video-icon">
            <i class="iconfont icon-video"></i>
          </div>
        </div>
      </div>

      <!-- 位置 -->
      <div v-if="moment.location" class="location">
        <i class="iconfont icon-location"></i>
        {{ moment.location }}
      </div>

      <!-- 点赞和评论统计 -->
      <!-- <div class="stats">
        <span class="stat-item">
          <i class="iconfont icon-like"></i>
          {{ moment.likeList?.length || 0 }}
        </span>
        <span class="stat-item">
          <i class="iconfont icon-comment"></i>
          {{ moment.commentList?.length || 0 }}
        </span>
      </div> -->

      <el-divider />

      <!-- 点赞列表 -->
      <div v-if="moment.likeList && moment.likeList.length" class="like-section">
        <div class="section-title">
          <span class="like-icon">👍</span>
          点赞
        </div>
        <div class="like-list">
          <div v-for="like in moment.likeList" :key="like.userId" class="like-item">
            <Avatar :userId="like.userId" :width="32" :borderRadius="4" />
            <span class="name">{{ like.nickName || like.userId }}</span>
          </div>
        </div>
      </div>

      <!-- 评论列表 -->
      <div class="comment-section">
        <div class="section-title">
          <i class="iconfont icon-comment"></i>
          评论 {{ moment.commentList?.length || 0 }}
        </div>
        <div v-if="moment.commentList && moment.commentList.length" class="comment-list">
          <div v-for="comment in moment.commentList" :key="comment.id" class="comment-item">
            <Avatar :userId="comment.userId" :width="36" :borderRadius="4" />
            <div class="comment-content">
              <div class="comment-header">
                <span class="name">{{ comment.nickName || comment.userId }}</span>
                <span class="time">{{ formatTime(comment.createTime) }}</span>
              </div>
              <div class="comment-text">
                <template v-if="comment.replyToUserId">
                  回复
                  <span class="reply-name">{{ comment.replyToNickName || comment.replyToUserId }}</span>
                  ：
                </template>
                {{ comment.content }}
              </div>
              <div class="comment-actions">
                <span class="action" @click="replyComment(comment)">回复</span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="empty-comment">暂无评论</div>
      </div>

      <!-- 评论输入框 -->
      <div class="comment-input">
        <el-input
          v-model="commentDraft"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          :placeholder="replyTo ? `回复 ${replyTo.nickName}：` : '说点什么...'"
        />
        <div class="input-actions">
          <el-button v-if="replyTo" size="small" @click="cancelReply">取消回复</el-button>
          <el-button size="small" type="primary" :loading="submitting" @click="submitComment">发送</el-button>
        </div>
      </div>
    </div>

    <!-- 图片预览 -->
    <el-image-viewer
      v-if="showImageViewer"
      :url-list="previewImageList"
      :initial-index="previewStartIndex"
      @close="closeImageViewer"
    />
  </Dialog>
</template>

<script setup>
import { ref, getCurrentInstance } from 'vue'
import Dialog from '@/components/Dialog.vue'
import Avatar from '@/components/Avatar.vue'
import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'

const { proxy } = getCurrentInstance()
const globalInfoStore = useGlobalInfoStore()

const emit = defineEmits(['refresh'])

const dialogConfig = ref({
  show: false,
  title: '朋友圈详情'
})

const moment = ref(null)
const commentDraft = ref('')
const replyTo = ref(null)
const submitting = ref(false)

const getImageUrl = (filePath) => {
  if (!filePath) return ''
  const serverPort = globalInfoStore.getInfo('localServerPort')
  // 朋友圈图片不需要封面，设置 showCover=false
  return `http://localhost:${serverPort}/file?fileId=${filePath}&partType=moment&fileType=0&showCover=false&forceGet=false&${new Date().getTime()}`
}

const formatTime = (time) => {
  if (!time) return ''
  const date = new Date(time)
  return `${date.getFullYear()}-${(date.getMonth() + 1 + '').padStart(2, '0')}-${(date.getDate() + '').padStart(2, '0')} ${(
    date.getHours() + ''
  ).padStart(2, '0')}:${(date.getMinutes() + '').padStart(2, '0')}`
}

const show = (momentData) => {
  moment.value = momentData
  dialogConfig.value.show = true
  commentDraft.value = ''
  replyTo.value = null
}

const closeDialog = () => {
  dialogConfig.value.show = false
}

const replyComment = (comment) => {
  replyTo.value = {
    userId: comment.userId,
    nickName: comment.nickName,
    commentId: comment.id
  }
}

const cancelReply = () => {
  replyTo.value = null
}

const submitComment = async () => {
  if (!commentDraft.value.trim()) {
    proxy.Message.warning('请输入评论内容')
    return
  }

  submitting.value = true
  const params = {
    momentId: moment.value.id,
    content: commentDraft.value
  }

  if (replyTo.value) {
    params.replyToUserId = replyTo.value.userId
    params.parentId = replyTo.value.commentId
  }

  const result = await proxy.Request({
    url: proxy.Api.commentMoment,
    params: params,
    showLoading: false
  })

  submitting.value = false
  if (!result) return

  if (!moment.value.commentList) {
    moment.value.commentList = []
  }
  moment.value.commentList.push(result.data)
  commentDraft.value = ''
  replyTo.value = null

  emit('refresh')
}

const showImageViewer = ref(false)
const previewImageList = ref([])
const previewStartIndex = ref(0)

const previewMedia = (index) => {
  // 只预览图片类型的媒体
  const imageMediaList = moment.value.mediaList.filter((m) => m.mediaType === 0)
  if (imageMediaList.length === 0) {
    proxy.Message.warning('暂不支持视频预览')
    return
  }
  
  previewImageList.value = imageMediaList.map((m) => getImageUrl(m.filePath))
  // 找到当前点击的图片在图片列表中的索引
  const clickedMedia = moment.value.mediaList[index]
  if (clickedMedia.mediaType === 0) {
    previewStartIndex.value = imageMediaList.findIndex((m) => m.id === clickedMedia.id)
    showImageViewer.value = true
  } else {
    proxy.Message.warning('暂不支持视频预览')
  }
}

const closeImageViewer = () => {
  showImageViewer.value = false
}

defineExpose({
  show
})
</script>

<style lang="scss" scoped>
.moment-detail {
  padding: 10px 0;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;

  .user-meta {
    .name {
      font-weight: 600;
      font-size: 16px;
      color: #1a1a1a;
    }

    .time {
      font-size: 12px;
      color: #999;
      margin-top: 4px;
    }
  }
}

.moment-content {
  margin-top: 15px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #333;
  font-size: 14px;
}

.moment-media {
  margin-top: 15px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;

  .media-item {
    position: relative;
    width: 100%;
    padding-bottom: 100%;
    border-radius: 4px;
    overflow: hidden;
    cursor: pointer;

    img,
    video {
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .video-icon {
      position: absolute;
      top: 50%;
      left: 50%;
      transform: translate(-50%, -50%);
      width: 40px;
      height: 40px;
      background: rgba(0, 0, 0, 0.5);
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;

      .iconfont {
        color: #fff;
        font-size: 20px;
      }
    }
  }
}

.location {
  margin-top: 10px;
  color: #576b95;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 4px;

  .iconfont {
    font-size: 14px;
  }
}

.stats {
  margin-top: 15px;
  display: flex;
  gap: 20px;

  .stat-item {
    display: flex;
    align-items: center;
    gap: 4px;
    color: #666;
    font-size: 14px;

    .iconfont {
      font-size: 16px;
    }
  }
}

.like-section,
.comment-section {
  margin-top: 20px;

  .section-title {
    font-weight: 600;
    font-size: 15px;
    color: #1a1a1a;
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 12px;

    .iconfont {
      font-size: 16px;
    }

    .like-icon {
      font-size: 16px;
    }
  }
}

.like-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;

  .like-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    background: #f7f7f7;
    border-radius: 20px;

    .name {
      font-size: 13px;
      color: #333;
    }
  }
}

.comment-list {
  .comment-item {
    display: flex;
    gap: 10px;
    padding: 12px 0;
    border-bottom: 1px solid #f0f0f0;

    &:last-child {
      border-bottom: none;
    }

    .comment-content {
      flex: 1;

      .comment-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 6px;

        .name {
          font-weight: 600;
          font-size: 14px;
          color: #1a1a1a;
        }

        .time {
          font-size: 12px;
          color: #999;
        }
      }

      .comment-text {
        line-height: 1.6;
        font-size: 14px;
        color: #333;

        .reply-name {
          color: #576b95;
          font-weight: 500;
        }
      }

      .comment-actions {
        margin-top: 6px;

        .action {
          font-size: 13px;
          color: #666;
          cursor: pointer;

          &:hover {
            color: #07c160;
          }
        }
      }
    }
  }
}

.empty-comment {
  text-align: center;
  color: #999;
  padding: 30px 0;
  font-size: 13px;
}

.comment-input {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #f0f0f0;

  .input-actions {
    margin-top: 10px;
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }
}
</style>
