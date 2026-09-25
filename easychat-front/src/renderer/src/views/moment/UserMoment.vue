<template>
  <Dialog
    :show="dialogConfig.show"
    :title="title"
    width="620px"
    :showCancel="false"
    @close="closeDialog"
  >
    <div class="user-moment">
      <!-- 个人主页头部 -->
      <div class="user-header" v-if="targetUserId">
        <Avatar :userId="targetUserId" :width="60" :borderRadius="6" />
        <div class="user-meta">
          <div class="name">{{ nickName || targetUserId }}</div>
          <div class="sub">{{ momentList.length }} 条动态</div>
        </div>
      </div>

      <el-scrollbar class="moment-list" ref="scrollbarRef">
        <div v-for="item in momentList" :key="item.id" class="moment-item">
          <div class="moment-time">{{ formatTime(item.createTime) }}</div>
          <div class="moment-content">{{ item.content }}</div>

          <div v-if="item.mediaList && item.mediaList.length" class="moment-media">
            <div
              v-for="(media, index) in item.mediaList"
              :key="media.id"
              class="media-item"
              @click="openMedia(item, media, index)"
            >
              <img v-if="media.mediaType === 0" :src="getImageUrl(media.filePath)" />
              <video v-else :src="getImageUrl(media.filePath)" preload="metadata" muted></video>
              <div v-if="media.mediaType === 1" class="video-icon">
                <i class="iconfont icon-video"></i>
              </div>
            </div>
          </div>

          <div class="moment-stat">
            <span>{{ (item.likeList && item.likeList.length) || 0 }} 赞</span>
            <span>{{ (item.commentList && item.commentList.length) || 0 }} 评论</span>
          </div>
        </div>

        <div v-if="loading" class="tip">加载中...</div>
        <div v-else-if="momentList.length === 0" class="tip empty">
          <i class="iconfont icon-empty"></i>
          <p>TA 还没有发布过动态</p>
        </div>
        <div v-else-if="noMore" class="tip">没有更多了</div>
      </el-scrollbar>

      <!-- 图片预览 -->
      <el-image-viewer
        v-if="showImageViewer"
        :url-list="previewImageList"
        :initial-index="previewStartIndex"
        @close="closeImageViewer"
      />
    </div>
  </Dialog>
</template>

<script setup>
import { ref, reactive, getCurrentInstance } from 'vue'
import Avatar from '@/components/Avatar.vue'
import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'

const { proxy } = getCurrentInstance()
const globalInfoStore = useGlobalInfoStore()

const dialogConfig = reactive({ show: false })
const targetUserId = ref('')
const nickName = ref('')
const title = ref('个人主页')

const momentList = ref([])
const loading = ref(false)
const noMore = ref(false)
const pageNo = ref(1)
const pageSize = 20

// 内置大图查看器
const showImageViewer = ref(false)
const previewImageList = ref([])
const previewStartIndex = ref(0)

const getImageUrl = (filePath) => {
  if (!filePath) return ''
  const serverPort = globalInfoStore.getInfo('localServerPort')
  return `http://localhost:${serverPort}/file?fileId=${filePath}&partType=moment&fileType=0&showCover=false&forceGet=false&${new Date().getTime()}`
}

const show = (userId, name) => {
  targetUserId.value = userId
  nickName.value = name || ''
  title.value = (name || userId) + ' 的朋友圈'
  dialogConfig.show = true
  pageNo.value = 1
  noMore.value = false
  loadList(false)
}

const closeDialog = () => {
  dialogConfig.show = false
}

const loadList = async (append = false) => {
  if (loading.value || !targetUserId.value) return
  loading.value = true
  const result = await proxy.Request({
    url: proxy.Api.userMomentList,
    params: {
      targetUserId: targetUserId.value,
      pageNo: pageNo.value,
      pageSize: pageSize
    },
    showLoading: false,
    showError: false
  })
  loading.value = false
  if (!result) return
  const list = result.data || []
  if (list.length < pageSize) {
    noMore.value = true
  }
  if (append) {
    momentList.value = momentList.value.concat(list)
  } else {
    momentList.value = list
  }
}

const formatTime = (time) => {
  if (!time) return ''
  const date = new Date(time)
  const month = (date.getMonth() + 1 + '').padStart(2, '0')
  const day = (date.getDate() + '').padStart(2, '0')
  const hour = (date.getHours() + '').padStart(2, '0')
  const minute = (date.getMinutes() + '').padStart(2, '0')
  return `${month}-${day} ${hour}:${minute}`
}

// 图片走内置大图查看器，视频走独立媒体窗口（与 MomentDetail 保持一致）
const openMedia = (moment, media) => {
  if (media.mediaType === 1) {
    window.ipcRenderer.send('newWindow', {
      windowId: 'media',
      title: '视频预览',
      path: '/showMedia',
      data: {
        currentFileId: media.filePath,
        fileList: [
          {
            partType: 'moment',
            fileId: media.filePath,
            fileType: 1,
            fileName: media.filePath,
            forceGet: false
          }
        ]
      }
    })
    return
  }
  const imageMediaList = (moment.mediaList || []).filter((m) => m.mediaType === 0)
  if (imageMediaList.length === 0) {
    return
  }
  previewImageList.value = imageMediaList.map((m) => getImageUrl(m.filePath))
  const idx = imageMediaList.findIndex((m) => m.id === media.id)
  previewStartIndex.value = idx < 0 ? 0 : idx
  showImageViewer.value = true
}

const closeImageViewer = () => {
  showImageViewer.value = false
}

defineExpose({ show })
</script>

<style lang="scss" scoped>
.user-moment {
  height: 540px;
  display: flex;
  flex-direction: column;
}

.user-header {
  display: flex;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
  margin-bottom: 10px;

  .user-meta {
    margin-left: 14px;

    .name {
      font-size: 16px;
      font-weight: 600;
      color: #1a1a1a;
    }

    .sub {
      font-size: 12px;
      color: #999;
      margin-top: 4px;
    }
  }
}

.moment-list {
  flex: 1;
}

.moment-item {
  padding: 12px 8px;
  border-bottom: 1px solid #f5f5f5;

  .moment-time {
    font-size: 12px;
    color: #999;
  }

  .moment-content {
    margin-top: 6px;
    font-size: 14px;
    color: #333;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
  }

  .moment-media {
    margin-top: 8px;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 4px;

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
        width: 36px;
        height: 36px;
        background: rgba(0, 0, 0, 0.5);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;

        .iconfont {
          color: #fff;
          font-size: 18px;
        }
      }
    }
  }

  .moment-stat {
    margin-top: 8px;
    font-size: 12px;
    color: #999;
    display: flex;
    gap: 16px;
  }
}

.tip {
  text-align: center;
  color: #999;
  padding: 20px 0;
  font-size: 13px;

  &.empty {
    padding: 60px 0;

    .iconfont {
      font-size: 50px;
      color: #ddd;
    }

    p {
      font-size: 14px;
    }
  }
}
</style>
