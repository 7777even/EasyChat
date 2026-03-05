<template>
  <div class="moment-page">
    <!-- 发布朋友圈 -->
    <div class="moment-editor">
      <div class="editor-header">
        <span>发表朋友圈</span>
        <i class="iconfont icon-image" @click="selectImages" title="添加图片"></i>
      </div>
      <el-input
        type="textarea"
        v-model="form.content"
        :autosize="{ minRows: 3, maxRows: 6 }"
        maxlength="500"
        show-word-limit
        placeholder="这一刻的想法..."
      />
      
      <!-- 图片预览 -->
      <div v-if="selectedImages.length > 0" class="image-preview">
        <div v-for="(img, index) in selectedImages" :key="index" class="preview-item">
          <img :src="img.preview" />
          <i class="iconfont icon-close" @click="removeImage(index)"></i>
        </div>
      </div>

      <div class="editor-row">
        <el-select v-model="form.visibility" size="small" style="width: 140px">
          <el-option v-for="item in visibilityOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-input
          v-model="form.location"
          size="small"
          placeholder="所在位置"
          style="margin-left: 10px; flex: 1"
        >
          <template #prefix>
            <i class="iconfont icon-location"></i>
          </template>
        </el-input>
        <el-button type="primary" size="small" :loading="publishing" @click="publishMoment" style="margin-left: 10px">发表</el-button>
      </div>
    </div>

    <el-divider />

    <!-- 朋友圈列表 -->
    <el-scrollbar class="moment-list" ref="scrollbarRef">
      <div v-for="item in momentList" :key="item.id" class="moment-item">
        <div class="moment-header">
          <Avatar :userId="item.userId" :width="45" :borderRadius="4" />
          <div class="moment-meta">
            <div class="name">{{ item.nickName || item.userId }}</div>
            <div class="info-row">
              <span class="time">{{ formatTime(item.createTime) }}</span>
              <span v-if="item.location" class="location">
                <i class="iconfont icon-location"></i>
                {{ item.location }}
              </span>
            </div>
          </div>
          <el-dropdown v-if="item.userId === currentUserId" trigger="click" @command="(cmd) => handleMomentAction(cmd, item)">
            <i class="iconfont icon-more"></i>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="delete">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        
        <div class="moment-content" @click="showMomentDetail(item)">{{ item.content }}</div>

        <!-- 图片九宫格 -->
        <div v-if="item.mediaList && item.mediaList.length" class="moment-media" :class="'media-count-' + item.mediaList.length">
          <div
            v-for="(media, index) in item.mediaList"
            :key="media.id"
            class="media-item"
            @click.stop="previewImages(item.mediaList, index)"
          >
            <img :src="getImageUrl(media.filePath)" />
            <div v-if="media.mediaType === 1" class="video-icon">
              <i class="iconfont icon-video"></i>
            </div>
          </div>
        </div>

        <!-- 操作栏 -->
        <div class="moment-footer">
          <div class="action-bar">
            <span class="action-btn" @click="toggleLike(item)">
              <i class="iconfont" :class="item.likedByMe ? 'icon-like-fill' : 'icon-like'"></i>
              {{ item.likedByMe ? '取消' : '点赞' }}
            </span>
            <span class="action-btn" @click="showCommentInput(item)">
              <i class="iconfont icon-comment"></i>
              评论
            </span>
          </div>
        </div>

        <!-- 点赞和评论区域 -->
        <div v-if="(item.likeList && item.likeList.length) || (item.commentList && item.commentList.length)" class="interaction-panel">
          <!-- 点赞列表 -->
          <div v-if="item.likeList && item.likeList.length" class="moment-likes">
            <i class="iconfont icon-like-fill"></i>
            <span class="like-user" v-for="(like, idx) in item.likeList" :key="like.userId">
              {{ like.nickName || like.userId }}{{ idx < item.likeList.length - 1 ? '，' : '' }}
            </span>
          </div>

          <!-- 评论列表 -->
          <div v-if="item.commentList && item.commentList.length" class="moment-comments">
            <div v-for="comment in item.commentList" :key="comment.id" class="comment-line">
              <span class="name" @click="replyComment(item, comment)">{{ comment.nickName || comment.userId }}</span>
              <template v-if="comment.replyToUserId">
                <span class="reply-arrow">回复</span>
                <span class="name">{{ comment.replyToNickName || comment.replyToUserId }}</span>
              </template>
              <span class="colon">：</span>
              <span class="content">{{ comment.content }}</span>
            </div>
          </div>
        </div>

        <!-- 评论输入框 -->
        <div v-if="item.showCommentBox" class="comment-box">
          <el-input
            ref="commentInputRef"
            type="textarea"
            v-model="item.commentDraft"
            :autosize="{ minRows: 2, maxRows: 4 }"
            :placeholder="item.replyTo ? `回复 ${item.replyTo.nickName}：` : '说点什么...'"
            @keydown.enter.ctrl="submitComment(item)"
          />
          <div class="comment-actions">
            <el-button size="small" @click="cancelComment(item)">取消</el-button>
            <el-button size="small" type="primary" :loading="item.commentSubmitting" @click="submitComment(item)">发送</el-button>
          </div>
        </div>
      </div>
      
      <div v-if="loading" class="loading-tip">加载中...</div>
      <div v-else-if="momentList.length === 0" class="empty-tip">
        <i class="iconfont icon-empty"></i>
        <p>暂无朋友圈内容</p>
      </div>
      <div v-else-if="noMore" class="no-more-tip">没有更多了</div>
    </el-scrollbar>

    <!-- 图片选择器（隐藏） -->
    <input
      ref="imageInputRef"
      type="file"
      accept="image/*"
      multiple
      style="display: none"
      @change="handleImageSelect"
    />

    <!-- 发布朋友圈对话框 -->
    <PublishMoment ref="publishMomentRef" @refresh="loadMomentList" />

    <!-- 朋友圈详情对话框 -->
    <MomentDetail ref="momentDetailRef" @refresh="loadMomentList" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, getCurrentInstance, nextTick, computed } from 'vue'
import Avatar from '@/components/Avatar.vue'
import PublishMoment from './PublishMoment.vue'
import MomentDetail from './MomentDetail.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'

const { proxy } = getCurrentInstance()
const userInfoStore = useUserInfoStore()
const globalInfoStore = useGlobalInfoStore()
const currentUserId = userInfoStore.getInfo().userId

const publishMomentRef = ref(null)
const momentDetailRef = ref(null)

const form = reactive({
  content: '',
  visibility: 0,
  visibleList: '',
  invisibleList: '',
  location: ''
})

const visibilityOptions = [
  { value: 0, label: '公开' },
  { value: 1, label: '好友可见' },
  { value: 2, label: '仅自己可见' }
]

const momentList = ref([])
const publishing = ref(false)
const loading = ref(false)
const noMore = ref(false)
const pageNo = ref(1)
const pageSize = 20

const selectedImages = ref([])
const imageInputRef = ref(null)
const commentInputRef = ref(null)
const scrollbarRef = ref(null)

const getImageUrl = (filePath) => {
  if (!filePath) return ''
  const serverPort = globalInfoStore.getInfo('localServerPort')
  // 朋友圈图片不需要封面，设置 showCover=false
  return `http://localhost:${serverPort}/file?fileId=${filePath}&partType=moment&fileType=0&showCover=false&forceGet=false&${new Date().getTime()}`
}

const formatTime = (time) => {
  if (!time) return ''
  const now = Date.now()
  const diff = now - time
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour

  if (diff < minute) {
    return '刚刚'
  } else if (diff < hour) {
    return `${Math.floor(diff / minute)}分钟前`
  } else if (diff < day) {
    return `${Math.floor(diff / hour)}小时前`
  } else if (diff < 2 * day) {
    return '昨天'
  } else {
    const date = new Date(time)
    const month = (date.getMonth() + 1 + '').padStart(2, '0')
    const dayStr = (date.getDate() + '').padStart(2, '0')
    return `${month}-${dayStr}`
  }
}

const loadMomentList = async (append = false) => {
  if (loading.value) return
  loading.value = true
  
  const result = await proxy.Request({
    url: proxy.Api.loadMomentList,
    params: {
      pageNo: pageNo.value,
      pageSize: pageSize
    },
    showLoading: false
  })
  loading.value = false
  
  if (!result) return
  const list = result.data || []
  
  if (list.length < pageSize) {
    noMore.value = true
  }
  
  list.forEach((item) => {
    item.showCommentBox = false
    item.commentDraft = ''
    item.commentSubmitting = false
    item.replyTo = null
  })
  
  if (append) {
    momentList.value = momentList.value.concat(list)
  } else {
    momentList.value = list
  }
}

const selectImages = () => {
  imageInputRef.value.click()
}

const handleImageSelect = (e) => {
  const files = Array.from(e.target.files)
  if (files.length + selectedImages.value.length > 9) {
    proxy.Message.warning('最多只能选择9张图片')
    return
  }
  
  files.forEach((file) => {
    const reader = new FileReader()
    reader.onload = (event) => {
      selectedImages.value.push({
        file: file,
        preview: event.target.result
      })
    }
    reader.readAsDataURL(file)
  })
  
  e.target.value = ''
}

const removeImage = (index) => {
  selectedImages.value.splice(index, 1)
}

const showPublishDialog = () => {
  publishMomentRef.value.show()
}

const publishMoment = async () => {
  if (!form.content || !form.content.trim()) {
    if (selectedImages.value.length === 0) {
      proxy.Message.warning('请先输入内容或选择图片')
      return
    }
  }
  
  console.log('Moment.vue - 开始发布朋友圈, 图片数量:', selectedImages.value.length)
  
  publishing.value = true
  const result = await proxy.Request({
    url: proxy.Api.publishMoment,
    params: {
      content: form.content,
      visibility: form.visibility,
      visibleList: form.visibleList,
      invisibleList: form.invisibleList,
      location: form.location
    },
    showLoading: false
  })
  
  if (!result) {
    publishing.value = false
    return
  }
  
  const momentId = result.data.id
  console.log('Moment.vue - 朋友圈发布成功, momentId:', momentId)
  
  // 上传图片
  if (selectedImages.value.length > 0) {
    for (let i = 0; i < selectedImages.value.length; i++) {
      const img = selectedImages.value[i]
      console.log(`Moment.vue - 开始上传第 ${i + 1} 个文件:`, img.file.name)
      
      const formDataObj = new FormData()
      formDataObj.append('momentId', momentId)
      formDataObj.append('file', img.file)
      formDataObj.append('mediaType', 0) // 0表示图片
      
      const uploadResult = await proxy.Request({
        url: proxy.Api.uploadMomentMedia,
        params: formDataObj,
        showLoading: false
      })
      
      console.log(`Moment.vue - 第 ${i + 1} 个文件上传结果:`, uploadResult)
      
      if (!uploadResult || uploadResult.code !== 200) {
        proxy.Message.error(`图片上传失败: ${uploadResult?.info || '未知错误'}`)
      }
    }
  }
  
  publishing.value = false
  
  // 重新加载朋友圈列表以显示新发布的内容（包含图片）
  pageNo.value = 1
  await loadMomentList(false)
  
  form.content = ''
  form.location = ''
  selectedImages.value = []
  
  proxy.Message.success('发表成功')
}

const showMomentDetail = (moment) => {
  momentDetailRef.value.show(moment)
}

const toggleLike = async (moment) => {
  const result = await proxy.Request({
    url: proxy.Api.likeMoment,
    params: {
      momentId: moment.id,
      cancel: moment.likedByMe
    },
    showLoading: false
  })
  
  if (!result) return
  const likeResult = result.data
  moment.likedByMe = likeResult.liked
  moment.likeList = likeResult.likeList || []
}

const showCommentInput = (moment) => {
  moment.showCommentBox = true
  moment.replyTo = null
  nextTick(() => {
    if (commentInputRef.value && commentInputRef.value.length) {
      commentInputRef.value[0].focus()
    }
  })
}

const replyComment = (moment, comment) => {
  moment.showCommentBox = true
  moment.replyTo = {
    userId: comment.userId,
    nickName: comment.nickName,
    commentId: comment.id
  }
  nextTick(() => {
    if (commentInputRef.value && commentInputRef.value.length) {
      commentInputRef.value[0].focus()
    }
  })
}

const cancelComment = (moment) => {
  moment.showCommentBox = false
  moment.commentDraft = ''
  moment.replyTo = null
}

const submitComment = async (moment) => {
  if (!moment.commentDraft || !moment.commentDraft.trim()) {
    proxy.Message.warning('请输入评论内容')
    return
  }
  
  moment.commentSubmitting = true
  const params = {
    momentId: moment.id,
    content: moment.commentDraft
  }
  
  if (moment.replyTo) {
    params.replyToUserId = moment.replyTo.userId
    params.parentId = moment.replyTo.commentId
  }
  
  const result = await proxy.Request({
    url: proxy.Api.commentMoment,
    params: params,
    showLoading: false
  })
  
  moment.commentSubmitting = false
  if (!result) return
  
  if (!moment.commentList) {
    moment.commentList = []
  }
  moment.commentList.push(result.data)
  moment.commentDraft = ''
  moment.showCommentBox = false
  moment.replyTo = null
}

const handleMomentAction = async (command, moment) => {
  if (command === 'delete') {
    proxy.Confirm({
      message: '确定要删除这条朋友圈吗？',
      okfun: async () => {
        const result = await proxy.Request({
          url: proxy.Api.deleteMoment,
          params: {
            momentId: moment.id
          },
          showLoading: false
        })
        
        if (!result) return
        
        const index = momentList.value.findIndex((item) => item.id === moment.id)
        if (index > -1) {
          momentList.value.splice(index, 1)
        }
        proxy.Message.success('删除成功')
      }
    })
  }
}

const previewImages = (mediaList, startIndex) => {
  const urls = mediaList.map((m) => m.filePath)
  // TODO: 使用 Element Plus 的图片预览组件
  console.log('预览图片', urls, startIndex)
}

const handleScroll = () => {
  const scrollbar = scrollbarRef.value
  if (!scrollbar) return
  
  const { scrollTop, scrollHeight, clientHeight } = scrollbar.wrapRef
  if (scrollTop + clientHeight >= scrollHeight - 100 && !loading.value && !noMore.value) {
    pageNo.value++
    loadMomentList(true)
  }
}

onMounted(() => {
  loadMomentList()
  
  // 监听滚动事件
  nextTick(() => {
    if (scrollbarRef.value && scrollbarRef.value.wrapRef) {
      scrollbarRef.value.wrapRef.addEventListener('scroll', handleScroll)
    }
  })
})
</script>

<style lang="scss" scoped>
.moment-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 15px;
  box-sizing: border-box;
  background: #f5f5f5;
}

.moment-editor {
  background: #fff;
  padding: 16px;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  
  .editor-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-weight: 600;
    margin-bottom: 12px;
    font-size: 15px;
    
    .iconfont {
      font-size: 20px;
      color: #07c160;
      cursor: pointer;
      transition: all 0.2s;
      
      &:hover {
        color: #06ad56;
        transform: scale(1.1);
      }
    }
  }
  
  .image-preview {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
    margin-top: 10px;
    
    .preview-item {
      position: relative;
      width: 100%;
      padding-bottom: 100%;
      border-radius: 4px;
      overflow: hidden;
      
      img {
        position: absolute;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        object-fit: cover;
      }
      
      .icon-close {
        position: absolute;
        top: 4px;
        right: 4px;
        width: 20px;
        height: 20px;
        background: rgba(0, 0, 0, 0.6);
        color: #fff;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        font-size: 12px;
        
        &:hover {
          background: rgba(0, 0, 0, 0.8);
        }
      }
    }
  }
  
  .editor-row {
    margin-top: 12px;
    display: flex;
    align-items: center;
    gap: 10px;
  }
}

.moment-list {
  flex: 1;
  margin-top: 15px;
  
  .moment-item {
    background: #fff;
    padding: 16px;
    border-radius: 8px;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
    margin-bottom: 12px;
    transition: all 0.2s;
    
    &:hover {
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
    }
  }
}

.moment-header {
  display: flex;
  align-items: flex-start;
  position: relative;
  
  .moment-meta {
    margin-left: 12px;
    flex: 1;
    
    .name {
      font-weight: 600;
      color: #1a1a1a;
      font-size: 15px;
      cursor: pointer;
      
      &:hover {
        color: #07c160;
      }
    }
    
    .info-row {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-top: 4px;
      
      .time {
        font-size: 12px;
        color: #999;
      }
      
      .location {
        font-size: 12px;
        color: #576b95;
        display: flex;
        align-items: center;
        gap: 2px;
        
        .iconfont {
          font-size: 12px;
        }
      }
    }
  }
  
  .icon-more {
    position: absolute;
    top: 0;
    right: 0;
    font-size: 18px;
    color: #999;
    cursor: pointer;
    padding: 4px;
    
    &:hover {
      color: #333;
    }
  }
}

.moment-content {
  margin-top: 10px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #333;
  font-size: 14px;
}

.moment-media {
  margin-top: 10px;
  display: grid;
  gap: 4px;
  
  &.media-count-1 {
    grid-template-columns: 1fr;
    max-width: 300px;
  }
  
  &.media-count-2,
  &.media-count-4 {
    grid-template-columns: repeat(2, 1fr);
  }
  
  &.media-count-3,
  &.media-count-5,
  &.media-count-6,
  &.media-count-7,
  &.media-count-8,
  &.media-count-9 {
    grid-template-columns: repeat(3, 1fr);
  }
  
  .media-item {
    position: relative;
    width: 100%;
    padding-bottom: 100%;
    border-radius: 4px;
    overflow: hidden;
    cursor: pointer;
    
    img {
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
      transition: transform 0.2s;
    }
    
    &:hover img {
      transform: scale(1.05);
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

.moment-footer {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
  
  .action-bar {
    display: flex;
    gap: 20px;
    
    .action-btn {
      display: flex;
      align-items: center;
      gap: 4px;
      color: #666;
      font-size: 13px;
      cursor: pointer;
      transition: all 0.2s;
      
      .iconfont {
        font-size: 16px;
      }
      
      .icon-like-fill {
        color: #ff6b6b;
      }
      
      &:hover {
        color: #07c160;
      }
    }
  }
}

.interaction-panel {
  margin-top: 10px;
  background: #f7f7f7;
  border-radius: 4px;
  padding: 10px 12px;
  font-size: 13px;
}

.moment-likes {
  display: flex;
  align-items: flex-start;
  line-height: 1.6;
  
  .icon-like-fill {
    color: #ff6b6b;
    font-size: 14px;
    margin-right: 6px;
    flex-shrink: 0;
    margin-top: 2px;
  }
  
  .like-user {
    color: #576b95;
    cursor: pointer;
    
    &:hover {
      text-decoration: underline;
    }
  }
}

.moment-comments {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #e8e8e8;
  
  .comment-line {
    line-height: 1.8;
    
    & + .comment-line {
      margin-top: 6px;
    }
    
    .name {
      color: #576b95;
      cursor: pointer;
      font-weight: 500;
      
      &:hover {
        text-decoration: underline;
      }
    }
    
    .reply-arrow {
      margin: 0 4px;
      color: #999;
    }
    
    .colon {
      margin-right: 4px;
    }
    
    .content {
      color: #333;
    }
  }
}

.comment-box {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #f0f0f0;
  
  .comment-actions {
    margin-top: 8px;
    text-align: right;
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }
}

.loading-tip,
.no-more-tip {
  text-align: center;
  color: #999;
  padding: 20px 0;
  font-size: 13px;
}

.empty-tip {
  text-align: center;
  color: #999;
  padding: 60px 0;
  
  .iconfont {
    font-size: 60px;
    color: #ddd;
    margin-bottom: 10px;
  }
  
  p {
    font-size: 14px;
  }
}
</style>

